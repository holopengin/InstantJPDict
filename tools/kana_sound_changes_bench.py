#!/usr/bin/env python3
"""#81 measurement: historical kana sound changes on the #75 bench slice.

Reuses #75's corpus and metric (2,000 Aozora lines, 350 of them 旧仮名, era from
`docids_aozora.json`; headwords are the imported dictionary's 465,525 distinct
keb|reb strings from JMdict). Two views, because two questions were asked:

View A — the #75 harness family. Normalise the line with #75 alone and with #75 +
sound changes; at the positions the sound-change pass changes, ask whether a
headword covers the position. `replacement` is the normalised string alone,
`union` is raw OR normalised (what the app searches, and the app also keeps the
raw and #75 forms, so `union` destroyed is 0 by construction).

View B — the tap view, mirroring `prepareSearchCandidates`. For each tap position
the app builds candidates from the prefixes of the following text and searches
each folded form. A headword is reachable iff it equals one of those candidate
strings. `restored` counts headwords the sound-change pass adds that were not
reachable without it; on the modern slice those are false positives, reported
rather than hidden.

The normaliser here mirrors `KanaOrthography.modernise` (#75) and
`KanaSoundChanges.modernise` (#81) including the grammatical conditions; the pair
table is read from the committed asset, so the table itself is measured, not a
copy. Deinflection is not modelled, as in #75's harness.

Usage:
    python3 tools/kana_sound_changes_bench.py \\
        --bench /tmp/kana_bench2 --jmdict /tmp/JMdict_e.xml --ablate
"""
import argparse
import collections
import difflib
import json
import re
import sys

HIRA = lambda c: '\u3041' <= c <= '\u3096'
KATA = lambda c: '\u30a1' <= c <= '\u30f6'
KANA = lambda c: HIRA(c) or KATA(c)
KATA2HIRA = {chr(0x30A1 + i): chr(0x3041 + i) for i in range(0x56)}

# --- #75 mirror (KanaOrthography) -------------------------------------------
WAGYOU = {'ゐ': 'い', 'ゑ': 'え', 'ヰ': 'イ', 'ヱ': 'エ'}
DAKUGYOU = {'ぢ': 'じ', 'づ': 'ず', 'ヂ': 'ジ', 'ヅ': 'ズ'}
SOKUON = {'つ': 'っ', 'ツ': 'ッ'}
YOON = {'や': 'ゃ', 'ゆ': 'ゅ', 'よ': 'ょ', 'ヤ': 'ャ', 'ユ': 'ュ', 'ヨ': 'ョ'}
DAKUTEN_PREV = set('つちツチ')
SOKUON_TRIGGERS = set('たちつてとさしすせそぱぴぷぺぽタチツテトサシスセソパピプペポ')
YOON_BASE = set('きしちにひみりぎじびぴキシチニヒミリギジビピ')


def modernise75(text):
    out = []
    for i, c in enumerate(text):
        prev = text[i - 1] if i else None
        nxt = text[i + 1] if i + 1 < len(text) else None
        if c in WAGYOU:
            out.append(WAGYOU[c]); continue
        if c in DAKUGYOU and prev not in DAKUTEN_PREV:
            out.append(DAKUGYOU[c]); continue
        if c in SOKUON and nxt in SOKUON_TRIGGERS:
            out.append(SOKUON[c]); continue
        if c in YOON and prev in YOON_BASE:
            out.append(YOON[c]); continue
        out.append(c)
    return "".join(out)


# --- #81 mirror (KanaSoundChanges) ------------------------------------------
PARTICLES = set('はがのにをともやかぞなよねだでどばへこそしかまでより')
HI_SUFFIX = set('てつな')
HE_SUFFIX = set('したてばどきけるれりま')
HA_SUFFIX = set('ずぬむば')


class SoundChanges:
    def __init__(self, pairs):
        self.ha, self.au, self.eu = {}, {}, {}
        for variant, modern, row in pairs:
            if row == 'ha' and len(modern) == 1:
                self.ha.setdefault(variant, modern)
            elif row == 'au' and len(modern) == 1:
                self.au.setdefault(variant, modern)
            elif row == 'eu':
                self.eu.setdefault(variant, modern)

    def _ha(self, text):
        out = []
        for i, c in enumerate(text):
            mapped = self.ha.get(c)
            if mapped is None:
                out.append(c)
                continue
            prev = text[i - 1] if i else None
            nxt = text[i + 1] if i + 1 < len(text) else None
            if c == 'ふ':
                fire = prev is not None and prev != 'う' and (
                    nxt is None or not KANA(nxt) or nxt in PARTICLES)
            elif c == 'ひ':
                fire = nxt is not None and nxt in HI_SUFFIX
            elif c == 'へ':
                fire = nxt is not None and nxt in HE_SUFFIX
            elif c == 'は':
                fire = prev is not None and nxt is not None and nxt in HA_SUFFIX
            else:
                fire = False
            out.append(mapped if fire else c)
        return "".join(out)

    def _vowels(self, text):
        out = []
        i = 0
        while i < len(text):
            c = text[i]
            nxt = text[i + 1] if i + 1 < len(text) else None
            if nxt == 'う':
                if c in self.au:
                    out.append(self.au[c] + 'う')
                    i += 2
                    continue
                if c in self.eu:
                    out.append(self.eu[c] + 'う')
                    i += 2
                    continue
            out.append(c)
            i += 1
        return "".join(out)

    def modernise(self, text):
        return self._vowels(self._ha(text))


def after75(text, changes):
    return changes.modernise(modernise75(text))


def kata_to_hira(s):
    return "".join(KATA2HIRA.get(c, c) for c in s)


def load_pairs(path):
    pairs = []
    for raw in open(path, encoding='utf-8'):
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        parts = line.split('\t')
        if len(parts) == 3:
            pairs.append((parts[0], parts[1], parts[2]))
    return pairs


def headwords(path):
    text = open(path, encoding='utf-8').read()
    return (set(re.findall(r"<keb>([^<]+)</keb>", text))
            | set(re.findall(r"<reb>([^<]+)</reb>", text)))


def covers(text, j, words, lo=2, hi=8):
    for a in range(max(0, j - hi + 1), j + 1):
        for b in range(j + 1, min(len(text), a + hi) + 1):
            if b - a >= lo and text[a:b] in words:
                return True
    return False


def reachable(text, j, words, moderniser, span=20):
    p = text[j:j + span]
    cands = set()
    for ln in range(1, len(p) + 1):
        q = p[:ln]
        for v in (q, moderniser(q), kata_to_hira(moderniser(q))):
            cands.add(v)
    return cands & words


def load_bench(bench_dir):
    doc = json.load(open(f"{bench_dir}/docids_aozora.json", encoding='utf-8'))
    recs = {}
    for line in open(f"{bench_dir}/records.jsonl", encoding='utf-8'):
        r = json.loads(line)
        recs[(r["id"], r["mode"])] = r
    man = [json.loads(l) for l in open(f"{bench_dir}/manifest.jsonl", encoding='utf-8')]
    return doc, recs, man


def era_of(doc_entry, rec):
    era = doc_entry.get("era", "")
    if "旧仮名" in era:
        return "legacy"
    if era == "新字新仮名":
        return "modern"
    return None


def view_a(words, doc, recs, man, changes, field):
    st = collections.defaultdict(collections.Counter)
    for m in man:
        d = doc.get(m["id"])
        if not d:
            continue
        key = era_of(d, recs.get((m["id"], "vertical")))
        if key is None:
            continue
        rec = recs.get((m["id"], "vertical"))
        if rec is None:
            continue
        text = rec.get(field) or ""
        before, after = modernise75(text), after75(text, changes)
        if before == after:
            continue
        for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(
                None, before, after, autojunk=False).get_opcodes():
            if tag == 'equal':
                continue
            for j in range(i1, i2):
                b, a = covers(before, j, words), covers(after, j, words)
                t = st[key]
                t["n"] += 1
                t["rep_restored"] += (not b) and a
                t["rep_destroyed"] += b and (not a)
                t["union_restored"] += (not b) and (b or a)
                t["union_destroyed"] += b and not (b or a)
    return st


def view_b(words, doc, recs, man, changes, field):
    st = collections.defaultdict(collections.Counter)
    seen = collections.defaultdict(set)
    for m in man:
        d = doc.get(m["id"])
        if not d:
            continue
        rec = recs.get((m["id"], "vertical"))
        if rec is None:
            continue
        key = era_of(d, rec)
        if key is None:
            continue
        text = rec.get(field) or ""
        t = st[key]
        for j in range(len(text)):
            p = text[j:j + 20]
            if after75(p, changes) == modernise75(p):
                continue
            before = reachable(text, j, words, modernise75)
            after = reachable(text, j, words, lambda q: after75(q, changes))
            added = (after - before) - seen[key]
            seen[key] |= (after - before)
            t["taps_fired"] += 1
            t["restored"] += len(added)
            t["taps_gain"] += bool(added)
    return st


def show(name, sa, sb):
    print(f"\n=== {name} ===")
    print("  view A (changed positions; #75 harness family)")
    for era in ("legacy", "modern"):
        t = sa[era]
        print("    %-7s changed=%-5d repl +%-4d/-%-4d  union +%-4d/-%-4d"
              % (era, t["n"], t["rep_restored"], t["rep_destroyed"],
                 t["union_restored"], t["union_destroyed"]))
    print("  view B (taps, prefix candidates)")
    for era in ("legacy", "modern"):
        t = sb[era]
        print("    %-7s fired=%-5d taps_gain=%-4d restored=%-5d"
              % (era, t["taps_fired"], t["taps_gain"], t["restored"]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bench", default="/tmp/kana_bench2")
    ap.add_argument("--jmdict", default="/tmp/JMdict_e.xml")
    ap.add_argument("--asset", default="app/src/main/assets/variants/kana_sound_changes.txt")
    ap.add_argument("--field", default="gt", choices=["gt", "pred"])
    ap.add_argument("--ablate", action="store_true",
                    help="also run each row disabled and each withheld pair enabled")
    args = ap.parse_args()

    words = headwords(args.jmdict)
    doc, recs, man = load_bench(args.bench)
    pairs = load_pairs(args.asset)
    changes = SoundChanges(pairs)
    print(f"headwords (keb|reb): {len(words)}; sound-change pairs: {len(pairs)}; field={args.field}")

    show("shipped table", view_a(words, doc, recs, man, changes, args.field),
         view_b(words, doc, recs, man, changes, args.field))

    if args.ablate:
        for row in ("ha", "au", "eu"):
            c = SoundChanges([p for p in pairs if p[2] != row])
            sa = view_a(words, doc, recs, man, c, args.field)
            sb = view_b(words, doc, recs, man, c, args.field)
            show(f"without row {row}", sa, sb)
        # The withheld pairs, put back one at a time: the measurement behind the
        # exclusion (legacy repairs vs modern false positives in view B).
        for variant, modern, row in [("あ", "お", "au"), ("ま", "も", "au"),
                                     ("ほ", "お", "ha"), ("を", "お", "au")]:
            c = SoundChanges(pairs + [(variant, modern, row)])
            sb = view_b(words, doc, recs, man, c, args.field)
            print(f"\n=== withheld {variant}->{modern} ({row}) enabled, view B only ===")
            for era in ("legacy", "modern"):
                t = sb[era]
                print("    %-7s fired=%-5d taps_gain=%-4d restored=%-5d"
                      % (era, t["taps_fired"], t["taps_gain"], t["restored"]))


if __name__ == "__main__":
    sys.exit(main())
