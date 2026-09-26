package com.holopengin.instantjpdict

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.nav_graph_core.CompactCtcDecodeResult
import uniffi.nav_graph_core.CtcDecode
import uniffi.nav_graph_core.charBoxesPeakOffset

/**
 * Host-JVM before/after gate for the Wave-3 CTC single-source swap.
 *
 * Timing caveat: `buildNavGraphCoreHost` builds the host `.so` in the **debug**
 * profile, which inflates the Rust lane (~3-4x) relative to the Kotlin lane
 * running on a JIT. The parity assertions are valid as-is; for meaningful
 * timings stage a release host build first, e.g.
 *   (cd nav_graph_core && cargo build --release)
 *   cp nav_graph_core/target/release/libnav_graph_core.so \
 *      nav_graph_core/target/debug/libnav_graph_core.so
 * The authoritative performance gate is `CtcDecodeDeviceBenchmarkTest`.
 *
 * This is deliberately synthetic but production-shaped: 60 useful timesteps,
 * four padded model timesteps, 18,710 classes, and the native packed top-15
 * layout. The "before" lane includes the old call-site staging (topPruned +
 * rawAlts) as well as decoding. The full lane also includes Kotlin candidate
 * packing for "after", because omitting that work would measure a crossing the
 * app cannot actually make. Fixture construction and decoder construction are
 * outside every timed region.
 *
 * The "after" lane is the *compact* decode plus the host's own re-expansion of
 * the flat cell list ([materialize]) — the only result shape the boundary
 * exports now, and the one production uses. The nested result is no longer a
 * binding, so the parity gate here is legacy-Kotlin-vs-compact rather than
 * compact-vs-nested; the compact-vs-nested equivalence is pinned on the Rust
 * side (`ctc_decode::tests::compact_rows_expand_back_to_the_nested_result`,
 * against `jpdict_core::ctc_decode::ctc_decode_topk` reached directly).
 */
class CtcDecodeJvmBenchmarkTest {
    private companion object {
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
        val cropLogits: Array<FloatArray>,
        val packedModel: FloatArray,
        val packedActual: FloatArray,
    )

    private fun fixture(): Fixture {
        val classes = 18_710
        val modelSeqLen = 64
        val actualSeqLen = 60
        val vocab = List(18_708) { i -> (0x3000 + i % 0x5000).toChar().toString() }
        val remap = List(classes) { it }

        // Multiplication by 37 is coprime with 18,709, so every row has unique
        // scores. That keeps this performance fixture independent of tie policy;
        // the Rust/native shim tests separately pin Java PriorityQueue ties.
        val logits = Array(modelSeqLen) { t ->
            FloatArray(classes) { c ->
                val unique = (c * 37 + t * 101) % 18_709
                unique / 100f
            }
        }
        val cropLogits = Array(actualSeqLen) { t -> logits[t].copyOf() }

        val packedModel = FloatArray(modelSeqLen * TOP_K * 2)
        for (t in 0 until modelSeqLen) {
            val row = logits[t]
            val order = (0 until classes)
                .sortedWith(compareByDescending<Int> { row[it] }.thenBy { it })
                .take(TOP_K)
            for (k in 0 until TOP_K) {
                val c = order[k]
                packedModel[(t * TOP_K + k) * 2] = c.toFloat()
                packedModel[(t * TOP_K + k) * 2 + 1] = row[c]
            }
        }
        val packedActual = packedModel.copyOf(actualSeqLen * TOP_K * 2)
        return Fixture(vocab, remap, cropLogits, packedModel, packedActual)
    }

    private fun decodeChar(vocab: List<String>, classIdx: Int): Char = when {
        classIdx == 18_709 -> ' '
        classIdx == 18_708 -> '\u3000'
        classIdx in 1..18_708 -> vocab.getOrNull(classIdx - 1)?.firstOrNull() ?: '\uFFFD'
        else -> '\u3000'
    }

    private fun remapClass(remap: List<Int>, prunedIdx: Int): Int =
        remap.getOrElse(prunedIdx) { prunedIdx }

    private fun top15(vocab: List<String>, remap: List<Int>, row: FloatArray):
        List<Pair<Char, Float>> {
        val pq = java.util.PriorityQueue<Int>(TOP_K + 1, compareBy { row[it] })
        for (c in row.indices) {
            pq.add(c)
            if (pq.size > TOP_K) pq.poll()
        }
        return pq.toList().sortedByDescending { row[it] }
            .map { c -> decodeChar(vocab, remapClass(remap, c)) to row[c] }
    }

    /** The pre-swap OcrEngine delegated every peak through this scalar FFI. */
    private fun peakOffset(v0: Float, v1: Float, v2: Float): Float =
        charBoxesPeakOffset(v0, v1, v2)

    /** Exact former Kotlin top-K decoder, including its old nested inputs. */
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
        return Result(
            text.toString(), alternatives, charCols.toFloatArray(), seqLen, topChars,
        )
    }

    /** Exact former full-logits call site: ctcDecode plus its separate rawAlts pass. */
    private fun legacyFull(vocab: List<String>, remap: List<Int>, rows: Array<FloatArray>): Result {
        val rawAlternatives = rows.map { top15(vocab, remap, it) }
        val text = StringBuilder()
        val alternatives = mutableListOf<List<Pair<Char, Float>>>()
        val charCols = mutableListOf<Float>()
        var previousClass = 0

        fun wval(row: Int, classIdx: Int): Float? = rows.getOrNull(row)?.getOrNull(classIdx)

        for (t in rows.indices) {
            val row = rows[t]
            var maxIdx = 0
            var maxVal = Float.NEGATIVE_INFINITY
            for (c in row.indices) {
                if (row[c] > maxVal) {
                    maxVal = row[c]
                    maxIdx = c
                }
            }
            val left = wval(t - 1, maxIdx)
            val right = wval(t + 1, maxIdx)
            val column = if (left != null && right != null) {
                t + peakOffset(left, maxVal, right)
            } else {
                t.toFloat()
            }
            val classIdx = remapClass(remap, maxIdx)
            val indexed = rawAlternatives[t].toMutableList()
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
        return Result(
            text.toString(), alternatives, charCols.toFloatArray(), rows.size, rawAlternatives,
        )
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

    private fun rustPacked(decoder: CtcDecode, packedModel: FloatArray): Result {
        // Match production's trim from padded model output to useful timesteps.
        val packed = packedModel.copyOf(60 * TOP_K * 2)
        return decoder.decodeTopKCompact(packed.asList(), 60L, false).materialize()
    }

    private fun rustFull(decoder: CtcDecode, f: Fixture): Result {
        val rows = OcrEngine.packCtcCandidateRows(f.cropLogits, 60, 18_710)
        return decoder.decodeFullCompact(
            rows.packed.asList(),
            rows.leftScores.asList(),
            rows.rightScores.asList(),
            60L,
            60L,
            false,
        ).materialize()
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

    private fun assertSame(expected: Result, actual: Result) {
        assertEquals(expected.text, actual.text)
        assertEquals(expected.alternatives, actual.alternatives)
        assertArrayEquals(expected.charCols, actual.charCols, 0f)
        assertEquals(expected.seqLenTotal, actual.seqLenTotal)
        assertEquals(expected.rawAlternatives, actual.rawAlternatives)
    }

    private fun measurePair(
        samples: Int,
        warmups: Int,
        before: () -> Result,
        after: () -> Result,
    ): Triple<LongArray, LongArray, Int> {
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
        check(blackHole != Int.MIN_VALUE) // keep both lanes observable
        return Triple(beforeNs, afterNs, blackHole)
    }

    private fun percentile(values: LongArray, percentile: Int): Double {
        val sorted = values.sortedArray()
        val index = ((sorted.size - 1) * percentile / 100.0).toInt()
        return sorted[index] / 1_000.0
    }

    private fun report(
        lane: String,
        beforeNs: LongArray,
        afterNs: LongArray,
    ) {
        val beforeP50 = percentile(beforeNs, 50)
        val beforeP95 = percentile(beforeNs, 95)
        val afterP50 = percentile(afterNs, 50)
        val afterP95 = percentile(afterNs, 95)
        println(
            "CTC_JVM_BENCH lane=$lane classes=18710 modelSeq=64 actualSeq=60 " +
                "samples=${beforeNs.size} beforeP50Us=$beforeP50 beforeP95Us=$beforeP95 " +
                "afterP50Us=$afterP50 afterP95Us=$afterP95 " +
                "deltaP50Us=${afterP50 - beforeP50}"
        )
    }

    @Test
    fun packedAndFullDecode_matchLegacy_andBenchmark() {
        val f = fixture()
        CtcDecode(f.vocab, f.remap).use { decoder ->
            val packedBefore = legacyPackedCallSite(f)
            val packedAfter = rustPacked(decoder, f.packedModel)
            assertSame(packedBefore, packedAfter)

            val fullBefore = legacyFull(f.vocab, f.remap, f.cropLogits)
            val fullAfter = rustFull(decoder, f)
            assertSame(fullBefore, fullAfter)

            val (packedBeforeNs, packedAfterNs) = measurePair(
                samples = 500,
                warmups = 200,
                before = { legacyPackedCallSite(f) },
                after = { rustPacked(decoder, f.packedModel) },
            )
            report("packed-topk", packedBeforeNs, packedAfterNs)

            val (fullBeforeNs, fullAfterNs) = measurePair(
                samples = 20,
                warmups = 3,
                before = { legacyFull(f.vocab, f.remap, f.cropLogits) },
                after = { rustFull(decoder, f) },
            )
            report("full-fallback", fullBeforeNs, fullAfterNs)
        }
    }
}
