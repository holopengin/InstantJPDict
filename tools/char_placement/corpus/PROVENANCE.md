# Text corpus provenance

`ja_sample.txt` is the committed text sample the synthetic generator draws
from.  It is rebuilt deterministically by `../corpus.py`; do not hand-edit.

Sources, in the order they appear in the file:

1. **Jitendex example sentences** (10 lines) — extracted from the shared
   fixture `app/src/test/resources/jitendex/entries.json` (structured-content
   walk: nodes tagged `example-sentence-a`; `-b` is the English translation
   and is skipped, `<rt>` ruby readings are skipped).  The fixture records
   its own provenance: Jitendex release 2026.08.11.0, index title
   "Jitendex.org [2026-08-11]", terms/readings kept verbatim.
2. **Aozora Bunko, 太宰治「走れメロス」** (158 lines sampled) — card 000035,
   file 1567, fetched from
   `https://www.aozora.gr.jp/cards/000035/files/1567_ruby_4948.zip`,
   decoded as Shift_JIS.  Aozora markup is stripped: `｜base《reading》` ruby,
   `<base>《reading》` bracket ruby and `［＃…］` editorial notes.  The text is
   public domain (the author died in 1948); redistribution follows the
   [Aozora Bunko terms](https://www.aozora.gr.jp/guide/kijyunn.html) and this
   attribution is the required notice.  The sample keeps ~140 sentences
   chosen by fixed-stride subsampling so the file stays small; the generator
   can use any text file via `--corpus`.
3. **Curated trigger strings** (16 lines, appended by `corpus.py`) — hand
   written to cover glyph classes the prose under-covers: corner punctuation,
   brackets, small kana, halfwidth katakana, ASCII/JP mixes, iteration marks,
   fullwidth digits.  These are synthetic, not corpus text.

Filtering: sentences must be 6–48 characters, renderable by the bundled
NotoSansJP-Regular cmap, and free of Aozora artefacts (`［］{}<>|`).

Regenerate with:

```
cd tools/char_placement
python corpus.py --aozora-zip /tmp/opencode/aozora_1567.zip
```
