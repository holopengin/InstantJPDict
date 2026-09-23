package com.holopengin.instantjpdict

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the [CharPlacement] research sketch to the Python reference
 * (`tools/char_placement/`) on a committed synthetic fixture.
 *
 * The fixture cases are exported by
 * `tools/char_placement/export_kotlin_fixture.py`: the crop PNG is also the
 * `pixels` input, and `expected_boxes` are the Python algorithm's output for
 * the same inputs.  Two properties are checked:
 *
 * * **Parity** — the Kotlin port lands within 1.5 px of the Python boxes.
 * * **Placement quality** — every box contains the true ink centre of the
 *   character it was placed for (the renderer's and tap target's contract),
 *   allowing a 0.5 px numeric slack.
 */
class CharPlacementTest {

    private data class Fixture(
        val id: String,
        val orientation: String,
        val text: String,
        val charCols: FloatArray,
        val seqLenTotal: Int,
        val cropW: Int,
        val cropH: Int,
        val pixels: IntArray,
        val steps: List<List<CharPlacement.Step>>,
        val inkBoxes: List<FloatArray>,
        val decodedToTrue: List<Int?>,
        val expected: List<FloatArray>,
    )

    private fun loadFixtures(): List<Fixture> {
        val names = listOf(
            "synth-core-00000",
            "synth-core-00001",
            "synth-rotated-00060",
            "synth-degraded-00030",
            "synth-ruby-00150",
            "synth-spacing-00120",
        )
        return names.map { loadFixture(it) }
    }

    private fun loadFixture(name: String): Fixture {
        val json = JsonParser.parseString(
            checkNotNull(javaClass.getResourceAsStream("/char_placement/$name.json")) {
                "fixture $name.json not on the test classpath"
            }.use { it.readBytes().toString(Charsets.UTF_8) }
        ).asJsonObject
        // Raw 8-bit luminance (Android unit tests have no java.awt/imageio).
        val gray = checkNotNull(
            javaClass.getResourceAsStream("/char_placement/$name.gray")
        ) { "fixture $name.gray not on the test classpath" }.use { it.readBytes() }

        val cropW = json["crop_w"].asInt
        val cropH = json["crop_h"].asInt
        assertEquals("fixture gray size must match crop_w*h", cropW * cropH, gray.size)
        val pixels = IntArray(cropW * cropH) { i ->
            val v = gray[i].toInt() and 0xFF
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        val steps = json["steps"].asJsonArray.map { step ->
            step.asJsonArray.map { pair ->
                val arr = pair.asJsonArray
                CharPlacement.Step(
                    char = arr[0].asString.let { if (it == "\u3000") CharPlacement.BLANK else it[0] },
                    score = arr[1].asFloat,
                )
            }
        }
        return Fixture(
            id = name,
            orientation = json["orientation"].asString,
            text = json["text"].asString,
            charCols = json["char_cols"].asJsonArray.map { it.asFloat }.toFloatArray(),
            seqLenTotal = json["seq_len_total"].asInt,
            cropW = cropW,
            cropH = cropH,
            pixels = pixels,
            steps = steps,
            inkBoxes = json["ink_boxes"].asJsonArray.map { box ->
                box.asJsonArray.map { it.asFloat }.toFloatArray()
            },
            decodedToTrue = json["decoded_to_true"].asJsonArray.map {
                if (it.isJsonNull) null else it.asInt
            },
            expected = json["expected_boxes"].asJsonArray.map { box ->
                box.asJsonArray.map { it.asFloat }.toFloatArray()
            },
        )
    }

    private fun place(f: Fixture): List<CharPlacement.Box> = CharPlacement.place(
        text = f.text,
        charCols = f.charCols,
        seqLenTotal = f.seqLenTotal,
        cropW = f.cropW,
        cropH = f.cropH,
        isVertical = f.orientation == "v",
        pixels = f.pixels,
        steps = f.steps,
    )

    @Test
    fun kotlinPortMatchesPythonReference() {
        for (f in loadFixtures()) {
            val boxes = place(f)
            assertEquals("${f.id}: box count", f.expected.size, boxes.size)
            var worst = 0f
            for (i in boxes.indices) {
                val e = f.expected[i]
                worst = maxOf(
                    worst,
                    kotlin.math.abs(boxes[i].left - e[0]),
                    kotlin.math.abs(boxes[i].top - e[1]),
                    kotlin.math.abs(boxes[i].right - e[2]),
                    kotlin.math.abs(boxes[i].bottom - e[3]),
                )
            }
            assertTrue("${f.id}: worst edge delta $worst px (limit 1.5)", worst <= 1.5f)
        }
    }

    @Test
    fun boxesContainTheirCharactersInkCentre() {
        for (f in loadFixtures()) {
            val boxes = place(f)
            val vertical = f.orientation == "v"
            val axis = if (vertical) 1 else 0
            for ((i, trueIdx) in f.decodedToTrue.withIndex()) {
                if (trueIdx == null || i >= boxes.size) continue
                val ink = f.inkBoxes[trueIdx]
                if (ink[2] <= ink[0] || ink[3] <= ink[1]) continue
                val centre = (ink[axis] + ink[axis + 2]) / 2f
                val box = boxes[i]
                val lo = if (vertical) box.top else box.left
                val hi = if (vertical) box.bottom else box.right
                assertTrue(
                    "${f.id}[$i]: ink centre $centre outside box [$lo, $hi]",
                    centre >= lo - 0.5f && centre <= hi + 0.5f,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Device regression: the bimodal-window walk must not read past the profile.
// ─────────────────────────────────────────────────────────────────────────────

class CharPlacementEdgeCaseTest {

    /**
     * The LAST glyph's Voronoi window is clipped to the line length L, and
     * `windowIsBimodal` walked `prof[a .. floor(hi)+1]` — one past the end
     * when `hi == L`, which is the common case for a det crop that hugs the
     * final glyph.  Kotlin threw IndexOutOfBoundsException, the rec callback
     * swallowed it silently (`catch (_: Exception) {})`), and the device saw
     * whole detected lines render blank ("detecting all lines, recognizing
     * half").  Python never showed it: `prof[a:b]` slices past the end.  The
     * profile is white background with two ink blobs, one on each glyph, so
     * the mass gate passes, the window is not smeared, and the bimodal walk
     * runs for the last glyph exactly as on device.
     */
    @Test
    fun `last glyph window reaching the line end does not throw`() {
        val cropW = 80
        val cropH = 40
        val pixels = IntArray(cropW * cropH) { -1 }  // white background
        fun ink(x0: Int, x1: Int) {
            for (y in 4 until 36) for (x in x0 until x1) pixels[y * cropW + x] = 0xFF202020.toInt()
        }
        ink(24, 36)   // glyph 0 (centre ~30t * 20px/t = 30)
        ink(64, 76)   // glyph 1, the LAST one (centre ~70; window clips to L=80)

        val boxes = CharPlacement.place(
            text = "あい",
            charCols = floatArrayOf(1f, 3f),
            seqLenTotal = 4,
            cropW = cropW,
            cropH = cropH,
            isVertical = false,
            pixels = pixels,
            steps = null,
        )
        assertEquals("both glyphs get a box", 2, boxes.size)
        val last = boxes[1]
        assertTrue(
            "last box must cover its ink centre (x=70): $last",
            last.left <= 70f && last.right >= 70f
        )
    }
}
