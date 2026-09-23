package com.holopengin.instantjpdict.util

import android.content.Context
import uniffi.nav_graph_core.KanaSoundTable as RustKanaSoundTable

/**
 * Historical kana sound changes (#81): the lookup-query normaliser for the class of
 * 旧仮名遣い that #75's variant table structurally cannot reach. The table is the
 * committed `variants/kana_sound_changes.txt` rows (source, licence and SHA-256
 * are in the `kana_sound_changes.PROVENANCE.txt` beside it; regenerate with
 * `tools/build_kana_sound_changes.py`).
 *
 * Since the util-core swap this is a thin facade over the PC `jpdict_core`
 * implementation (`core/src/util/japanese.rs`, exposed through `nav_graph_core`'s
 * UniFFI surface), so the table and the two passes have one source of truth. PC
 * inlines the rows as consts, byte-identical to the asset, so [install] installs
 * the builtin table and the asset is no longer read at runtime; [parse] still
 * parses the asset's text, which is what the tests install. The grammatical
 * conditions and the pass order are documented upstream; this class only adapts
 * types (`Char` ↔ `Int` code points, `Long` → `Int`) and keeps the API call sites
 * already use.
 *
 * **Why JMdict cannot generate this.** [KanaOrthography] folds alternations JMdict
 * records *inside one entry* as variant readings (`かつかざん`/`かっかざん`,
 * `あはれ`/`あわれ`). The historical sound changes are rules over 旧仮名遣い, and the
 * pre-reform spelling is not a reading of the modern entry at all: JMdict's readings
 * are modern kana usage, and `けふ`, `きやう`, `てふ` are simply absent from it. So the
 * rule source is the two Cabinet notices (内閣告示) that set the correspondences —
 * public domain under 著作権法第13条第2号, recorded in the licence index.
 *
 * **Query-side only, exactly like [KanaOrthography] and the kanji variant fold (#44).**
 * The reader keeps the book's own orthography; the caller searches the raw prefix
 * *alongside* this normalised one, so the pass can only add a reachable headword,
 * never take one away. Displayed text is untouched.
 *
 * **Grammatically conditioned, not character-wise.** The pass runs the 告示's two
 * kinds of change in the order the 告示 derives them:
 *
 *  1. 語中・語尾のハ行 -> ワ行 (row `ha`), each character only at the ending its
 *     活用 demands:
 *     - `ふ` -> `う` at a 終止/連体 boundary: the next character is absent, not kana,
 *       or a particle (`思ふ` -> `思う`, `言ふ` -> `言う`, `けふ` -> `けう`), and the
 *       preceding character is not `う` (夫婦/毛布 written in kana end in `ふ` and are
 *       not verb endings).
 *     - `ひ` -> `い` before a 連用形 ending (`て`, `つ`, `な`): `思ひつ` -> `思いつ`.
 *     - `へ` -> `え` before a 連用/仮定/已然 ending (`て`, `ば`, `ど`, ...).
 *     - `は` -> `わ` in the 未然形 before `ず`, `ぬ`, `む`, `ば` (`言はず` -> `言わず`).
 *       The particle `は` never folds (`本は` stays), and neither does a word-initial
 *       `は` — 語中・語尾 only.
 *  2. 母音の変化 before `う` (rows `au`/`eu`): アウ->オウ (`やう` -> `よう`,
 *     `かう` -> `こう`) and エウ->ヨウ (`けう` -> `きょう`, so ハ行転呼 feeds it:
 *     `けふ` -> `けう` -> `きょう`).
 *
 * **What is deliberately NOT applied** (measured on the #75 bench slice, 350 legacy +
 * 648 modern Aozora lines, against JMdict headwords; numbers in the #81 commit):
 *
 *  - `あ` -> `お` and `ま` -> `も` in the アウ row: each repaired nothing on the
 *    legacy slice while adding modern false candidates (`会う` -> `おう`, `しまう` ->
 *    `しもう`). The other a-row members paid for themselves or cost zero.
 *  - `ほ` -> `お` (`おほ` -> `おお`): repaired nothing, cost modern false candidates.
 *    Withheld; the table has no `ほ` pair, so it folds to itself.
 *  - the 小書き row and `を` -> `お`: withheld by #75 for the same reason, measured
 *    in `tools/wopro_bench.py`. This table does not revisit them.
 *
 * **Deterministic per query**: two passes, one character of context, no dependence on
 * page, era or how much of the line is 旧仮名. The caller composes it after
 * [KanaOrthography.modernise], so `きやう` -> `きゃう` (the #75 拗音 fold) ->
 * `きょう`.
 *
 * **Katakana is out of scope.** The same changes would rewrite modern loanwords
 * (`クラウン` -> `クロウン`), and the legacy bench slice is hiragana; the table ships
 * hiragana rows only, while [KanaOrthography] keeps its katakana pairs.
 *
 * `tools/kana_sound_changes_bench.py` re-implements this normaliser (and
 * [KanaOrthography]) in Python for the #81 measurements; the two must move
 * together. Last compared 2026-09-15 (#86/C4).
 */
object KanaSoundChanges {
    const val ASSET_PATH = "variants/kana_sound_changes.txt"

    @Volatile
    private var table: Table = Table.EMPTY

    /**
     * Install the builtin table (the committed asset's rows, inlined in PC).
     * Idempotent. [context] is kept for the call-site signature — the asset is
     * no longer read here, so it is unused.
     */
    fun install(context: Context) {
        install(Table(RustKanaSoundTable.builtin()))
    }

    /** Install an already-parsed table (tests, or a table from another source). */
    fun install(parsed: Table) {
        table = parsed
    }

    fun parse(text: String): Table = Table.parse(text)

    /** Number of distinct pairs installed; 0 when nothing is loaded. */
    val entryCount: Int get() = table.size

    /**
     * Rewrite an already-modernised lookup query (see [KanaOrthography.modernise])
     * from pre-reform sound changes onto the modern form the imported dictionary
     * keys on. Never throws and never returns null; the identity when nothing in the
     * query is a rule's input. Query-side only — callers keep displaying the raw
     * text, and the raw and #75-normalised forms are still searched alongside this.
     */
    fun modernise(query: String): String = table.modernise(query)

    /**
     * An immutable parsed table: one `variant<TAB>modern<TAB>row` line per pair, `#`
     * comments and blank lines ignored, malformed lines skipped. Rows are `ha`
     * (single-char ハ行転呼), `au` (single-char アウ) and `eu` (one character to its
     * イ段+small-ょ counterpart). Wraps the Rust handle [inner]
     * (`uniffi.nav_graph_core.KanaSoundTable`); the parse rules and the two passes
     * live in `jpdict_core`.
     */
    class Table internal constructor(private val inner: RustKanaSoundTable) {
        /** Number of distinct pairs in this table. */
        val size: Int get() = inner.entryCount().toInt()

        fun ha(variant: Char): Char? = inner.ha(variant.code)?.let { Char(it) }

        fun au(variant: Char): Char? = inner.au(variant.code)?.let { Char(it) }

        fun eu(variant: Char): String? = inner.eu(variant.code)

        /**
         * Rewrite [query] through this table (the installed table when called via
         * [KanaSoundChanges.modernise]): the ハ行転呼 pass, then the vowel-change
         * pass, exactly as [KanaSoundChanges] documents them. `internal`: callers
         * go through [KanaSoundChanges.modernise], as before the swap.
         */
        internal fun modernise(query: String): String = inner.modernise(query)

        override fun toString(): String = "KanaSoundChanges.Table($size pairs)"

        companion object {
            /** The identity table: every lookup misses. */
            val EMPTY: Table = Table(RustKanaSoundTable.empty())

            fun parse(text: String): Table = Table(RustKanaSoundTable.parse(text))
        }
    }
}
