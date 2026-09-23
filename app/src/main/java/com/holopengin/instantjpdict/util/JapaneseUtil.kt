package com.holopengin.instantjpdict.util

import uniffi.nav_graph_core.japaneseCollapseEmphatic
import uniffi.nav_graph_core.japaneseFoldLookupVariants
import uniffi.nav_graph_core.japaneseKatakanaToHiragana
import uniffi.nav_graph_core.japaneseMeasuredVariantFold
import uniffi.nav_graph_core.japaneseNormalize
import uniffi.nav_graph_core.japaneseSplitKanaList
import uniffi.nav_graph_core.japaneseVerticalPunctuation
import uniffi.nav_graph_core.japaneseVerticalPunctuationChar
import uniffi.nav_graph_core.japaneseVerticalPunctuationChars

/**
 * OCR-line text normalisation (#44, #55, #56, #63).
 *
 * Since the util-core swap this is a thin facade over the PC `jpdict_core`
 * implementation (`core/src/util/japanese.rs`, exposed through `nav_graph_core`'s
 * UniFFI surface), so the normalisation stages, the lookup-variant fold tables and
 * the vertical-punctuation rules have one source of truth. This object only adapts
 * types (`Char` ↔ `Int` code points) and keeps the API call sites already use.
 *
 * [MEASURED_VARIANT_FOLD] is no longer a Kotlin table: it is rebuilt from the
 * exported `jpdict_core` data (`japaneseMeasuredVariantFold`) and exists for the
 * drift-guard test `JapaneseUtilVariantFoldTest.measured_variant_fold_matches_the_
 * committed_asset`, which checks it against the committed asset. Production code
 * never reads it, and [foldLookupVariants] is Rust's.
 */
object JapaneseUtil {
    /**
     * Fold an OCR line to the form dictionary lookup expects: width/combining
     * normalisation plus [#44] lookup-variant folds (iteration kana, obsolete
     * kana, Roman numerals, the Chinese-only forms the recogniser emits).
     *
     * Query-side only. [OcrOverlayStateController] calls this to build search
     * keys from the raw line prefix, so a fold may change the *length* of the
     * key without affecting what is displayed or which prefix the match
     * corresponds to — the caller keeps using the raw prefix length.
     */
    fun normalize(text: String): String = japaneseNormalize(text)

    /** Vertical-line punctuation (#56, #63): PP-OCR emits ASCII `?` where JP
     * text wants fullwidth `？`, and horizontal `…`/`‥` where vertical text
     * wants the vertical presentation forms `︙`/`︰`. ASCII `?` and `…` have
     * no `vert` alternate and mis-center in the vertical em box; `？`/`︙`/`︰`
     * center. Horizontal lines keep the originals. Lookup-safe: [normalize]
     * folds `？` back to `?` and `︙`/`︰` back to `…`/`‥`, so dictionary
     * search is unaffected. ASCII period runs (`...`) are deliberately left
     * untouched — an N:1 fold would break char-box/alternative alignment. */
    fun verticalPunctuation(text: String): String = japaneseVerticalPunctuation(text)

    /**
     * The single-character map behind [verticalPunctuation]. A Kotlin `Char`
     * holding a lone surrogate degrades to U+FFFD at the boundary; real call
     * sites only pass characters from recognised text.
     */
    fun verticalPunctuationChar(c: Char): Char = Char(japaneseVerticalPunctuationChar(c.code))

    /**
     * Batched [verticalPunctuationChar] over a line's alternatives: one FFI call
     * per list instead of one per character. Raw-alternative lists hold every CTC
     * timestep × top-K, so the per-character form crossed the boundary thousands
     * of times per line and made recognition visibly slower.
     */
    fun verticalPunctuationAlternatives(
        alternatives: List<List<Pair<Char, Float>>>,
    ): List<List<Pair<Char, Float>>> {
        var size = 0
        for (alts in alternatives) size += alts.size
        if (size == 0) return alternatives
        val flat = ArrayList<Int>(size)
        for (alts in alternatives) for ((c, _) in alts) flat.add(c.code)
        val mapped = japaneseVerticalPunctuationChars(flat)
        var k = 0
        return alternatives.map { alts ->
            alts.map { (c, s) ->
                val n = Char(mapped[k++])
                if (n == c) c to s else n to s
            }
        }
    }

    /**
     * Split a KANJIDIC kana list ("きみ -ぎみ", "クン キン") into readings.
     * Entries are whitespace-separated; a leading ASCII hyphen marks an
     * okurigana-less stem ("-ぎみ" → "ぎみ") and is stripped. Shared by the
     * #69 classifier and the kanji-branch renderer so both agree.
     */
    fun splitKanaList(raw: String): List<String> = japaneseSplitKanaList(raw)

    /**
     * Fold the variant characters of the lookup-variant table and expand the
     * iteration marks `ゝ`/`ゞ`/`ヽ`/`ヾ`, which repeat the preceding kana
     * (`こゝろ` → `こころ`, `たゞ` → `ただ`) — 139,270 occurrences in Aozora, all
     * emittable, and a query containing one of them matches nothing in a modern
     * dictionary.
     *
     * `ゞ`/`ヾ` voice the repeat when the preceding kana has a voiced form and
     * fall back to a plain repeat otherwise (`まゞ` → `まま`, 537 real
     * occurrences), which is also what an already-voiced kana needs
     * (`がゞ` → `がが`). A mark whose preceding character is not kana of the
     * matching script (line-initial, after a kanji or punctuation) is left as-is
     * rather than folded into a guess: 1,067 of 139,270 real occurrences, so
     * 99.23% fold, measured across the Aozora corpus.
     *
     * Delegates to `jpdict_core::util::japanese::fold_lookup_variants`; the
     * tables and their direction/frequency guards live there.
     */
    fun foldLookupVariants(text: String): String = japaneseFoldLookupVariants(text)

    /**
     * Convert katakana to hiragana, resolving a prolonged sound mark `ー`
     * against the character it follows (`カード` → `かあど`). Delegates to
     * `jpdict_core::util::japanese::katakana_to_hiragana`.
     */
    fun katakanaToHiragana(text: String): String = japaneseKatakanaToHiragana(text)

    /**
     * Collapse consecutive emphatic characters (`っ`, `ッ`, `ー`, `～`), which
     * the recogniser can repeat (`すごーーい` → `すごーい`). Delegates to
     * `jpdict_core::util::japanese::collapse_emphatic`.
     */
    fun collapseEmphatic(text: String): String = japaneseCollapseEmphatic(text)

    /**
     * Unihan variant forms that real Japanese text uses, folded onto the form the
     * shipped dictionary carries (#44). Same direction rule as [KanjiVariants] and
     * `tools/build_kanji_variants.py`: canonical = the side present in
     * `PP-OCRv6_small_ncnn/vocab.json`, variant = the side that is not.
     *
     * **Mirror, not implementation.** The fold itself runs in Rust
     * (`jpdict_core::util::japanese`); this literal survives only because the
     * upstream table is private and
     * `JapaneseUtilVariantFoldTest.measured_variant_fold_matches_the_committed_asset`
     * reads it against the shared asset. Keep it in step by hand: the JVM guard
     * pins this copy, the upstream crate test pins Rust's, and both compare to the
     * same `variants/kanji_variants.txt`.
     *
     * Unlike the curated entries of the Rust `LOOKUP_VARIANT_MAP`, **these
     * characters have no class in the shipped head at all**, so an OCR line can
     * never contain one. They are not dead entries, they are the other direction:
     * lookup also runs over text that did not come from the model (a character
     * typed into a manual override, dictionary-side text), and there the obsolete
     * form is exactly what needs normalising. On model *output* a variant the head
     * cannot emit still fails as a deletion and needs the proposal layer, not a
     * fold.
     *
     * Subset rule — measured, not guessed: a pair ships only if its variant side
     * actually occurs in real text. 112 of the table's 593 pairs qualified, measured
     * over the whole Aozora Bunko corpus as streamed from the HF clean mirror
     * (16,950 works, 230,196,565 characters): 囘 1,493; 欝 1,431; 壜 1,322; 劒 387;
     * 慙 357; 厶 305; 噐 74; 齅 30; the rest tail off to a single occurrence.
     *
     * **Frequency guard — a fold REPLACES the lookup key, so the direction has to be
     * earned.** That rule alone is not enough, because Unihan's `kSemanticVariant` is
     * loose: it also lists pairs whose "canonical" side is the rarer form, and folding
     * those would rewrite a query that used to resolve into one that does not — not
     * merely useless, actively harmful. So a pair also ships only when its canonical
     * side occurs **at least as often as its variant** in that same corpus: 92 of the
     * 112 qualify, and the 20 that do not are dropped. Worst offenders, with counts —
     * `壜`→`罈` (1,322 vs **0**), `躱`→`躲` (194 vs 0), `輙`→`輒` (74 vs 44),
     * `韈`→`襪` (51 vs 25), `覊`→`羈` (196 vs 183), `鬭`→`鬥` (23 vs 0). Dropping them
     * is not a re-direction of the fold: `壜` is the everyday form for "bottle" and is
     * a dictionary headword, so it must resolve to itself.
     *
     * Where Unihan offers several canonical candidates for one variant (15 of the
     * measured set) the fold takes the form that dominates that same corpus rather than an
     * arbitrary first: 葢→蓋 (蓋 6,811 vs 盖 92), 悋→吝 (760 vs 恡 0), 冫→氷 (10,435
     * vs 冰 88), 秇→藝 (7,036), 穪→稱 (2,032), 﨑→崎 (15,233 vs 埼 431). Every pair
     * here is also present in `variants/kanji_variants.txt` with the same direction where
     * the table keeps the pair — 9 of the measured set are absent, because the table drops
     * both-sides-emittable pairs (folding gains nothing for *its* purpose) while the fold
     * still rescues model output — and no canonical is itself a key, so the fold stays
     * idempotent. All pairs are single-character, so unlike the Roman numerals below they
     * never change query length.
     *
     * The 72 pairs added for the `摑` report come from the table's second source: JMdict's
     * out-dated/rarely-used kanji tags intersected with Unihan's simplified/traditional
     * axis, direction taken from JMdict. The previous rule could not produce them at all,
     * because both forms are emittable (`掴`/`摑`, `国`/`國`) and it drops both-known pairs.
     * Regenerate both halves with tools/build_kanji_variants.py and
     * tools/build_variant_fold.py.
     */
    internal val MEASURED_VARIANT_FOLD: Map<Char, String> by lazy {
        japaneseMeasuredVariantFold().associate { Char(it.variant) to it.canonical }
    }
}
