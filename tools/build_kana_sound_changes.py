#!/usr/bin/env python3
"""Build the historical kana sound-change table (#81 lookup-query normaliser).

#75 shipped the kana *variant* table, generated from JMdict's own kana cross
references. This table is the class JMdict cannot reach: the historical sound
changes, which are rules over 旧仮名遣い rather than variant spellings of one word.
The pre-reform spelling is not a reading of the modern entry (JMdict lists きょう
for 今日, never けふ), so an external rule source is needed, and the one chosen
here is public domain by statute.

Source: the two Cabinet notices (内閣告示), published by the Agency for Cultural
Affairs (文化庁), which set the 旧仮名遣い -> 現代仮名遣い correspondences:

  昭和21年内閣告示第33号「現代かなづかい」(1946-11-16)
    語中・語尾のハ行の仮名は、ワ行の仮名に書き改める — は->わ ひ->い ふ->う
    へ->え ほ->お; 母音の変化 — アウ->オウ, エウ->ヨウ.
  昭和61年内閣告示第1号「現代仮名遣い」(1986-07-01)
    本文第1 (直音・拗音・促音・長音) and 本文第2 (表記の慣習による特例:
    助詞の「は」「を」「へ」はそのまま; 動詞の「いう（言）」は「いう」と書く;
    「ぢ」「づ」; オ列の長音は「お」). 第2 is where the grammatical conditions
    the normaliser applies come from.

Licence: 著作権法第13条第2号 (Copyright Act, Article 13, item 2) — 国…の機関が
発する告示…は権利の目的となることができない. No licence text ships for this
component; the notice beside the asset and the index row are the attribution.

The rows below are the correspondences; the *conditions* (which ハ行 kana may fold
at which ending, which row members are withheld) are in the Kotlin normaliser and
in tools/kana_sound_changes_bench.py, and each withholding is measured on the
#75 bench slice (app/src/test pins the pairs and the behaviour).

    python3 tools/build_kana_sound_changes.py \\
        --out-dir app/src/main/assets/variants \\
        --check や:よ け:きょ ふ:う ひ:い へ:え は:わ \\
        --reject あ:お ま:も ほ:お を:お
"""
import argparse
import hashlib
import sys
from datetime import date
from pathlib import Path

SOURCE_NOTICES = [
    "昭和21年内閣告示第33号「現代かなづかい」 (1946-11-16)",
    "昭和61年内閣告示第1号「現代仮名遣い」 (1986-07-01)",
]
SOURCE_URLS = [
    "https://www.bunka.go.jp/kokugo_nihongo/sisaku/joho/joho/kijun/naikaku/gendaikana/index.html",
    "https://www.bunka.go.jp/kokugo_nihongo/sisaku/joho/joho/kijun/naikaku/gendaikana/honbun_dai1.html",
    "https://www.bunka.go.jp/kokugo_nihongo/sisaku/joho/joho/kijun/naikaku/gendaikana/honbun_dai2.html",
]
LICENSE = ("Public domain — 著作権法第13条第2号 (Japanese Copyright Act, Article 13, "
           "item 2: 告示は権利の目的とならない)")

# Row `ha`: 語中・語尾のハ行 -> ワ行 (KanaSoundChanges applies each with the ending
# its 活用 demands; see HI/HE/HA conditions in the Kotlin).
HA = [("ふ", "う"), ("ひ", "い"), ("へ", "え"), ("は", "わ")]
# Row `au`: アウ -> オウ, applied before う. あ and ま are withheld — measured on the
# bench slice: ま repaired nothing and cost 3 modern readings (しまう, 舞う); あ
# repaired nothing and cost 1 (会う). The other rows pay for themselves or cost 0.
AU = [("か", "こ"), ("さ", "そ"), ("た", "と"), ("な", "の"), ("は", "ほ"),
      ("や", "よ"), ("ら", "ろ"), ("わ", "お"), ("ゃ", "ょ"),
      ("が", "ご"), ("ざ", "ぞ"), ("だ", "ど"), ("ば", "ぼ"), ("ぱ", "ぽ")]
# Row `eu`: エウ -> ヨウ, applied before う (けふ -> けう -> きょう).
EU = [("え", "よ"), ("け", "きょ"), ("せ", "しょ"), ("て", "ちょ"), ("ね", "にょ"),
      ("へ", "ひょ"), ("め", "みょ"), ("れ", "りょ"), ("げ", "ぎょ"), ("ぜ", "じょ"),
      ("で", "じょ"), ("べ", "びょ"), ("ぺ", "ぴょ")]

# Row members deliberately withheld, with the measured reason. を is withheld by
# #75 already (the accusative particle; measured in tools/wopro_bench.py).
EXCLUDED = {
    ("あ", "お"): "au row: 0 headwords repaired on the legacy slice, +1 modern false positive (会う)",
    ("ま", "も"): "au row: 0 headwords repaired on the legacy slice, +3 modern false positives (しまう, 舞う)",
    ("ほ", "お"): "ha row: 0 headwords repaired on the legacy slice (おほ->おお); no ending condition ships, so the pair would be inert",
    ("を", "お"): "withheld by #75: the modern accusative particle (measured in tools/wopro_bench.py)",
}

ROWS = [("ha", HA), ("au", AU), ("eu", EU)]


def build_lines():
    lines = [
        "# Historical kana sound-change table (#81) — vendored, do not edit by hand.",
        "# variant<TAB>modern<TAB>row: 旧仮名遣い -> 現代仮名遣い.",
        "# Rows: ha ハ行転呼, au アウ->オウ, eu エウ->ヨウ.",
        "# Source and licence: kana_sound_changes.PROVENANCE.txt beside this file;",
        "# regenerate with tools/build_kana_sound_changes.py.",
    ]
    body = [f"{variant}\t{modern}\t{row}"
            for row, pairs in ROWS
            for variant, modern in sorted(pairs)]
    return "\n".join(lines + body) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--retrieved", default=date.today().isoformat())
    ap.add_argument("--check", nargs="*", default=[],
                    help="variant:modern pairs that must be present")
    ap.add_argument("--reject", nargs="*", default=[],
                    help="variant:modern pairs that must be absent")
    args = ap.parse_args()

    pairs = {}
    for row, members in ROWS:
        for variant, modern in members:
            if (variant, modern) in EXCLUDED:
                continue
            pairs[(variant, modern)] = row

    bad = []
    for spec in args.check:
        variant, modern = spec.split(":")
        if (variant, modern) not in pairs:
            bad.append(spec)
        else:
            print(f"  {spec} <- row {pairs[(variant, modern)]}")
    for spec in args.reject:
        variant, modern = spec.split(":")
        if (variant, modern) in pairs:
            print(f"{spec}: present but required ABSENT", file=sys.stderr)
            bad.append(spec)
    if bad:
        for spec in bad:
            print(f"{spec}: check failed", file=sys.stderr)
        raise SystemExit(1)
    if args.check or args.reject:
        print(f"checked {len(args.check)} required (present) and "
              f"{len(args.reject)} rejected (absent) pairs")

    text = build_lines()
    data = text.encode("utf-8")

    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    (out / "kana_sound_changes.txt").write_bytes(data)

    counts = {row: sum(1 for v, m, r in (line.split("\t") for line in text.splitlines()
                                         if line and not line.startswith("#")) if r == row)
              for row, _ in ROWS}
    prov = (
        "Historical kana sound-change table — vendored, do not edit by hand.\n\n"
        f"Entries    : {len(pairs)} pairs (ha {counts['ha']}, au {counts['au']}, eu {counts['eu']})\n"
        f"File       : kana_sound_changes.txt\n"
        f"Size       : {len(data)} bytes\n"
        f"SHA-256    : {hashlib.sha256(data).hexdigest()}\n\n"
        f"Source     : {'; '.join(SOURCE_NOTICES)}\n"
        f"Retrieved  : {args.retrieved}\n"
        f"Licence    : {LICENSE}\n"
        "Online     :\n" + "".join(f"             {u}\n" for u in SOURCE_URLS) +
        "\n"
        "What it is : the 旧仮名遣い -> 現代仮名遣い sound changes the #75 kana-variant\n"
        "             table structurally cannot carry. JMdict's readings are modern kana\n"
        "             usage; the pre-reform spelling is not a reading of the modern entry\n"
        "             (けふ and きやう are absent from JMdict), so the rule source is the\n"
        "             two Cabinet notices above. The asset lists only the correspondences;\n"
        "             the grammatical conditioning lives in KanaSoundChanges.kt and in\n"
        "             tools/kana_sound_changes_bench.py.\n\n"
        "Rows       : ha ハ行転呼 ふ->う ひ->い へ->え は->わ, each applied only at the\n"
        "             ending its 活用 demands (終止/連体 for ふ, 連用 for ひ, 連用/仮定/\n"
        "             已然 for へ, 未然+ず/ぬ/む/ば for は; the particle は never folds).\n"
        "             au アウ->オウ before う; eu エウ->ヨウ before う (けふ->きょう).\n"
        "             Hiragana only: katakana rows are out of scope because the same\n"
        "             change rewrites modern loanwords (クラウン, タウン), and the legacy\n"
        "             bench slice is hiragana; #75's table keeps its katakana pairs.\n\n"
        f"Withheld ({len(EXCLUDED)} row members, measured on the #75 bench slice):\n"
        + "".join(f"  {v} -> {m}\t{EXCLUDED[(v, m)]}\n" for v, m in sorted(EXCLUDED)) +
        "\nGenerated by tools/build_kana_sound_changes.py — rerun rather than editing.\n"
    )
    (out / "kana_sound_changes.PROVENANCE.txt").write_text(prov, encoding="utf-8")

    print(f"wrote {out / 'kana_sound_changes.txt'} ({len(pairs)} pairs, {len(data)} bytes)")


if __name__ == "__main__":
    main()
