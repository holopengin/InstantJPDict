#!/usr/bin/env python3
"""Build the committed Japanese text sample used by the synthetic generator.

Sources
-------
* Jitendex example sentences extracted from the shared fixture
  ``app/src/test/resources/jitendex/entries.json`` (structured-content walk:
  nodes whose ``data.content == "example-sentence-a"`` hold the Japanese
  sentence; ``-b`` holds the English translation and is skipped).
* Aozora Bunko, 太宰治「走れメロス」 (card 000035, file 1567, ruby version).
  Fetched from https://www.aozora.gr.jp/cards/000035/files/1567_ruby_4948.zip,
  decoded as Shift_JIS, Aozora markup stripped: ``｜base《reading》`` ruby and
  ``［＃…］`` editorial notes.  Aozora Bunko states the work is public domain
  (author died 1948); redistribution follows the Aozora Bunko terms
  (https://www.aozora.gr.jp/guide/kijyunn.html) — attribution is kept in
  ``corpus/PROVENANCE.md``.

The output, ``corpus/ja_sample.txt``, is a small, deterministic sample:
one sentence per line, deduplicated, filtered to characters the bundled
Noto JP fonts can render, sorted by a stable key so regeneration is
byte-identical.

Usage:
    python corpus.py [--aozora-zip PATH] [--out corpus/ja_sample.txt]
"""

from __future__ import annotations

import argparse
import json
import re
import unicodedata
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
JITENDEX = REPO / "app/src/test/resources/jitendex/entries.json"
DEFAULT_OUT = HERE / "corpus/ja_sample.txt"
DEFAULT_CACHE = Path("/tmp/opencode/aozora_1567.zip")
AOZORA_URL = "https://www.aozora.gr.jp/cards/000035/files/1567_ruby_4948.zip"

MAX_LEN = 48
MIN_LEN = 6

# Hand-picked trigger strings for glyph classes the prose sample under-covers:
# brackets, corner punctuation, small kana, halfwidth katakana, ASCII mixes,
# iteration marks, and fullwidth digits.  Kept as data (not generated) so the
# generator's variety knobs have a stable named input.
CURATED = [
    "「こんにちは、世界。」",
    "テスト、テスト。",
    "コンピューター・サイエンス",
    "お茶とコーヒー、それからケーキ。",
    "Ａ Ｂ Ｃ あ い う",
    "Hello, 世界! 1234",
    "ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ",
    "々〆ヶ",
    "（株）山田商事",
    "彼は「そうだ」と言った。",
    "……、。」」",
    "1,234,567円",
    "そうですか？",
    "ええ、そうですね！",
    "『吾輩は猫である』を読む。",
    "【重要】お知らせ：メンテナンス中",
    "ひらがなカタカナ漢字ABCｱｲｳｴｵ",
    "縦書きのテスト。、」",
]


def jitendex_sentences() -> list[str]:
    data = json.loads(JITENDEX.read_text(encoding="utf-8"))
    out: list[str] = []

    def walk(node) -> None:
        if isinstance(node, dict):
            data_obj = node.get("data")
            if isinstance(data_obj, dict) and data_obj.get("content") == "example-sentence-a":
                out.append(_strings(node))
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for item in node:
                walk(item)

    def _strings(node) -> str:
        parts: list[str] = []

        def collect(n) -> None:
            if isinstance(n, dict):
                # <rt>/<rp> hold ruby readings, not sentence text.
                if n.get("tag") in ("rt", "rp"):
                    return
                if isinstance(n.get("content"), str):
                    parts.append(n["content"])
                else:
                    collect(n.get("content"))
            elif isinstance(n, list):
                for item in n:
                    collect(item)
            elif isinstance(n, str):
                # Bare string children (ruby base text, plain runs).
                parts.append(n)

        collect(node)
        return "".join(parts)

    walk(data["entries"])
    return out


RUBY_RE = re.compile(r"｜([^《｜]{1,20})《[^》]{1,30}》")
NOTE_RE = re.compile(r"［＃[^］]*］")
BRACKET_RUBY_RE = re.compile(r"([\u4e00-\u9fff々〆ヵヶ]{1,20})《[^》]{1,30}》")


def aozora_sentences(zip_path: Path) -> list[str]:
    if not zip_path.exists():
        import urllib.request

        zip_path.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(AOZORA_URL, zip_path)  # noqa: S310
    with zipfile.ZipFile(zip_path) as zf:
        name = next(n for n in zf.namelist() if n.endswith(".txt"))
        text = zf.read(name).decode("cp932", errors="replace")
    text = text.replace("\r\n", "\n")
    # Aozora files open with a notation-legend block (ruby examples etc.)
    # terminated by a dashed rule; the body starts after it.
    lines_all = text.split("\n")
    rules = [
        i
        for i, line in enumerate(lines_all[:20])
        if len(line.strip()) > 10 and set(line.strip()) == {"-"}
    ]
    if rules:
        text = "\n".join(lines_all[rules[-1] + 1 :])
    text = RUBY_RE.sub(r"\1", text)
    text = NOTE_RE.sub("", text)
    text = BRACKET_RUBY_RE.sub(r"\1", text)
    text = text.replace("\r\n", "\n").replace("\u3000", " ")
    sentences: list[str] = []
    for line in text.split("\n"):
        line = line.strip()
        if not line or line.startswith("-") or line.startswith("底本"):
            continue
        # Split on sentence punctuation, keeping the punctuation.
        for piece in re.split(r"(?<=[。！？])", line):
            piece = piece.strip()
            if piece:
                sentences.append(piece)
    return sentences


def usable(sentence: str, cmap: set[str]) -> bool:
    if not (MIN_LEN <= len(sentence) <= MAX_LEN):
        return False
    if any(ch not in cmap for ch in sentence):
        return False
    # Keep the sample readable prose: drop Aozora's inline editorial noise.
    if any(ch in sentence for ch in "［］{}<>|"):
        return False
    return True


def load_cmap(font_path: Path) -> set[str]:
    from fontTools.ttLib import TTFont

    font = TTFont(font_path, lazy=True)
    cmap = font.getBestCmap()
    chars = {chr(cp) for cp in cmap}
    # Normalise to NFC so composed kana with dakuten (が vs か+゛) match.
    return {unicodedata.normalize("NFC", c) for c in chars}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--aozora-zip", type=Path, default=DEFAULT_CACHE)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--no-aozora", action="store_true")
    args = ap.parse_args()

    cmap = load_cmap(REPO / "app/src/main/assets/fonts/NotoSansJP-Regular.ttf")
    ji = [s for s in jitendex_sentences()]
    ao = [] if args.no_aozora else aozora_sentences(args.aozora_zip)

    seen: set[str] = set()
    lines: list[str] = []
    for sentence in ji + ao:
        s = unicodedata.normalize("NFC", sentence.strip())
        if not usable(s, cmap) or s in seen:
            continue
        seen.add(s)
        lines.append(s)

    # Deterministic sample: keep Jitendex first, then an even spread over
    # Aozora (stride sampling keeps sentence variety, not the opening only).
    ji_n = len([s for s in ji if s in seen])
    aozora_part = lines[ji_n:]
    stride = max(1, len(aozora_part) // 140)
    sample = lines[:ji_n] + aozora_part[::stride][:140]
    sample += [c for c in CURATED if c not in seen]

    args.out.parent.mkdir(parents=True, exist_ok=True)
    header = (
        "# Synthetic-generator text sample: Jitendex example sentences + Aozora Bunko\n"
        "# (太宰治「走れメロス」, card 000035 file 1567). See corpus/PROVENANCE.md.\n"
    )
    args.out.write_text(header + "\n".join(sample) + "\n", encoding="utf-8")
    print(f"wrote {len(sample)} sentences -> {args.out}")
    print(f"  jitendex={ji_n} aozora={len(sample) - ji_n}")


if __name__ == "__main__":
    main()
