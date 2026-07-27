# SIUS Display

Reference for the electronic scoring display this project reads: an LCD
monitor in a black plastic bezel, white grid lines dividing it into
cells. Cell letters match `CellLayout.siusDisplayCells()` — the code is
authoritative.

```
┌──────────────────────────────────────────────────────────────────┐
│ A: Tavla/Bana 9               │ B: 17.12.2025 15:28              │
├───────────────────────────────┼──────────────────────────────────┤
│                               │ C: Tävling                       │
│                               │    1← 7.6 P                      │
│   TARGET: the rings graphic — │    2← 6.9 P                      │
│   the green latest-shot       │                                  │
│   marker and the black        ├──────────────────────────────────┤
│   aiming disc                 │ D:  1P  7.6                      │
│                               ├──────────────────────────────────┤
│                               │ E: P- E10 E10                    │
│                               ├──────────────────────────────────┤
│                               │ F: KLAR                          │
├──────────────────┬────────────┼──────────────────────────────────┤
│ H: 11 A Shooter  │ G: MTP ←7.4 ↑4.0                              │
│ Air Rifle 20…    │   (mean point of impact, last 5 shots)        │
└──────────────────┴───────────────────────────────────────────────┘
```

## What the app uses

- **Cell D, shot region** — the only thing *read*: `<1–2 digits>[P]`,
  shot 1–60, `P` = Provskott (practice). Parsed by
  `ScoreShotValueParser`. The big green score digits beside it are never
  read — they only feed the presence gate.
- **TARGET** — everything else is *measured* here: the green marker's
  centroid gives score and aim offset; the black aiming disc gives
  centre and ring spacing.

## How the display draws a shot

- **On the face** — the latest shot is a solid green disc with the shot
  number in white on top of it, drawn where the pellet landed. Earlier
  shots stay as black discs with white numbers.
- **Off the face (a miss)** — *not* omitted. The shot becomes a small
  green badge pinned to the rim of the TARGET cell, carrying the shot
  number in **dark** digits, positioned in the direction the shot left
  the target (bottom-left badge = went out low and left) with a small
  arrow. Cell D's score reads `0.0`. The badge turns black once the next
  shot supersedes it, like any past shot. Roughly half the linear size of
  an on-face marker — see [PIPELINE.md](PIPELINE.md) §6 for the measured
  separation.

## Cell C, the shot list

Rows are `<shot> <arrow> <score>`, oldest at the top, the current shot
last; the list scrolls once it fills. A `Delsumma <subtotal>` row is
drawn after every tenth shot, and a header ("Match", the discipline)
sits above the list until enough shots push it off. Since the rows are
consecutive and the last one is the current shot, the whole list is
self-labeling given Cell D — which is what the superseded list-panel
glyph harvest exploited (see [EXPERIMENTS.md](EXPERIMENTS.md)). Its
digits render smaller than Cell D's.

## Language

The display is Swedish: "Tavla" = target, "Bana" = lane, "Tävling" =
competition, "Provskott" = practice shot, "KLAR" = ready, "STOPP" = stop.

## Known variations

- Only one SIUS model observed; other models may differ in layout or
  font (the cell ratios and glyph alphabet would need per-model
  variants). Everything above — including the miss badge's size and rim
  position — is observed on that one model.
- A pistol-match summary screen exists with a different layout — the
  per-shot cell ratios don't fit it (see [ROADMAP.md](ROADMAP.md)).
