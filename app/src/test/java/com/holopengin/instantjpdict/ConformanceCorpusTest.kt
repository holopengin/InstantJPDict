package com.holopengin.instantjpdict

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.holopengin.instantjpdict.data.DictionaryEntry
import com.holopengin.instantjpdict.util.CharLm
import com.holopengin.instantjpdict.util.GapCandidates
import com.holopengin.instantjpdict.util.KanaSizeEncoder
import com.holopengin.instantjpdict.util.KanaSizeFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Android side of the shared cross-platform conformance corpus
 * (ticket `pipeline-sharing/01`).
 *
 * The cases live as plain-data JSON under `app/src/test/resources/conformance/`
 * (byte-identical copies of the PC `accessibility_daemon/tests/conformance/`
 * corpus — see `README.md` there for provenance and the re-sync procedure) and
 * are parsed with Gson, already a project dependency: no new test dependency
 * was added for this runner. Every pure-module stage runs 1:1 against the
 * current Kotlin (FuriganaRule, rotated geometry, the real read-order sort,
 * GapCandidates/CharLm, the KanaSize window + epsilon policy, and
 * Definitions/format); `detection` image cases use the case's explicit
 * `det_thresh`/`det_unclip` on the PC side and stay Android-manual here (see
 * [ANDROID_MANUAL]), as does the PC-hosted `recognition` kind.
 *
 * A case file with no coverage — an unknown `kind`, or a file whose stem does
 * not match its `id` — fails [everyCaseFileHasCoverage] loudly, so a case can
 * never silently stop running.
 */
class ConformanceCorpusTest {

    private val gson = Gson()

    // ── corpus loading ──────────────────────────────────────────────────

    private data class Case(val file: String, val root: JsonObject) {
        val id: String get() = root.get("id").asString
        val kind: String get() = root.get("kind").asString
    }

    /** Every case file, sorted by file name. Filesystem (not classpath), like
     *  [TestAssets] file lookups: the classloader cannot list a directory. */
    private fun allCases(): List<Case> {
        val candidates = listOf(
            File("app/src/test/resources/conformance/cases"),
            File("src/test/resources/conformance/cases"),
        )
        val dir = candidates.firstOrNull { it.isDirectory }
            ?: error("conformance cases not found; tried ${candidates.joinToString { it.path }}")
        return dir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }.map { f ->
            Case(f.name, JsonParser.parseString(f.readText(Charsets.UTF_8)).asJsonObject)
        }
    }

    private fun kindCases(kind: String): List<Case> = allCases().filter { it.kind == kind }

    private fun JsonObject.doubleField(name: String): Double =
        get(name)?.takeIf { !it.isJsonNull }?.asDouble
            ?: error("missing case field $name")

    private fun JsonObject.intField(name: String): Int = doubleField(name).toInt()

    private fun JsonObject.stringField(name: String): String =
        get(name)?.takeIf { !it.isJsonNull }?.asString
            ?: error("missing case field $name")

    private fun JsonArray.floats(): List<Float> = map { it.asDouble.toFloat() }

    private fun angleTol(root: JsonObject): Float =
        root.getAsJsonObject("tolerances")?.get("angle_deg")?.asDouble?.toFloat() ?: 1.0f

    private fun rectOf(a: JsonArray): JpDictRect {
        val v = a.map { it.asInt }
        require(v.size == 4) { "rects are [left, top, right, bottom]" }
        return JpDictRect(v[0], v[1], v[2], v[3])
    }

    // ── the orphan guard ────────────────────────────────────────────────

    /** Kinds executed by a runner below. */
    private val EXECUTED_KINDS = setOf(
        "furigana", "geometry", "reading_order", "gap", "char_lm", "kana", "dictionary",
    )

    /**
     * Kinds with no JVM runner, each explicitly Android-manual with its reason
     * (per the ticket: a manual case records the reason, it never silently
     * passes). A new case of one of these kinds still has to be listed in
     * [MANUAL_IDS], or [androidManualCasesAreExplicit] fails.
     */
    private val ANDROID_MANUAL = mapOf(
        // Bitmap input + the full detect chain including the native det mask
        // (DetNcnn .so): android.graphics is stubbed in JVM unit tests and
        // Robolectric is deliberately not added as a dependency. The PC
        // harness pins these from image bytes with the case's explicit
        // det_thresh/det_unclip; the pure-module stages they cover
        // (furigana rules, read-order sort) run here from recorded inputs.
        "detection" to "needs Bitmap decode + native det mask; no JVM runner without Robolectric",
        // PC-hosted full inference (detect_lines + recognize_boxes_collect over
        // vendored photos, ~36 s in the PC suite); Android hosts inference only
        // behind an instrumented benchmark, and char boxes from real inference
        // are an accepted platform substitution (TOLERANCES.md §2/§5).
        "recognition" to "needs hosted ncnn inference; PC-only until Android tests can host it",
    )

    /** Case ids covered by reason rather than by a runner. */
    private val MANUAL_IDS = setOf(
        "detection-01-h-bars", "detection-02-v-columns", "detection-03-ruby-strip",
        "recognition-01-tategaki-fonts", "recognition-02-newspaper-ruby",
        "recognition-03-phone-photo", "recognition-04-ruby-test",
        "recognition-05-phone-ui-1", "recognition-06-phone-ui-2",
        "recognition-07-phone-ui-3", "recognition-08-phone-ui-4",
        "recognition-09-phone-ui-5",
    )

    @Test
    fun everyCaseFileHasCoverage() {
        val cases = allCases()
        assertTrue("corpus is empty — re-sync did not run?", cases.isNotEmpty())
        for (c in cases) {
            assertEquals(
                "${c.file}: file stem must match the case id",
                c.file.removeSuffix(".json"), c.id,
            )
            if (c.kind !in EXECUTED_KINDS && c.kind !in ANDROID_MANUAL) {
                fail("${c.id}: unknown kind ${c.kind} — add a runner or fix the case")
            }
        }
        for (kind in EXECUTED_KINDS) {
            assertTrue(
                "kind $kind has a runner but no case files — a deletion must fail loudly",
                cases.any { it.kind == kind },
            )
        }
    }

    @Test
    fun androidManualCasesAreExplicit() {
        val cases = allCases()
        val manual = cases.filter { it.kind in ANDROID_MANUAL }
        assertEquals(
            "a manual case was added/removed without triage — update MANUAL_IDS + reason",
            MANUAL_IDS.sorted(), manual.map { it.id }.sorted(),
        )
        for (c in manual) {
            val body = c.root.getAsJsonObject("case")
            // Even manual, the rule holds: explicit case values, never platform
            // defaults (TOLERANCES.md §3).
            body.doubleField("det_thresh")
            body.doubleField("det_unclip")
            if (c.kind == "recognition") {
                assertEquals("$c.id: recognition runs in mode both", "both", body.stringField("recognition_mode"))
                assertTrue(
                    "$c.id: recognition stays PC-hosted-only",
                    c.root.get("mobile_mirror").isJsonNull,
                )
            }
        }
    }

    // ── furigana ────────────────────────────────────────────────────────

    @Test
    fun furiganaCases() {
        for (c in kindCases("furigana")) {
            val body = c.root.getAsJsonObject("case")
            val img = body.getAsJsonArray("img").floats()
            for (p in body.getAsJsonArray("pairs")) {
                val o = p.asJsonObject
                val get = { k: String -> rectOf(o.getAsJsonArray(k)) }
                val got = when (o.stringField("orientation")) {
                    "horizontal" -> FuriganaRule.isRubyHorizontal(
                        get("small_raw"), get("big_raw"),
                        get("small_un"), get("big_un"),
                        img[0].toInt(), img[1].toInt(),
                    )
                    "vertical" -> FuriganaRule.isRubyVertical(
                        get("small_raw"), get("big_raw"),
                        get("small_un"), get("big_un"), img[1].toInt(),
                    )
                    else -> error("${c.id}: bad orientation")
                }
                assertEquals(
                    "${c.id} pair '${o.stringField("name")}': ruby verdict drifted",
                    o.get("expect_ruby").asBoolean, got,
                )
            }
        }
    }

    // ── geometry ────────────────────────────────────────────────────────

    /**
     * The PC frame definition (centre + local sizes + local-x angle, y-down,
     * clockwise-positive) as the quad the Android pipeline would hold: local
     * sizes stay fixed like PC `RotatedBox::new`, and the axes take the
     * orientation the fit guarantees ("the chosen axes point right/down so the
     * frame is never mirrored", RotatedGeometry) — local x to the right half
     * plane for horizontal frames, the reading axis down for vertical ones,
     * which is exactly PC's `[-90°, 90°)` normalization. The assertions then
     * run the production predicates ([RotatedGeometry.isVertical],
     * [JpDictQuad.isAxisAligned], [JpDictQuad.tiltDeg]) against corpus values.
     */
    private fun frameQuad(cx: Double, cy: Double, w: Double, h: Double, angleDeg: Double): JpDictQuad {
        val t = Math.toRadians(angleDeg)
        var xa = QuadPoint(cos(t).toFloat(), sin(t).toFloat())
        var ya = QuadPoint(-xa.y, xa.x)
        if (w >= h) {
            if (xa.x < 0f) {
                xa = QuadPoint(-xa.x, -xa.y)
                ya = QuadPoint(-ya.x, -ya.y)
            }
        } else {
            if (ya.y < 0f) {
                xa = QuadPoint(-xa.x, -xa.y)
                ya = QuadPoint(-ya.x, -ya.y)
            }
        }
        val hw = (w / 2).toFloat()
        val hh = (h / 2).toFloat()
        val ox = (cx - hw * xa.x - hh * ya.x).toFloat()
        val oy = (cy - hw * xa.y - hh * ya.y).toFloat()
        fun px(lx: Float, ly: Float) = QuadPoint(ox + lx * xa.x + ly * ya.x, oy + lx * xa.y + ly * ya.y)
        return JpDictQuad(px(0f, 0f), px(2 * hw, 0f), px(2 * hw, 2 * hh), px(0f, 2 * hh))
    }

    @Test
    fun geometryCases() {
        for (c in kindCases("geometry")) {
            val tol = angleTol(c.root)
            for (f in c.root.getAsJsonObject("case").getAsJsonArray("frames")) {
                val o = f.asJsonObject
                val q = frameQuad(
                    o.get("cx")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0,
                    o.get("cy")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0,
                    o.doubleField("w"), o.doubleField("h"), o.doubleField("angle_deg"),
                )
                val exp = o.getAsJsonObject("expect")
                assertEquals("${c.id}: isVertical drifted for $o", exp.get("vertical").asBoolean, RotatedGeometry.isVertical(q))
                assertEquals("${c.id}: isAxisAligned drifted for $o", exp.get("axis_aligned").asBoolean, q.isAxisAligned())
                exp.get("norm_angle_deg")?.takeIf { !it.isJsonNull }?.asDouble?.let { n ->
                    assertEquals("${c.id}: angle normalization drifted for $o", n.toFloat(), q.tiltDeg, tol)
                }
            }
        }
    }

    // ── reading order ───────────────────────────────────────────────────

    @Test
    fun readingOrderCases() {
        for (c in kindCases("reading_order")) {
            val body = c.root.getAsJsonObject("case")
            val rects = body.getAsJsonArray("boxes").map { b ->
                val o = b.asJsonObject
                val e = o.getAsJsonArray("box").map { it.asInt }
                // The Android rect sort decides orientation from the box's own
                // sizes (the axis-aligned path has no separate frame): a case
                // whose frame sizes ever differ from the box sizes cannot run
                // here and must fail loudly instead of sorting something else.
                assertEquals(
                    "${c.id}: w_local diverged from the box — needs the LineBox sort",
                    e[2], o.doubleField("w_local").toInt(),
                )
                assertEquals(
                    "${c.id}: h_local diverged from the box — needs the LineBox sort",
                    e[3], o.doubleField("h_local").toInt(),
                )
                JpDictRect(e[0], e[1], e[0] + e[2], e[1] + e[3])
            }
            val got = OcrEngine.sortDetectedBoxes(rects)
                .map { r -> rects.indexOfFirst { it === r } }
            val expect = body.getAsJsonArray("expect_order").map { it.asInt }
            assertEquals("${c.id}: reading order drifted", expect, got)
        }
    }

    // ── gap + char LM ───────────────────────────────────────────────────

    /** A toy CharLm table in the shipped packed format, like the PC harness
     *  `pack_lm` (and GapCandidatesTest.packed) — but with the case's own
     *  unigram mass, which the cases pin independently of the entries. */
    private fun packLm(entries: List<Pair<String, Int>>, mass: Int): ByteArray {
        val records = entries.map { (ngram, count) ->
            val units = IntArray(CharLm.MAX_ORDER)
            ngram.forEachIndexed { i, ch -> units[i] = ch.code }
            units to count
        }.sortedWith(compareBy({ it.first[0] }, { it.first[1] }, { it.first[2] }, { it.first[3] }))
        val head = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        head.put("CLM1".toByteArray()).putInt(records.size).putInt(CharLm.MAX_ORDER).putInt(mass)
        val out = java.io.ByteArrayOutputStream()
        out.write(head.array())
        for ((units, count) in records) {
            val rec = java.nio.ByteBuffer.allocate(10).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            units.forEach { rec.putShort(it.toShort()) }
            rec.putShort(count.toShort())
            out.write(rec.array())
        }
        return out.toByteArray()
    }

    private fun lmOf(body: JsonObject): CharLm? {
        val lm = body.get("lm") ?: return null
        if (lm.isJsonNull) return null
        val o = lm.asJsonObject
        val entries = o.getAsJsonArray("entries").map {
            it.asJsonArray.let { e -> e[0].asString to e[1].asInt }
        }
        return CharLm.fromBytes(packLm(entries, o.intField("mass")))
            ?: error("toy table does not pack")
    }

    /** One corpus character: every corpus string at these positions is a
     *  single BMP char, so this fails loudly on anything else. */
    private fun singleChar(s: String): Char =
        if (s.length == 1) s[0] else error("expected one char, got $s")

    @Test
    fun gapCases() {
        for (c in kindCases("gap")) {
            val body = c.root.getAsJsonObject("case")
            val text = body.stringField("text")
            val gapIndex = body.intField("gap_index")
            assertEquals("${c.id}: gap_index must point at the ◌ placeholder", OcrEngine.GAP_CHAR, text[gapIndex])
            body.get("expect_context")?.takeIf { !it.isJsonNull }?.asJsonArray?.let { exp ->
                assertEquals(
                    "${c.id}: context derivation drifted",
                    exp.map { singleChar(it.asString) },
                    GapCandidates.contextBefore(text, gapIndex).toString().toList(),
                )
            }
            val alts = body.getAsJsonArray("alternatives").map { step ->
                step.asJsonArray.map { e ->
                    e.asJsonArray.let { singleChar(it[0].asString) to it[1].asDouble.toFloat() }
                }
            }
            val lm = lmOf(body)
            val limit = body.get("limit")?.asInt ?: GapCandidates.MAX
            val got = GapCandidates.generate(text, alts, gapIndex, lm, limit)
            assertEquals(
                "${c.id}: candidate list drifted",
                body.getAsJsonArray("expect").map { singleChar(it.asString) }, got,
            )
            body.get("expect_fallback_first")?.takeIf { !it.isJsonNull }?.asString?.let { first ->
                val fb = GapCandidates.fallback(text, gapIndex, lm, limit)
                assertTrue("${c.id}: fallback must never be empty", fb.isNotEmpty())
                assertEquals("${c.id}: fallback class order drifted: $fb", singleChar(first), fb[0])
            }
        }
    }

    @Test
    fun charLmCases() {
        for (c in kindCases("char_lm")) {
            val body = c.root.getAsJsonObject("case")
            val lm = CharLm.fromBytes(
                packLm(
                    body.getAsJsonArray("entries").map {
                        it.asJsonArray.let { e -> e[0].asString to e[1].asInt }
                    },
                    body.intField("mass"),
                ),
            ) ?: error("${c.id}: toy table does not pack")
            for (q in body.getAsJsonArray("counts")) {
                val o = q.asJsonObject
                val ngram = o.getAsJsonArray("ngram").map { it.asString }.joinToString("")
                assertEquals("${c.id}: count drifted for $ngram", o.intField("expect"), lm.count(ngram))
            }
            for (q in body.getAsJsonArray("ranks")) {
                val o = q.asJsonObject
                val ctx = o.getAsJsonArray("context").map { it.asString }.joinToString("")
                val pool = o.getAsJsonArray("pool").map { singleChar(it.asString) }
                assertEquals(
                    "${c.id}: rank drifted",
                    o.getAsJsonArray("expect").map { singleChar(it.asString) },
                    lm.rank(ctx, pool),
                )
            }
        }
    }

    // ── kana ────────────────────────────────────────────────────────────

    @Test
    fun kanaCases() {
        for (c in kindCases("kana")) {
            val body = c.root.getAsJsonObject("case")
            body.getAsJsonArray("encoder")?.let { enc ->
                for (e in enc) {
                    val o = e.asJsonObject
                    val text = o.stringField("text")
                    val index = o.intField("index")
                    val got = KanaSizeEncoder.window(text, index)
                    val want = o.getAsJsonArray("expect_window_head").map { it.asInt }
                    assertEquals("${c.id}: window is ${got.size} bytes, expected ${want.size}", want.size, got.size)
                    for (i in want.indices) {
                        assertEquals("${c.id}: window byte $i drifted for $text@$index", want[i], got[i])
                    }
                }
            }
            body.get("policy")?.takeIf { !it.isJsonNull }?.asJsonObject?.let { p ->
                val lines = p.getAsJsonArray("lines").map { s ->
                    val t = s.asString
                    LineResult(
                        text = t,
                        charBoxes = emptyList(),
                        alternatives = MutableList(t.length) { mutableListOf() },
                    )
                }
                val logits = p.getAsJsonArray("logits").floats().toFloatArray()
                val epsilon = p.doubleField("epsilon").toFloat()
                // Scoring injected: the ε policy runs on the JVM with no native
                // net, exactly like KanaSizeFixTest.
                val out = KanaSizeFix.apply(lines, { _, _ -> logits }, epsilon)
                assertEquals(
                    "${c.id}: corrected text drifted",
                    p.getAsJsonArray("expect_text").map { it.asString }, out.map { it.text },
                )
                val expectFlips = p.getAsJsonArray("expect_flips")
                val gotFlips = out.flatMapIndexed { li, l ->
                    l.overrides.map { (index, override) -> Triple(li, index, override.first) }
                }
                assertEquals("${c.id}: flip count drifted: $gotFlips", expectFlips.size(), gotFlips.size)
                for ((got, exp) in gotFlips.zip(expectFlips.map { it.asJsonObject })) {
                    assertEquals(exp.intField("line"), got.first)
                    assertEquals(exp.intField("index"), got.second)
                    assertEquals("${c.id}: flip source drifted", singleChar(exp.stringField("from")), lines[got.first].text[got.second])
                    assertEquals("${c.id}: flip target drifted", singleChar(exp.stringField("to")), got.third)
                }
            }
        }
    }

    // ── dictionary ──────────────────────────────────────────────────────

    /** All descendant nodes, depth-first (same walk as
     *  JitendexStructuredContentTest, kept in sync by review). */
    private fun flattenNodes(nodes: List<DefinitionNode>): List<DefinitionNode> =
        nodes + nodes.flatMap { flattenNodes(childrenOf(it)) }

    private fun childrenOf(node: DefinitionNode): List<DefinitionNode> = when (node) {
        is DefinitionNode.Text, is DefinitionNode.Ruby, is DefinitionNode.Tag,
        is DefinitionNode.Citation -> emptyList()
        is DefinitionNode.Group -> node.nodes
        is DefinitionNode.ListBlock -> node.items.flatten()
        is DefinitionNode.Example -> (node.content ?: emptyList()) + node.parts.flatten()
        is DefinitionNode.Table -> node.rows.flatten().flatten()
    }

    @Test
    fun dictionaryCases() {
        val controller = OcrOverlayStateController()
        val root = JsonParser.parseString(TestAssets.resourceText("jitendex/entries.json")).asJsonObject
        for (c in kindCases("dictionary")) {
            val body = c.root.getAsJsonObject("case")
            val term = body.stringField("term")
            val reading = body.stringField("reading")
            // Payload by reference, like the PC harness: the Android-side copy
            // of the shared fixture (verified byte-identical with the PC
            // tests/data/jitendex/entries.json at copy time; the case's
            // definitions_ref names the PC-side path).
            val row = root.getAsJsonArray("entries").firstOrNull {
                val o = it.asJsonObject
                o.get("term").asString == term && o.get("reading").asString == reading
            } ?: error("${c.id}: no jitendex fixture for $term ($reading)")
            val entry = DictionaryEntry(
                kanji = term,
                reading = reading,
                definitions = Gson().toJson(row.asJsonObject.get("definitions")),
                rules = "",
                popularity = 0,
                dictionaryId = 1,
            )
            val out = controller.formatDictionaryResults(
                listOf(TermMatch(term, listOf(entry))), Gson(), mapOf(1 to "Jitendex"),
            )
            assertEquals("${c.id}: expected one formatted entry", 1, out.size)
            val exp = body.getAsJsonObject("expect")
            val groups = out[0].readingGroups
            assertEquals(
                "${c.id}: reading groups drifted",
                exp.getAsJsonArray("readings").map { it.asString }, groups.map { it.reading },
            )
            assertEquals(
                "${c.id}: headwords drifted",
                exp.getAsJsonArray("headwords").map { it.asString },
                groups.flatMap { g -> g.headwords.map { it.kanji } },
            )
            val totalGroups = groups.sumOf { it.senseGroups.size }
            assertTrue(
                "${c.id}: sense groups shrank to $totalGroups",
                totalGroups >= exp.intField("min_sense_groups"),
            )
            val blob = groups.flatMap { g -> g.senseGroups }
                .flatMap { sg -> sg.senses }
                .flatMap { s -> flattenNodes(s.nodes) }
                .filterIsInstance<DefinitionNode.Text>()
                .joinToString("\n") { it.text }
            for (key in listOf("example_ja", "example_en")) {
                exp.get(key)?.takeIf { !it.isJsonNull }?.asString?.let { want ->
                    assertTrue("${c.id}: $key $want missing from formatted senses:\n$blob", blob.contains(want))
                }
            }
        }
    }
}
