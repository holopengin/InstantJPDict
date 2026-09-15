# Blank surfacing (#44) — the measured path that is not shipped

**Status: removed from the code in #86** (the A5 item of the 2026-09-15 audit).
This file is the measurement record; the repo keeps its negative results rather
than only its verdicts (compare `docs/85-keystone-fit-findings.md`).

## What was tried

PP-OCR's CTC head emits **blank** for a character it cannot name — an OOV kanji,
a dropped `。` `、` `ー`. "Blank surfacing" put a candidate character back at such
a timestep, decided from the per-timestep distribution alone.

Measured over 218 paired bench lines (`trails` + `vert_large`):

| quantity | value |
|---|---|
| dropped characters (deletions) | 17 |
| genuine gaps between emitted characters | 6,548 |
| deletions that are a **contest** (a confident non-blank alternative just below blank, e.g. `blank 0.42 / candidate 0.98`) | 5 — surfacing right about 60% of the time |
| deletions that are a **confident blank** (`blank ≈ 0.999`, best alternative ≤ 0.01) | 12 — no threshold reaches these; they need external evidence (dictionary/reading context), not a decode rule |

The rule was `candidateProb >= 0.5 && candidateProb > blankProb`. It fires on the
5 contests and on 3 of the 6,548 ordinary gaps (0.05%). Recoveries and spurious
insertions were roughly equal in number, so enabling it is **error-neutral on the
bench** — which is why the shipped default was `DISABLED` and no user-visible
control ever existed.

## The two decoders disagreed

- The live decode called `BlankRecovery.shouldSurface` (a probability rule).
- The cached re-decode used an unrelated distance-to-blank formula,
  `1 / (1 + |blankScore - sc|)`, against the same threshold.
- The native rec path deliberately returns **logits**, not probabilities
  (`ppocr_ncnn_core.cpp`: "scores become logits, which no consumer reads
  absolutely"), so the "probability" parameters were comparing logits.

That is three value domains for one policy, and the only caller of the cached
path (`OcrOverlayStateController.refreshLinesWithThreshold`) had no production
call site at all: every live decode passed `blankThreshold = 0f` (pure greedy).

## What ships instead

The deletions are handled by the shipped #44 work, which uses evidence outside
the timestep distribution: the clickable blank for manual IME entry, and the
component/char-LM candidate list in the alternatives panel. The greedy decode
(`OcrEngine.ctcDecode` / `ctcDecodeTopK`) is unchanged from what the device
already ran, and `OcrEngine.reDecodeLineResult` remains as the cached re-decode
the gap detector's collapse rules are written against — both now with no
threshold parameter to disagree about.
