package com.holopengin.instantjpdict

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.nav_graph_core.CompactCtcDecodeResult
import uniffi.nav_graph_core.CtcDecode
import uniffi.nav_graph_core.charBoxesPeakOffset

/**
 * On-device (Pixel 7a, arm64 release `libnav_graph_core.so`) before/after gate
 * for the Wave-3 CTC single-source swap, packed top-K lane (the production
 * path). Mirrors `CtcDecodeJvmBenchmarkTest` but runs the Rust core under ART
 * and JNA on real hardware, which is where the FFI marshalling cost lands.
 *
 * Run:
 *   ./gradlew :app:connectedBenchmarkAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.holopengin.instantjpdict.CtcDecodeDeviceBenchmarkTest
 *
 * The project sets `testBuildType = "benchmark"` (initWith(release), minify
 * off, isDebuggable = false), so this runs in a non-debuggable process where
 * ART/JIT timing is production-like. The Rust `.so` is the same arm64 release
 * build that ships, so the Rust lane is exact and the Kotlin lane is fair.
 *
 * The "after" lane is the *compact* decode plus the host's own re-expansion of
 * the flat cell list ([materialize]) — the only result shape the boundary
 * exports now, and the one production uses. The nested result is no longer a
 * binding, so the parity gate here is legacy-Kotlin-vs-compact rather than
 * compact-vs-nested; the compact-vs-nested equivalence is pinned on the Rust
 * side (`ctc_decode::tests::compact_rows_expand_back_to_the_nested_result`).
 */
@RunWith(AndroidJUnit4::class)
class CtcDecodeDeviceBenchmarkTest {
    private companion object {
        const val TAG = "CtcDecodeDevice"
        const val TOP_K = 15
    }

    private data class Result(
        val text: String,
        val alternatives: List<List<Pair<Char, Float>>>,
        val charCols: FloatArray,
        val seqLenTotal: Int,
        val rawAlternatives: List<List<Pair<Char, Float>>>,
    ) {
        fun checksum(): Int =
            text.hashCode() * 31 + alternatives.size * 17 + charCols.size * 13 +
                rawAlternatives.size * 7
    }

    private data class Fixture(
        val vocab: List<String>,
        val remap: List<Int>,
        val packedModel: FloatArray,
        val packedActual: FloatArray,
    )

    private fun fixture(): Fixture {
        val classes = 18_710
        val modelSeqLen = 64
        val actualSeqLen = 60
        val vocab = List(18_708) { i -> (0x3000 + i % 0x5000).toChar().toString() }
        val remap = List(classes) { it }
        val packedModel = FloatArray(modelSeqLen * TOP_K * 2)
        for (t in 0 until modelSeqLen) {
            for (k in 0 until TOP_K) {
                // Descending unique scores per row, lowest id wins ties.
                val c = (k * 131 + t * 7) % classes
                packedModel[(t * TOP_K + k) * 2] = c.toFloat()
                packedModel[(t * TOP_K + k) * 2 + 1] = (2_000_000 - t * 100 - k).toFloat()
            }
        }
        return Fixture(vocab, remap, packedModel, packedModel.copyOf(actualSeqLen * TOP_K * 2))
    }

    private fun decodeChar(vocab: List<String>, classIdx: Int): Char = when {
        classIdx == 18_709 -> ' '
        classIdx == 18_708 -> '\u3000'
        classIdx in 1..18_708 -> vocab.getOrNull(classIdx - 1)?.firstOrNull() ?: '\uFFFD'
        else -> '\u3000'
    }

    private fun remapClass(remap: List<Int>, prunedIdx: Int): Int =
        remap.getOrElse(prunedIdx) { prunedIdx }

    private fun peakOffset(v0: Float, v1: Float, v2: Float): Float =
        charBoxesPeakOffset(v0, v1, v2)

    /** Exact former Kotlin top-K decoder. */
    private fun legacyTopK(
        vocab: List<String>,
        remap: List<Int>,
        topPruned: Array<IntArray>,
        topChars: List<List<Pair<Char, Float>>>,
        seqLen: Int,
    ): Result {
        val text = StringBuilder()
        val alternatives = mutableListOf<List<Pair<Char, Float>>>()
        val charCols = mutableListOf<Float>()
        var previousClass = 0

        fun wval(row: Int, classIdx: Int): Float? {
            val ids = topPruned.getOrNull(row) ?: return null
            val cells = topChars.getOrNull(row) ?: return null
            val k = ids.indexOf(classIdx)
            return if (k < 0) null else cells.getOrNull(k)?.second
        }

        for (t in 0 until seqLen) {
            val ids = topPruned.getOrNull(t) ?: continue
            val indexed = topChars.getOrNull(t)?.toMutableList() ?: continue
            if (ids.isEmpty() || indexed.isEmpty()) continue
            val winner = ids[0]
            val classIdx = remapClass(remap, winner)
            val left = if (t > 0) wval(t - 1, winner) else null
            val right = wval(t + 1, winner)
            val column = if (left != null && right != null) {
                t + peakOffset(left, indexed[0].second, right)
            } else {
                t.toFloat()
            }
            when {
                classIdx == 0 -> previousClass = 0
                classIdx == 18_709 -> {
                    text.append(' ')
                    previousClass = classIdx
                    charCols.add(column)
                    alternatives.add(indexed)
                }
                classIdx == previousClass -> Unit
                else -> {
                    val ch = decodeChar(vocab, classIdx)
                    if (ch != '\uFFFD') {
                        text.append(ch)
                        charCols.add(column)
                        alternatives.add(indexed)
                        previousClass = classIdx
                    }
                }
            }
        }
        return Result(text.toString(), alternatives, charCols.toFloatArray(), seqLen, topChars)
    }

    private fun legacyPackedCallSite(f: Fixture): Result {
        val seqLen = 60
        val topPruned = Array(seqLen) { t ->
            IntArray(TOP_K) { k -> f.packedModel[(t * TOP_K + k) * 2].toInt() }
        }
        val topChars = Array(seqLen) { t ->
            List(TOP_K) { k ->
                val classIdx = remapClass(f.remap, topPruned[t][k])
                decodeChar(f.vocab, classIdx) to
                    f.packedModel[(t * TOP_K + k) * 2 + 1]
            }
        }
        return legacyTopK(f.vocab, f.remap, topPruned, topChars.toList(), seqLen)
    }

    /**
     * The compact result as this benchmark's nested [Result], by the indexing the
     * recognition path does: slice the flat cell list at `rawRows`, then take the
     * per-character alternatives *by index* into those rows.
     *
     * Kept here rather than shared with production so the benchmark measures the
     * decode plus the host's own re-expansion — the work the app actually does.
     */
    private fun CompactCtcDecodeResult.materialize(): Result {
        val rows = List(rawRows.size - 1) { i ->
            val from = rawRows[i].toInt()
            val to = rawRows[i + 1].toInt()
            List(to - from) { k -> Char(rawAlternatives[from + k].ch) to rawAlternatives[from + k].score }
        }
        return Result(
            text,
            altRows.map { rows[it.toInt()] },
            charCols.toFloatArray(),
            seqLenTotal.toInt(),
            rows,
        )
    }

    private fun rustPacked(decoder: CtcDecode, f: Fixture): Result =
        decoder.decodeTopKCompact(f.packedActual.asList(), 60L, false).materialize()

    private fun assertSame(expected: Result, actual: Result) {
        assertEquals(expected.text, actual.text)
        assertEquals(expected.alternatives, actual.alternatives)
        assertArrayEquals(expected.charCols, actual.charCols, 0f)
        assertEquals(expected.seqLenTotal, actual.seqLenTotal)
        assertEquals(expected.rawAlternatives, actual.rawAlternatives)
    }

    private fun percentile(values: LongArray, percentile: Int): Double {
        val sorted = values.sortedArray()
        val index = ((sorted.size - 1) * percentile / 100.0).toInt()
        return sorted[index] / 1_000.0
    }

    private fun measurePair(
        samples: Int,
        warmups: Int,
        before: () -> Result,
        after: () -> Result,
    ): Pair<LongArray, LongArray> {
        repeat(warmups) {
            before().checksum()
            after().checksum()
        }
        val beforeNs = LongArray(samples)
        val afterNs = LongArray(samples)
        var blackHole = 0
        fun timed(block: () -> Result): Long {
            val start = System.nanoTime()
            val result = block()
            val elapsed = System.nanoTime() - start
            blackHole = blackHole xor result.checksum()
            return elapsed
        }
        repeat(samples) { i ->
            if (i and 1 == 0) {
                beforeNs[i] = timed(before)
                afterNs[i] = timed(after)
            } else {
                afterNs[i] = timed(after)
                beforeNs[i] = timed(before)
            }
        }
        check(blackHole != Int.MIN_VALUE)
        return beforeNs to afterNs
    }

    @Test
    fun packedTopK_matchesLegacy_andBenchmarksOnDevice() {
        val f = fixture()
        CtcDecode(f.vocab, f.remap).use { decoder ->
            val before = legacyPackedCallSite(f)
            val after = rustPacked(decoder, f)
            assertSame(before, after)

            val (beforeNs, afterNs) = measurePair(
                samples = 300,
                warmups = 100,
                before = { legacyPackedCallSite(f) },
                after = { rustPacked(decoder, f) },
            )
            val b50 = percentile(beforeNs, 50)
            val b95 = percentile(beforeNs, 95)
            val a50 = percentile(afterNs, 50)
            val a95 = percentile(afterNs, 95)
            Log.i(
                TAG,
                "CTC_DEVICE_BENCH lane=packed-topk seq=60 classes=18710 samples=300 " +
                    "kotlinP50Us=$b50 kotlinP95Us=$b95 rustP50Us=$a50 rustP95Us=$a95 " +
                    "deltaP50Us=${a50 - b50}",
            )
        }
    }
}
