package com.holopengin.instantjpdict.util

import uniffi.nav_graph_core.japaneseAlignFurigana

/**
 * Aligns a dictionary reading against its headword so ruby is shown only over
 * kanji spans, with okurigana/kana rendered as plain base text (#55).
 *
 * Since the util-core swap this is a thin facade over the PC `jpdict_core`
 * implementation (`core/src/util/japanese.rs::align_furigana`, exposed through
 * `nav_graph_core`'s UniFFI surface), so the alignment rules have one source of
 * truth. This object only maps the generated `RubySegment` record onto
 * [Segment], which keeps the API call sites already use.
 *
 * Returns null when the reading cannot be unambiguously aligned — callers must
 * fall back to full-reading ruby rendering in that case.
 *
 * Examples:
 * - 食べる / たべる → [食:た][べる:—]
 * - 大きい / おおきい → [大:おお][きい:—]
 * - 申し込む / もうしこむ → [申:もう][し:—][込:こ][む:—]
 * - 今日 / きょう → [今日:きょう] (no kana anchor: whole ruby, as before)
 */
object FuriganaAligner {
    /** One render run: [base] surface text with optional [ruby] above it (null = plain). */
    data class Segment(val base: String, val ruby: String?)

    fun align(term: String, reading: String): List<Segment>? =
        japaneseAlignFurigana(term, reading)?.map { Segment(it.base, it.ruby) }
}
