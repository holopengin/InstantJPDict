package com.holopengin.instantjpdict.util

import uniffi.nav_graph_core.kanaSizeBaseIndexOf
import uniffi.nav_graph_core.kanaSizeBaseOrder
import uniffi.nav_graph_core.kanaSizeBigFormOf
import uniffi.nav_graph_core.kanaSizeIsSmall
import uniffi.nav_graph_core.kanaSizeSmallToBig
import uniffi.nav_graph_core.kanaSizeWindow
import uniffi.nav_graph_core.kanaSizeWindowBytes

/**
 * Window encoder for the small/large kana size model (#44).
 *
 * The model decides, for one kana position, whether it should be the BIG form (つ) or the small
 * one (っ). Its input is the surrounding text only — the target character itself is *not* in the
 * window, and the pair identity travels through the base index instead. That is deliberate: if
 * the target were fed in, the model would mostly agree with whatever the OCR already emitted,
 * which is useless exactly where the OCR is wrong.
 *
 * Layout (40 bytes), exactly as the model's interface document specifies:
 *   cells 0..4  left context, leftmost first:  L5 L4 L3 L2 L1
 *   cells 5..9  right context, nearest first:  R1 R2 R3 R4 R5
 *   each cell = the character's UTF-8 bytes, left-aligned, zero-padded to 4
 *   an absent cell (past a boundary, or past the edge of the text) is four zero bytes
 *
 * **Context stops at 。 and newline** (the retrain's line-domain raster). The app recognises per
 * line, so there is no cross-line text to draw from; the `nb_*` artifacts were trained with this
 * clip, and the boundary character itself is not part of the window. The earlier `v2` artifact
 * used a doc-domain raster that crossed 。 — do not mix an encoder with an artifact trained for
 * the other. Verified byte-exact against the published vectors; see `KanaSizeEncoderTest`.
 *
 * Since the util-core swap this is a thin facade over the PC `jpdict_core` implementation
 * (`core/src/kana_size.rs`, exposed through `nav_graph_core`'s UniFFI surface), so the window
 * layout, the pair tables and the boundary clip have one source of truth. This object only adapts
 * types (`Char` ↔ `Int` code points) and keeps the API call sites already use. [WINDOW_BYTES] and
 * [BOUNDARY] are mirrors of the Rust consts, which UniFFI cannot export; the Rust side pins the
 * window length against `WINDOW_BYTES`, so the mirror cannot drift silently.
 */
object KanaSizeEncoder {

    /** Pair index. The BIG form is the base, hiragana then katakana. */
    val BASE_ORDER: List<Char> = kanaSizeBaseOrder().map { Char(it) }

    /** Small form -> big form. The model's table is the authority. */
    val SMALL_TO_BIG: Map<Char, Char> =
        kanaSizeSmallToBig().associate { Char(it.small) to Char(it.big) }

    /** The canonical big form for either member of a pair, or null if [ch] is not one. */
    fun bigFormOf(ch: Char): Char? = kanaSizeBigFormOf(ch.code)?.let { Char(it) }

    /**
     * Pair index for the position holding [ch], or null when [ch] is not part of a size pair.
     * Both っ and つ map to the same index: the pair is the class, the size is the decision.
     */
    fun baseIndexOf(ch: Char): Int? = kanaSizeBaseIndexOf(ch.code)?.toInt()

    /** Whether [ch] is the small member of a pair, i.e. what the model would be correcting. */
    fun isSmall(ch: Char): Boolean = kanaSizeIsSmall(ch.code)

    /**
     * The 40-byte window for the position at [index] in [text]. The character at [index] is
     * excluded; context stops at a [BOUNDARY] character and runs off the ends as zero padding,
     * which is what the app itself sees, since it recognises line by line.
     *
     * [index] counts Unicode scalar values, as the Rust window does; for the BMP text the model
     * sees that equals the Kotlin index. A supplementary-plane character before the target
     * shifts the window by one, matching the artifact author's Python reference (the pre-swap
     * Kotlin encoder indexed UTF-16 units and fed the model a bogus cell there).
     */
    fun window(text: CharSequence, index: Int): IntArray =
        kanaSizeWindow(text.toString(), index.toLong()).toIntArray()

    /**
     * The 40-byte window length, read from `jpdict_core` at first use (UniFFI cannot export
     * consts). `KanaSizeNcnn.WINDOW_BYTES` derives from this.
     */
    val WINDOW_BYTES: Int = kanaSizeWindowBytes().toInt()
}
