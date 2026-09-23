package com.holopengin.instantjpdict.util

import android.content.Context
import uniffi.nav_graph_core.KanjiVariantTable as RustKanjiVariantTable

/**
 * Kanji variant table (#44): the vendored Unihan `kSemanticVariant`/`kZVariant`
 * pairs, loaded from `variants/kanji_variants.txt` (source, licence and SHA-256
 * are in the `PROVENANCE.txt` beside it; regenerate with
 * `tools/build_kanji_variants.py`).
 *
 * **Direction:** `variant -> canonical`, where the *canonical* side is the one
 * the shipped recogniser's dictionary carries (`PP-OCRv6_small_ncnn/vocab.json`)
 * and the *variant* is the one it does not. A fold exists to map a form the
 * pipeline cannot handle onto one it can, so the target must be the form the
 * dictionary already keys on. Both-sides-known and both-sides-unknown pairs are
 * dropped by the generator; only BMP CJK pairs are in the file, because this API
 * is `Char`-based (one UTF-16 code unit).
 *
 * The lookup fold in [JapaneseUtil.foldLookupVariants] carries only the subset of
 * these pairs whose variant side was measured to occur in real Japanese text. This
 * loader exposes the whole table, in both directions, for callers that want it —
 * e.g. offering the obsolete forms of a character as alternatives.
 *
 * Since the util-core swap this is a thin facade over the PC `jpdict_core`
 * implementation (`core/src/util/kanji_variants.rs`, exposed through
 * `nav_graph_core`'s UniFFI surface), so the algorithm has one source of truth.
 * The parse rules, the direction rule and the both-directions indexes are
 * documented there; this class only adapts types (`Char` ↔ `Int` code points,
 * `Long` → `Int`) and keeps the API call sites already use.
 *
 * The table is plain committed text, parsed once. [install] is the entry point;
 * until it is called every lookup is the identity function (a missing entry is not
 * an error — an unknown character folds to itself).
 */
object KanjiVariants {
    const val ASSET_PATH = "variants/kanji_variants.txt"

    @Volatile
    private var table: Table = Table.EMPTY

    /** Parse the committed asset and install it. Idempotent. */
    fun install(context: Context) {
        install(parse(context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }))
    }

    /** Install an already-parsed table (tests, or a table from another source). */
    fun install(parsed: Table) {
        table = parsed
    }

    fun parse(text: String): Table = Table.parse(text)

    /** Number of distinct variants installed; 0 when nothing is loaded. */
    val entryCount: Int get() = table.size

    /**
     * The canonical form of [ch], or [ch] itself when the table has no entry for
     * it. Never throws, including before [install].
     */
    fun canonical(ch: Char): Char = table.canonical(ch)

    /** The variant forms that fold to [ch], sorted; empty when there are none. */
    fun obsoleteFormsOf(ch: Char): List<Char> = table.obsoleteFormsOf(ch)

    /**
     * Every canonical candidate the table lists for [ch], sorted; empty when there
     * are none. Only useful where Unihan gives a variant several candidates — the
     * fold in [JapaneseUtil.MEASURED_VARIANT_FOLD] resolves those on the corpus.
     */
    fun canonicalsOf(ch: Char): List<Char> = table.canonicalsOf(ch)

    /**
     * The currently installed table ([Table.EMPTY] until [install]). `internal` so a
     * later shim in this module that takes the table across the boundary (WP-10
     * `oov_suggestions`) can reach the installed handle; nothing public changes.
     */
    internal val installed: Table get() = table

    /**
     * An immutable parsed table: one `variant<TAB>canonical` pair per line, `#`
     * comments and blank lines ignored, malformed lines skipped. Every pair in the
     * file is kept — Unihan gives 59 of the variants more than one canonical
     * candidate, and [canonicalsOf] exposes them — while [canonical] answers with the
     * **first** line for that variant (the generator sorts by variant then canonical,
     * so first is the lowest codepoint: deterministic, but arbitrary, which is why the
     * measured fold in [JapaneseUtil.MEASURED_VARIANT_FOLD] makes its own choice).
     *
     * Wraps the Rust handle [inner] (`uniffi.nav_graph_core.KanjiVariantTable`); the
     * parse and index rules live in `jpdict_core`. [inner] is `internal` so a later
     * shim in this module (WP-10 `oov_suggestions`) can pass the table across the
     * boundary — the `variant_forms` closure is then built in Rust.
     */
    class Table private constructor(internal val inner: RustKanjiVariantTable) {
        /** Number of distinct variants in this table. */
        val size: Int get() = inner.entryCount().toInt()

        fun canonical(ch: Char): Char = Char(inner.canonical(ch.code))

        /**
         * Every canonical candidate Unihan lists for [ch], sorted; empty when the
         * variant is not in the table. 59 of the table's variants have more than one
         * — [canonical] then returns the first by codepoint, which is arbitrary, so a
         * caller that cares picks from this list instead.
         */
        fun canonicalsOf(ch: Char): List<Char> = inner.canonicalsOf(ch.code).map { Char(it) }

        fun obsoleteFormsOf(ch: Char): List<Char> = inner.obsoleteFormsOf(ch.code).map { Char(it) }

        override fun toString(): String = "KanjiVariants.Table($size variants)"

        companion object {
            /** The identity table: every lookup returns its input. */
            val EMPTY = Table(RustKanjiVariantTable.empty())

            fun parse(text: String): Table = Table(RustKanjiVariantTable.parse(text))
        }
    }
}
