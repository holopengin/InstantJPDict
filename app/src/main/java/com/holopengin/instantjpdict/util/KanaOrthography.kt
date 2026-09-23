package com.holopengin.instantjpdict.util

import android.content.Context
import uniffi.nav_graph_core.KanaOrthographyTable as RustKanaOrthographyTable

/**
 * Pre-reform kana orthography (#75): the lookup-query normaliser for text written
 * before the 1946 spelling reform (旧仮名遣い). The table is the committed
 * `variants/kana_variants.txt` rows (source, licence and SHA-256 are in the
 * `kana_variants.PROVENANCE.txt` beside it; regenerate with
 * `tools/build_kana_variants.py`).
 *
 * Since the util-core swap this is a thin facade over the PC `jpdict_core`
 * implementation (`core/src/util/japanese.rs`, exposed through `nav_graph_core`'s
 * UniFFI surface), so the table and the fold have one source of truth. PC inlines
 * the rows as consts, byte-identical to the asset, so [install] installs the
 * builtin table and the asset is no longer read at runtime; [parse] still parses
 * the asset's text, which is what the tests install. The context rules, the
 * direction rule and the parse rules are documented upstream; this class only
 * adapts types (`Char` ↔ `Int` code points, `Long` → `Int`) and keeps the API
 * call sites already use.
 *
 * **Query-side only, exactly like the kanji variant fold (#44).** The reader sees
 * the book's own orthography; only the string handed to the dictionary is
 * normalised, and the raw form is searched alongside it, so a fold can add a
 * reachable headword but can never take one away.
 *
 * **Why a size-only corrector could not do this.** [KanaSizeFix] flips a kana
 * between its small and large form on OCR output, and 76 of `deinflect.json`'s 569
 * rules mention small kana — but none maps a size. On pre-reform text the change
 * needed is never only a size: `いつしよ` -> `いっしょ` needs `つ`->`っ` *and*
 * `よ`->`ょ`; `しゆつぱつ` -> `しゅっぱつ` needs `つ`->`っ` *and* `ゆ`->`ゅ`. A model
 * that flips the subset it is confident about leaves a hybrid that matches neither
 * the book nor the dictionary. This pass rewrites the whole query onto the modern
 * form instead, which is what a reader of old text and the imported dictionary
 * both want.
 *
 * **Direction and source.** The table's pairs come from JMdict's own kana
 * cross-references — readings of one entry that differ at a single position on a
 * historical-kana row (`かつかざん`/`かっかざん`, `あはれ`/`あわれ`,
 * `あづま`/`あずま`) — and the direction is the row rule `variant -> modern`. See
 * `tools/build_kana_variants.py` for the extraction and the per-pair citation in
 * the PROVENANCE file.
 *
 * `tools/kana_sound_changes_bench.py` re-implements this normaliser (and
 * [KanaSoundChanges]) in Python for the #81 measurements; the two must move
 * together. Last compared 2026-09-15 (#86/C4).
 *
 * **What is deliberately NOT folded.** Measurement, not taste (2,000-line Aozora
 * bench slice, 350 lines of 旧仮名; counts in the #75 commit message):
 *
 *  - `を`/`ヲ` -> `お`/`オ`: the ordinary modern accusative particle. JMdict
 *    attests the alternation only inside a few lexical items (`をことてん`,
 *    `みやこをどり`); folding it row-wide would rewrite correct modern queries
 *    (`本を読む` -> `本お読む`) and break the lookups it is meant to help.
 *  - the 小書き row (`あ`->`ぁ` … `わ`->`ゎ`, and katakana): not in the table at
 *    all. Its variant side is an ordinary modern kana, and shipping it changed
 *    1,220 positions on the modern slice — 977 of them clipping a reachable
 *    headword — while repairing *nothing* extra on the legacy one (118 either
 *    way). The alternation is gairaigo spelling, not historical kana.
 *  - `づ` after `つ`/`ち` and `ぢ` after `つ`/`ち`: 連濁, where `つづく`/`ちぢむ`
 *    are the modern forms themselves. Folding those would break correct lookups.
 *  - `つ` before a か行 kana (`つくえ`, `つかう`): measured to clip more modern
 *    words than it repaired, so the 促音 rule fires only before た行/さ行/ぱ行.
 */
object KanaOrthography {
    const val ASSET_PATH = "variants/kana_variants.txt"

    @Volatile
    private var table: Table = Table.EMPTY

    /**
     * Install the builtin table (the committed asset's rows, inlined in PC).
     * Idempotent. [context] is kept for the call-site signature — the asset is
     * no longer read here, so it is unused.
     */
    fun install(context: Context) {
        install(Table(RustKanaOrthographyTable.builtin()))
    }

    /** Install an already-parsed table (tests, or a table from another source). */
    fun install(parsed: Table) {
        table = parsed
    }

    fun parse(text: String): Table = Table.parse(text)

    /** Number of distinct variants installed; 0 when nothing is loaded. */
    val entryCount: Int get() = table.size

    /** The modern form of [variant], or [variant] itself when the table has none. */
    fun canonical(variant: Char): Char = table.canonical(variant)

    /**
     * Rewrite a lookup query from pre-reform orthography onto the modern form the
     * imported dictionary keys on. Never throws and never returns null; the
     * identity when nothing in the query is a variant. Query-side only — callers
     * keep displaying the raw text.
     *
     * Deterministic: one pass, no lookahead beyond one character, no dependence on
     * how much of the line is one era. (A previous page-ratio gate here made a
     * small scroll change the result; a lookup must not depend on that.)
     *
     * `を`/`ヲ` are left alone, and the 小書き row is not applied — see the class
     * doc for the measurement behind both.
     */
    fun modernise(query: String): String = table.modernise(query)

    /**
     * An immutable parsed table: one `variant<TAB>canonical` pair per line, `#`
     * comments and blank lines ignored, malformed lines skipped — the same shape as
     * `variants/kanji_variants.txt`, so the two read alike. Wraps the Rust handle
     * [inner] (`uniffi.nav_graph_core.KanaOrthographyTable`); the parse and
     * context rules live in `jpdict_core`.
     */
    class Table internal constructor(private val inner: RustKanaOrthographyTable) {
        /** Number of distinct variants in this table. */
        val size: Int get() = inner.entryCount().toInt()

        fun canonical(variant: Char): Char = Char(inner.canonical(variant.code))

        /**
         * Rewrite [query] through this table (the installed table when called via
         * [KanaOrthography.modernise]). The context rules are the same ones
         * [KanaOrthography] documents: ワ行 unconditional, だ行 unless preceded by
         * `つ`/`ち`, 促音 only before た行/さ行/ぱ行, 拗音 only after an i-column
         * kana. `internal`: callers go through [KanaOrthography.modernise], as
         * before the swap.
         */
        internal fun modernise(query: String): String = inner.modernise(query)

        override fun toString(): String = "KanaOrthography.Table($size variants)"

        companion object {
            /** The identity table: every lookup returns its input. */
            val EMPTY: Table = Table(RustKanaOrthographyTable.empty())

            fun parse(text: String): Table = Table(RustKanaOrthographyTable.parse(text))
        }
    }
}
