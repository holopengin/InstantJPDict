package com.holopengin.instantjpdict.util

import android.content.Context

/**
 * Historical kana sound changes (#81): the lookup-query normaliser for the class of
 * 旧仮名遣い that #75's variant table structurally cannot reach, loaded from
 * `variants/kana_sound_changes.txt` (source, licence and SHA-256 are in the
 * `kana_sound_changes.PROVENANCE.txt` beside it; regenerate with
 * `tools/build_kana_sound_changes.py`).
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
 */
object KanaSoundChanges {
    const val ASSET_PATH = "variants/kana_sound_changes.txt"

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

    /** Number of distinct pairs installed; 0 when nothing is loaded. */
    val entryCount: Int get() = table.size

    /**
     * Rewrite an already-modernised lookup query (see [KanaOrthography.modernise])
     * from pre-reform sound changes onto the modern form the imported dictionary
     * keys on. Never throws and never returns null; the identity when nothing in the
     * query is a rule's input. Query-side only — callers keep displaying the raw
     * text, and the raw and #75-normalised forms are still searched alongside this.
     */
    fun modernise(query: String): String {
        if (query.isEmpty()) return query
        return vowelChanges(haGyouten(query))
    }

    /** Pass 1: 語中・語尾のハ行 -> ワ行, under each character's ending condition. */
    private fun haGyouten(query: String): String {
        val t = table
        val sb = StringBuilder(query.length)
        for (i in query.indices) {
            val c = query[i]
            val mapped = t.ha(c)
            if (mapped == null) {
                sb.append(c)
                continue
            }
            val prev = if (i > 0) query[i - 1] else null
            val next = if (i + 1 < query.length) query[i + 1] else null
            sb.append(if (applies(c, prev, next)) mapped else c)
        }
        return sb.toString()
    }

    /**
     * Pass 2: 母音の変化 before `う` — `au` (アウ->オウ) replaces one character, `eu`
     * (エウ->ヨウ) replaces the エ段 character with its イ段 counterpart + small ょ
     * and keeps the `う` (`け` + `う` -> `きょ` + `う`).
     */
    private fun vowelChanges(query: String): String {
        val t = table
        val sb = StringBuilder(query.length)
        var i = 0
        while (i < query.length) {
            val c = query[i]
            val next = if (i + 1 < query.length) query[i + 1] else null
            if (next == 'う') {
                val au = t.au(c)
                if (au != null) {
                    sb.append(au).append('う')
                    i += 2
                    continue
                }
                val eu = t.eu(c)
                if (eu != null) {
                    sb.append(eu).append('う')
                    i += 2
                    continue
                }
            }
            sb.append(c)
            i += 1
        }
        return sb.toString()
    }

    /**
     * Whether a `ha`-row character folds in this context. `ほ` has no branch: it is
     * withheld (see the class doc), so even a table that carried the pair would leave
     * it alone until a condition is added and pinned in tests.
     */
    private fun applies(variant: Char, prev: Char?, next: Char?): Boolean = when (variant) {
        // ハ行四段 終止形・連体形: word-final, or before a particle. Not after ウ:
        // kana-written 夫婦/毛布 end in ふ and are not verb endings.
        'ふ' -> prev != null && prev != 'う' &&
            (next == null || !isKana(next) || next in PARTICLES)
        // ハ行四段 連用形: 思ひつ -> 思いつ; 思ひながら -> 思いながら.
        'ひ' -> next != null && next in HI_SUFFIX
        // ハ行四段 仮定形・已然形 and the 下二段 連用形: 数へて, 添へた, 言へば.
        'へ' -> next != null && next in HE_SUFFIX
        // 未然形 + ず/ぬ/む/ば: 言はず -> 言わず, 思はば -> 思わば. The particle は
        // is not in the suffix set, and a word-initial は is excluded above.
        'は' -> prev != null && next != null && next in HA_SUFFIX
        else -> false
    }

    private fun isKana(c: Char): Boolean =
        c in '\u3041'..'\u3096' || c in '\u30A1'..'\u30F6'

    /**
     * What can follow a 終止形 before it folds to `う`: the sentence particles, plus
     * the leading character of the common multi-character particles (から, まで, より,
     * こそ, しか). A 終止形 before any other kana is left alone on the full prefix;
     * the lookup also searches the shorter prefix that ends at the `ふ`, which is the
     * 終止形 itself.
     */
    private val PARTICLES = "はがのにをともやかぞなよねだでどばへこそしかまでより".toCharSet()

    private val HI_SUFFIX = "てつな".toCharSet()
    private val HE_SUFFIX = "したてばどきけるれりま".toCharSet()
    private val HA_SUFFIX = "ずぬむば".toCharSet()

    private fun String.toCharSet(): Set<Char> = toSet()

    /**
     * An immutable parsed table: one `variant<TAB>modern<TAB>row` line per pair, `#`
     * comments and blank lines ignored, malformed lines skipped. Rows are `ha`
     * (single-char ハ行転呼), `au` (single-char アウ) and `eu` (one character to its
     * イ段+small-ょ counterpart).
     */
    class Table internal constructor(
        private val ha: Map<Char, Char>,
        private val au: Map<Char, Char>,
        private val eu: Map<Char, String>,
    ) {
        /** Number of distinct pairs in this table. */
        val size: Int get() = ha.size + au.size + eu.size

        fun ha(variant: Char): Char? = ha[variant]

        fun au(variant: Char): Char? = au[variant]

        fun eu(variant: Char): String? = eu[variant]

        override fun toString(): String = "KanaSoundChanges.Table($size pairs)"

        companion object {
            val EMPTY = Table(emptyMap(), emptyMap(), emptyMap())

            fun parse(text: String): Table {
                val ha = LinkedHashMap<Char, Char>()
                val au = LinkedHashMap<Char, Char>()
                val eu = LinkedHashMap<Char, String>()
                for (raw in text.lineSequence()) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val parts = line.split('\t')
                    if (parts.size != 3) continue
                    val variant = parts[0].singleOrNull() ?: continue
                    val modern = parts[1]
                    if (modern.isEmpty() || modern == variant.toString()) continue
                    when (parts[2]) {
                        "ha" -> if (modern.length == 1) ha.putIfAbsent(variant, modern[0])
                        "au" -> if (modern.length == 1) au.putIfAbsent(variant, modern[0])
                        "eu" -> if (modern.length in 1..2) eu.putIfAbsent(variant, modern)
                    }
                }
                return Table(ha, au, eu)
            }
        }
    }
}
