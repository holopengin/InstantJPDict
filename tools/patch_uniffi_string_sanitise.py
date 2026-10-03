#!/usr/bin/env python3
"""Re-apply the #106 lone-surrogate sanitiser to the generated shim bindings.

`app/src/main/java/uniffi/nav_graph_core/nav_graph_core.kt` is generated, so a
regeneration silently drops the local patch in `FfiConverterString.toUtf8`. Run
this after every regeneration; it is idempotent (it refuses to patch twice).

Why the patch exists: an app's own text -- an accessibility node's text, or a
slice of one -- can carry an unpaired surrogate. That is legal in a Java String
and impossible to encode as UTF-8, so UniFFI's deliberately strict encoder threw
`MalformedInputException: Input length = 1` out of any Rust call taking a string
(a dictionary lookup, crashing the main thread). The sanitiser substitutes U+FFFD
one-for-one, so character indices keep their meaning and valid pairs survive.
"""
import pathlib
import sys

TARGET = pathlib.Path(__file__).resolve().parents[1] / (
    "app/src/main/java/uniffi/nav_graph_core/nav_graph_core.kt"
)

ORIGINAL = """    fun toUtf8(value: String): ByteBuffer {
        // Make sure we don't have invalid UTF-16, check for lone surrogates.
        return Charsets.UTF_8.newEncoder().run {
            onMalformedInput(CodingErrorAction.REPORT)
            encode(CharBuffer.wrap(value))
        }
    }"""

PATCHED = ORIGINAL.replace(
    "        return Charsets.UTF_8.newEncoder().run {",
    """        //
        // LOCAL PATCH -- #106. Keep after regenerating: `tools/patch_uniffi_string_sanitise.py`
        // re-applies it. An unpaired surrogate in `value` (legal in a Java String,
        // impossible to encode as UTF-8) used to throw out of this strict encoder on
        // every Rust call taking a string. U+FFFD is substituted one-for-one, so
        // character indices keep their meaning; valid pairs are untouched.
        val sanitised = sanitiseUnpairedSurrogates(value)
        return Charsets.UTF_8.newEncoder().run {""",
).replace(
    "            encode(CharBuffer.wrap(value))",
    "            encode(CharBuffer.wrap(sanitised))",
) + """

    /** See the LOCAL PATCH note in [toUtf8]. */
    private fun sanitiseUnpairedSurrogates(value: String): String {
        var i = 0
        var out: StringBuilder? = null
        while (i < value.length) {
            val c = value[i]
            val pair = Character.isHighSurrogate(c) &&
                i + 1 < value.length && Character.isLowSurrogate(value[i + 1])
            val lone = (Character.isHighSurrogate(c) && !pair) || Character.isLowSurrogate(c)
            if (lone) {
                if (out == null) out = StringBuilder(value.length).append(value, 0, i)
                out.append('\\uFFFD')
                i++
            } else {
                out?.append(c)
                if (pair) {
                    out?.append(value[i + 1])
                    i += 2
                } else {
                    i++
                }
            }
        }
        return out?.toString() ?: value
    }"""


def main() -> int:
    text = TARGET.read_text()
    if "LOCAL PATCH -- #106" in text:
        print(f"{TARGET}: already patched")
        return 0
    if text.count(ORIGINAL) != 1:
        print(f"{TARGET}: the generated toUtf8 does not look like the shape this patch knows; "
              "re-derive the patch before regenerating.", file=sys.stderr)
        return 1
    TARGET.write_text(text.replace(ORIGINAL, PATCHED, 1))
    print(f"{TARGET}: patched")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
