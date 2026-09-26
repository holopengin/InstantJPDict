package com.holopengin.instantjpdict

import uniffi.nav_graph_core.GapCell

/**
 * One recognised line's per-timestep top-K, in the shape the decode actually
 * crosses.
 *
 * The compact CTC decode hands over `raw_alternatives` as **one flat row-major
 * cell list** plus `raw_rows` boundaries (`ctc_decode.rs`), precisely so the
 * host does not have to rebuild the nested shape. Until now
 * [OcrEngine.toPpoResult] threw that away: it expanded the flat list into
 * `List<List<Pair<Char, Float>>>` — one `ArrayList` per timestep and a `Pair`
 * **plus a boxed `Float`** per cell — and then [OcrEngine.computeCharBoxes]
 * expanded the very same rows *again* into `CharPlacement.Step` objects for
 * CAP's `steps`. On the book-page fixture that was ~679 row lists, ~10,185
 * `Pair`s and ~10,185 `Float`s, then as many `Step`s and `GapCell`s again, on a
 * path whose only readers want the top one or two cells per row.
 *
 * ## The representation
 *
 * * `cells` — the flat list, **shared with the decode result, never copied**.
 * * `rowStart` — the row boundaries as an [IntArray] (`raw_rows` arrives as
 *   boxed `Long`s; the page path never wants an object per boundary).
 * * `topChars` / `topScores` — a **top-2 table**: two `Int`/`Float` slots per
 *   row, filled in the single pass that walks the boundaries. This is the only
 *   per-row work the page path does eagerly, and it is what CAP's `steps` are
 *   built from — two primitive-array reads per row instead of a walk over all
 *   15 cells. It is also the accessor the audited top-1/top-2 readers use
 *   ([topChar], [topScore], [secondScore]).
 * * `cache` — rows materialised on demand by [get], so a second read of the
 *   same row is free. Every consumer that wants the full rows
 *   ([OcrEngine.reDecodeLineResult], `GapCandidates.generate`, the rare second
 *   [com.holopengin.instantjpdict.util.BlankGaps] plan call) goes through here
 *   and gets the identical `List<List<Pair<Char, Float>>>` it got before.
 *
 * ## Why it *is* a `List<List<Pair<Char, Float>>>`
 *
 * [LineResult] and [OcrEngine.PPOcrResult] are data classes whose
 * `rawAlternatives` field every consumer, `copy` call, test fixture and
 * `assertEquals` already speaks. Being a [java.util.AbstractList] keeps that
 * contract byte-for-byte: `get`, `size`, `equals`, `hashCode` and `toString`
 * are inherited, so a `TimestepTopK` compares and hashes exactly like the
 * nested `ArrayList` it replaces, and a lazily built line is `equals` to an
 * eagerly built one. A nullable backing field plus an accessor on `LineResult`
 * was the other option, but it moves the laziness into the constructor: every
 * `LineResult(rawAlternatives = …)` call site and every
 * `copy(rawAlternatives = …)` would have to learn the compact type, for no
 * behavioural gain and a much larger diff.
 *
 * ## Threading
 *
 * A line's instance is created on the recognition worker and published to the
 * main thread through the same `Handler` post the [LineResult] itself takes, so
 * the immutable fields are safely published. `cache` is written only by [get];
 * a concurrent double build of one row is idempotent (both copies are equal),
 * which is why it is a plain array rather than a `ConcurrentHashMap`.
 */
class TimestepTopK private constructor(
    private val cells: List<GapCell>,
    private val rowStart: IntArray,
    /** `topChars[2i]`, `topChars[2i + 1]` — the row's first two code units. */
    private val topChars: IntArray,
    /** `topScores[2i]`, `topScores[2i + 1]` — the row's first two scores. */
    private val topScores: FloatArray,
    private val cache: Array<List<Pair<Char, Float>>?>,
) : AbstractList<List<Pair<Char, Float>>>() {

    override val size: Int get() = rowStart.size - 1

    /** Timesteps, i.e. rows — the same number as [size], named for the readers. */
    val rowCount: Int get() = rowStart.size - 1

    /** Cells, i.e. `rowStart[rowCount]`. */
    val cellCount: Int get() = rowStart[rowCount]

    /**
     * Rows built so far. A recognised line reads exactly
     * `LineResult.alternatives.size` of them (the emitted timesteps) and leaves
     * the other ~90% flat, so this is the number that says whether the page path
     * paid for the table or not.
     */
    val materialisedRows: Int get() = built

    @Volatile
    private var built = 0

    override fun get(index: Int): List<Pair<Char, Float>> {
        cache[index]?.let { return it }
        val from = rowStart[index]
        val to = rowStart[index + 1]
        val row = ArrayList<Pair<Char, Float>>(to - from)
        for (k in from until to) {
            val cell = cells[k]
            row.add(Char(cell.ch) to cell.score)
        }
        cache[index] = row
        built++
        return row
    }

    /** The row's argmax character, or [NO_CELL] for a timestep with no cells. */
    fun topChar(index: Int): Char =
        if (rowStart[index + 1] <= rowStart[index]) NO_CELL else Char(topChars[index * 2])

    /** The argmax's own score; `0f` for an empty row, as the placement walk's. */
    fun topScore(index: Int): Float =
        if (rowStart[index + 1] <= rowStart[index]) 0f else topScores[index * 2]

    /**
     * The runner-up's score, or `0f` when the row holds fewer than two cells.
     *
     * `0f` is the placement walk's own rule for a one-cell row
     * (`jpdict_core::char_placement::runs_from_steps`:
     * `if alts.len() > 1 { alts[1].1 } else { 0.0 }`), so a row that really
     * holds one cell must keep reporting no second — inventing a second cell
     * would change the top1−top2 margin.
     */
    fun secondScore(index: Int): Float =
        if (rowStart[index + 1] - rowStart[index] < 2) 0f else topScores[index * 2 + 1]

    /**
     * CAP's `steps`: every row truncated to its **first two cells**.
     *
     * `jpdict_core::char_placement::runs_from_steps` is the only reader of
     * `steps` anywhere on this path (the shim's `place_dispatch` only converts
     * it, and the PC `place_core` only calls that one function): it reads
     * `alts[0].0` to replay the greedy walk, `alts[0].1` for the mean top-1
     * confidence and `alts[1].1` for the mean top1−top2 margin — the
     * runner-up's **character** is never read, and nothing reads past the
     * second entry. A row that held `n` cells therefore contributes exactly the
     * same numbers after truncation to `min(2, n)`, which is what makes the
     * placed boxes bit-identical while the page stops building `n − 2` `Step`
     * objects per timestep.
     */
    internal fun capStepRows(): List<List<CharPlacement.Step>> = List(size) { i ->
        when (minOf(2, rowStart[i + 1] - rowStart[i])) {
            0 -> ArrayList(0)
            1 -> ArrayList<CharPlacement.Step>(1)
                .apply { add(CharPlacement.Step(topChar(i), topScore(i))) }
            else -> ArrayList<CharPlacement.Step>(2).apply {
                add(CharPlacement.Step(topChar(i), topScore(i)))
                // The runner-up's score through the audited accessor, its
                // character straight out of the flat list: the placement walk
                // never reads it, but keeping the real one means a future reader
                // cannot be handed a placeholder.
                add(CharPlacement.Step(Char(cells[rowStart[i] + 1].ch), secondScore(i)))
            }
        }
    }

    /**
     * The same rows as the gap pipeline's `List<List<GapCell>>` boundary, in
     * cell order — the shape `blankGapTimestepColumns` and `blankGapsPlan`
     * take. One pass, one `GapCell` alias per cell (the flat list's own cells,
     * not copies). Only the rare callers that cross the whole table read this.
     */
    fun gapCellRows(): List<List<GapCell>> = List(size) { i ->
        val from = rowStart[i]
        val to = rowStart[i + 1]
        ArrayList<GapCell>(to - from).apply { for (k in from until to) add(cells[k]) }
    }

    companion object {
        /** What [topChar] reports for a timestep the decoder emitted no cell for. */
        const val NO_CELL: Char = '�'

        /**
         * No timesteps — the shape [OcrEngine.PPOcrResult] defaults to and the
         * `emptyList()` every hand-built line passes.
         */
        val EMPTY: TimestepTopK = TimestepTopK(
            emptyList(), intArrayOf(0), IntArray(0), FloatArray(0), emptyArray(),
        )

        /** The decoder's top-K width; only sizes the [of] copy below. */
        private const val TOP_K = 15

        /**
         * The compact decode result, verbatim: the flat cell list is adopted
         * (not copied), `raw_rows` becomes an [IntArray], and one pass fills the
         * top-2 table. The boundary shape is checked, not defended against —
         * the shim always emits `0, …, cells.len()` ascending, and every other
         * decode entry on this path `check`s its own input the same way.
         */
        fun of(cells: List<GapCell>, rowStart: List<Long>): TimestepTopK {
            check(rowStart.isNotEmpty()) { "compact CTC result has no row boundaries" }
            val n = rowStart.size - 1
            val starts = IntArray(n + 1)
            for (i in 0..n) {
                val at = rowStart[i]
                check(at in 0..cells.size.toLong()) {
                    "compact CTC row boundary $at outside 0..${cells.size}"
                }
                if (i > 0) check(at >= starts[i - 1]) { "compact CTC row boundaries descend at $i" }
                starts[i] = at.toInt()
            }
            return of(cells, starts)
        }

        /**
         * The core: an already-validated [IntArray] of `rows + 1` ascending
         * boundaries inside the cell list. One pass fills the top-2 table.
         */
        private fun of(cells: List<GapCell>, rowStart: IntArray): TimestepTopK {
            val n = rowStart.size - 1
            val topChars = IntArray(n * 2)
            val topScores = FloatArray(n * 2)
            for (i in 0 until n) {
                val from = rowStart[i]
                val to = rowStart[i + 1]
                if (from < to) {
                    topChars[i * 2] = cells[from].ch
                    topScores[i * 2] = cells[from].score
                }
                if (to - from >= 2) {
                    topChars[i * 2 + 1] = cells[from + 1].ch
                    topScores[i * 2 + 1] = cells[from + 1].score
                }
            }
            return TimestepTopK(cells, rowStart, topChars, topScores, arrayOfNulls(n))
        }

        /**
         * The nested shape, for the callers that already hold it: the long-line
         * stitch paths concatenate chunk rows before they can be re-sliced, and
         * a test fixture builds a line by hand. Each cell becomes a `GapCell`
         * (the source rows are `Pair`s, and the flat table owns `GapCell`s), so
         * this is the re-slicing those paths already paid for. Passing a
         * `TimestepTopK` back returns it untouched.
         */
        fun of(rows: List<List<Pair<Char, Float>>>): TimestepTopK {
            if (rows is TimestepTopK) return rows
            if (rows.isEmpty()) return EMPTY
            val cells = ArrayList<GapCell>(rows.size * TOP_K)
            val starts = IntArray(rows.size + 1)
            rows.forEachIndexed { i, row ->
                starts[i] = cells.size
                for ((c, s) in row) cells.add(GapCell(c.code, s))
            }
            starts[rows.size] = cells.size
            return of(cells, starts)
        }
    }
}

/**
 * The line's top-K in the compact shape, whatever shape it arrived in.
 *
 * A recognised line stores a [TimestepTopK], so this is a cast; a line built by
 * a test fixture (or by the stitch paths, which concatenate nested rows) is
 * re-sliced once — the cost that path already paid.
 */
internal val LineResult.rawTopK: TimestepTopK
    get() = rawAlternatives as? TimestepTopK ?: TimestepTopK.of(rawAlternatives)
