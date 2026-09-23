package com.holopengin.instantjpdict.util

import uniffi.nav_graph_core.japaneseCollapseEmphatic
import uniffi.nav_graph_core.japaneseFoldLookupVariants
import uniffi.nav_graph_core.japaneseKatakanaToHiragana
import uniffi.nav_graph_core.japaneseNormalize
import uniffi.nav_graph_core.japaneseSplitKanaList
import uniffi.nav_graph_core.japaneseVerticalPunctuation
import uniffi.nav_graph_core.japaneseVerticalPunctuationChar

/**
 * OCR-line text normalisation (#44, #55, #56, #63).
 *
 * Since the util-core swap this is a thin facade over the PC `jpdict_core`
 * implementation (`core/src/util/japanese.rs`, exposed through `nav_graph_core`'s
 * UniFFI surface), so the normalisation stages, the lookup-variant fold tables and
 * the vertical-punctuation rules have one source of truth. This object only adapts
 * types (`Char` ↔ `Int` code points) and keeps the API call sites already use.
 *
 * [MEASURED_VARIANT_FOLD] is the one table still written out here: upstream keeps it
 * private (a `lazy_static` with no `pub` accessor), so it cannot cross the boundary.
 * It is a mirror read **only by the drift-guard test**
 * `JapaneseUtilVariantFoldTest.measured_variant_fold_matches_the_committed_asset`;
 * production code never reads it, and [foldLookupVariants] is Rust's.
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
    internal val MEASURED_VARIANT_FOLD: Map<Char, String> = mapOf(
        '㕞' to "刷", '㘅' to "啣", '㝵' to "碍", '䖟' to "蝱",
        '䙝' to "褻", '䬒' to "颼", '䯻' to "髻", '䰗' to "鬮",
        '乾' to "干", '亻' to "人", '來' to "来", '俠' to "侠",
        '册' to "冊", '冩' to "写", '冫' to "氷", '准' to "準",
        '凉' to "涼", '凴' to "憑", '凾' to "函", '刋' to "刊",
        '剝' to "剥", '劒' to "劍", '勹' to "包", '匳' to "奩",
        '匵' to "櫝", '卭' to "卬", '厶' to "某", '后' to "後",
        '噐' to "器", '噓' to "嘘", '嚮' to "向", '囑' to "嘱",
        '囘' to "回", '國' to "国", '堭' to "隍", '壽' to "寿",
        '娬' to "嫵", '學' to "学", '寫' to "写", '寶' to "宝",
        '將' to "将", '尸' to "屍", '屆' to "届", '屬' to "属",
        '峽' to "峡", '巤' to "鬣", '帋' to "紙", '帒' to "袋",
        '并' to "併", '彌' to "弥", '悋' to "吝", '慙' to "慚",
        '懜' to "懵", '戀' to "恋", '挾' to "挟", '捬' to "撫",
        '摑' to "掴", '无' to "無", '晝' to "昼", '會' to "会",
        '朙' to "明", '栖' to "棲", '樓' to "楼", '樷' to "叢",
        '樸' to "朴", '欝' to "鬱", '氵' to "水", '涶' to "唾",
        '渊' to "淵", '潛' to "潜", '濵' to "濱", '灑' to "洒",
        '灣' to "湾", '烟' to "煙", '燈' to "灯", '犭' to "犬",
        '甎' to "磚", '甤' to "蕤", '畄' to "留", '畆' to "畝",
        '當' to "当", '癢' to "痒", '皃' to "貌", '眎' to "視",
        '瞹' to "曖", '碯' to "瑙", '礟' to "礮", '祿' to "禄",
        '禀' to "稟", '禦' to "御", '禪' to "禅", '禮' to "礼",
        '禱' to "祷", '秇' to "藝", '秌' to "秋", '穪' to "稱",
        '竆' to "窮", '竒' to "奇", '笋' to "筍", '簞' to "箪",
        '粮' to "糧", '糓' to "穀", '纎' to "纖", '缻' to "缶",
        '网' to "網", '羮' to "羹", '耻' to "恥", '耼' to "聃",
        '聲' to "声", '脉' to "脈", '膓' to "腸", '舊' to "旧",
        '艪' to "櫓", '苢' to "苡", '莖' to "茎", '萬' to "万",
        '著' to "着", '葢' to "蓋", '薑' to "姜", '蘯' to "蕩",
        '號' to "号", '蚦' to "蚺", '蜹' to "蚋", '蟬' to "蝉",
        '蟲' to "虫", '蠶' to "蚕", '襍' to "雜", '覔' to "覓",
        '觧' to "解", '註' to "注", '誐' to "哦", '賍' to "贓",
        '賷' to "齎", '軆' to "体", '輓' to "挽", '辶' to "辵",
        '迯' to "逃", '迹' to "跡", '遉' to "偵", '遙' to "遥",
        '釐' to "厘", '鍫' to "鍬", '鏁' to "鎖", '閙' to "鬧",
        '隂' to "陰", '隖' to "塢", '雙' to "双", '頣' to "頤",
        '颱' to "台", '飃' to "飄", '餘' to "余", '駞' to "駝",
        '髗' to "顱", '髩' to "鬢", '鬂' to "鬢", '鮧' to "鯷",
        '鵶' to "鴉", '鶽' to "隼", '鸎' to "鶯", '麄' to "粗",
        '麴' to "麹", '麸' to "麩", '點' to "点", '齅' to "嗅",
        '﨑' to "崎",

    )
}
