# Experiments — what was tried, and why it lost

Short records of approaches that were built, evaluated, and removed.
This file exists so they don't get re-proposed without new evidence.
Ordered roughly by pipeline stage.

## Reading the display

### Glyphs harvested from the shot-list panel (superseded)

The alphabet the reader matches against has to come from somewhere, and
for a long time the corpus didn't hold enough Cell D examples to cover
ten digits — a handful of captures, most of them the same shot numbers.
The workaround: harvest from the *shot-list panel* (Cell C), where one
frame shows ten to twenty rows and the row sequence labels itself. It
worked, and it scaled absurdly well — one 14-photo session yields ~300
labeled instances against ~30 from the same session's Cell D.

It was still the wrong region. The list panel draws the same characters
at a smaller render size against different neighbours, so its templates
are a distribution mismatch for the region actually read. Once the
2026-08-01 session gave the corpus 0–9 and P *in Cell D*, the comparison
was blunt: 30 right-region glyphs matched the 348-glyph list alphabet on
corpus accuracy (19/19 either way, and both read the degraded
`field-…-misread` benchmark correctly) at **9× lower match cost** —
1.2 ms vs 10.9 ms per cache miss, since every instance is scored on
every read.

Lesson: **sample the distribution you will actually see.** A large,
cheaply-labeled proxy set is worth less than a small set from the right
region — and it is not free, because template count is linear in read
cost. The harvesters are still in `GlyphAlphabetTools` (the list-panel
trick is the right answer again the moment a new display's Cell D
coverage is thin), but `harvestCellD` is what builds the shipped
alphabet.

### ML Kit OCR (removed)

The obvious Android choice: small bundled model, hardware-accelerated,
on-device. Lost for structural reasons, not tuning ones:

- It answers a harder question than we ask ("what text is in this
  photograph?") — our pipeline already removed font/layout/perspective
  variance before the read, so its capability was aimed at problems we
  don't have, while its language prior stayed free to nudge ambiguous
  glyphs toward letters.
- **The decoder can't be constrained** — no character allowlist, no
  segmentation hints, no engine choice. Every ambiguity resolves over ~90
  characters; each non-digit resolution became a dropped read.
- Our hard-binarized crops are out-of-distribution for a photo-trained
  model.

Field A/B on the range confirmed it read worse than Tesseract on the same
input.

### Tesseract OCR (removed)

Won the engine-vs-engine fight, then lost to no-engine-at-all. The
decisive experiment: misreads of *clean binarized digits* (8→3, 9→0)
turned out to be a **decoder** problem — Tesseract's LSTM ignores the
character whitelist and free-runs its text priors. Forcing the legacy
engine (which honors `tessedit_char_whitelist=0123456789.`) on clean
digit crops went 80/110 → 110/110 on a sweep. Lesson: **for closed
worlds, constraint is capability** — a decoder that can't emit a letter
beats a more capable one that can.

Accumulated engine-era tuning, kept here in case an engine ever returns:
PSM 7 over PSM 8 (PSM 8 drops narrow leading digits), never blur LED
text (a 3×3 blur ate decimal points), decimal-inference repair
("76" → "7.6" is unambiguous given the 0.0–10.9 range), a dark-pixel
gate before reading (Otsu inside an engine hallucinates digits from
blank-cell noise), 12 px quiet borders, per-region upscales.

Removed because every job it had was taken: the score became geometric,
Cell G retired, and the shot number was already glyph-matched.
Costs recovered: ~31 MB of APK (traineddata + natives), a JNI dependency,
the test suite's only environment gate. The winner — NCC template
matching against eleven harvested real-display glyph shapes — is the
constraint-is-capability lesson taken to its endpoint: the reader cannot
imagine anything it hasn't seen.

### Ensemble consensus reads (removed with the engines)

At cache-miss, Cell D was read 5× over ±2 px jittered crops with
majority vote — insurance against one unlucky engine read. Sound idea,
obsolete premise: the glyph matcher is deterministic, so re-reading the
same pixels five times returns the same answer five times.

### Cell G arrows-first reader (removed)

Read the SIUS's mean-point-of-impact cell (`MTP ←7.4 ↑4.0`, averaged
over the last 5 shots) by template-matching the four arrow glyphs and
OCR-ing the number regions their positions define. It worked — 4/4 on
the corpus — and was retired anyway: nothing downstream consumed it, and
the pistol-match screen doesn't show Cell G at all.

The original rationale also said the aggregate was one "the app doesn't
need". That part was wrong — a group average is exactly what a shooter
adjusts sights from, and it is what a volunteer walks over to deliver
every few shots. The conclusion survives for a better reason: the app
already owns every shot's measured offset, so `ShotQueries.averageErrorText`
computes the group from its own data. No OCR, no decimal-point glyph, and
it works on screen modes that don't draw Cell G at all — strictly better
than reading the display's version of a number we can derive.

## Shot detection

### Pixel-based shot events (removed after field testing)

The target-as-truth idea taken one step too far: detect new shots from
*pixel changes* of the shot-index sub-region (mean-subtracted gray-block
descriptor, N-frame confirmation dwell) instead of reading the number —
"we trust the number on the display, it's only the OCR of it we don't
trust." Shipped behind a toggle, default-on, and real field sessions
killed it: a defocused or briefly-averted camera makes the shot region
"changed pixels", which *confirms as a phantom shot* — while a glyph
read must parse to a plausible number and otherwise degrades to
null-and-hold. Appearance change has no semantic gate; parse-or-null is
one. The drift-robust descriptor tuning (8×8 grid after warm-refine
corner drift defeated 16×16) held fine — the failure was conceptual, not
parametric. Don't re-propose pixel-driven shot events without a semantic
gate; note that "gate events on a successful glyph match of the changed
region" is exactly what the production reader-driven path already is.

The sighting-triangle detector (reading SIUS's practice-phase marker
from the TARGET corner) existed to give pixel mode its "P" flag and was
removed with it — in the reader-driven path the glyph read carries P.

## Green-dot detection and scoring

### Contour + circularity detection (rejected)

Find the most circular green blob. Failed on real images: the dot has
the white shot number rendered *on top of it*, punching holes in the
mask — the contour is ragged and circularity tanks. Replaced by
centroid-of-all-green-pixels (worked only when cropped tightly), then by
the production shape: connected components + a size band derived from
the dot's known radius ratio + a singularity rule (two candidates = 
something is wrong = refuse). Lesson that outlived the experiment:
**encode domain priors as hard gates, not heuristics** — gates
correspond to physical facts; heuristics encode intuitions that don't
survive structural quirks like text-on-marker.

### Dot-radius subtraction in scoring (rejected)

Score from `centroid_distance − dot_radius`, on the assumption the dot
renders the bullet's physical footprint. The corpus fit exposed it: one
case implied a wildly outlying ring-spacing ratio (0.009 vs the 0.027–
0.031 cluster). Removing the subtraction collapsed the variance — the
dot is a fixed-size *marker*, not a rendered bullet hole. When the
display itself reports the authoritative numbers elsewhere, treat drawn
elements as presentation, not physics.

### Fitted constants → measured geometry (superseded, kept as fallback)

Scoring originally used a corpus-fitted centre + `ringSpacingRatio`
constant, which bakes one average rectification into every frame — the
one extreme-angle corpus case implied a ratio ~17% off the mean.
`TargetGeometryEstimator` replaced constants with per-frame measurement
of the black aiming disc (chord-profile method; see PIPELINE.md §6). The
constants survive as the fallback for frames where the disc can't be
measured.

## Orchestration and infrastructure

### `ScoreReader` — the shared frame-loop driver (removed)

Built as the one per-frame orchestrator (frames in → cells → diff →
announce) that Android and the desktop harness would share. Never
adopted: the real loops legitimately differ in what they interleave
(camera lifecycle, capture writing, overlay state), and the green-dot
decision obsoleted its extract-and-read premise. Its scaffolding
(`Calibration`, `FrameSource`) existed only to feed it and went with it.
If a shared driver ever becomes worth it, grow it out of
`FrameAnalyzer` — the only loop where the green-dot path and
`ShotTracker` actually meet.

### Synthetic display generator + series simulator (removed)

Rendered artificial SIUS screens (known geometry, known values,
controllable noise/perspective/corner-drift) and ran simulated shot
series through the real pipeline — zero-labeling-cost ground truth that
de-risked the border refiner and the shot-tracking confirmation gates.
Removed once those algorithms shipped: real captures are the corpus now,
and the generator's base template photo carried personal data the public
repo must not.

### Gray-block change descriptor (built, never wired up, removed)

A finer cell-change descriptor than the `dHash` the read cache uses:
downsample to 16×16 gray, subtract the mean, count cells differing by
more than a threshold. The motivation was measured — a dHash's 64
gradient-sign bits cannot reliably see a single small digit change
(sensor noise flips 0–3 bits, a digit change flips 1–4; the ranges
overlap). Intensities separate cleanly instead: noise averages to ~1
gray level per downsampled cell while a digit stroke shifts its cells by
dozens, and mean subtraction keeps the dHash's robustness to global
brightness.

Removed unused: nothing ever called it. The read cache's dHash operates
on whole cell crops, where content changes are large enough that the
overlap never bit. Worth rebuilding from this note if a future consumer
needs sub-glyph change detection.

### USB camera (dropped early)

Considered as the frame source before settling on the phone's built-in
rear camera via CameraX. Dropped: one device, no cabling, no extra
hardware to set up at the range — the phone camera is strictly simpler.

### A previous incarnation of this project (abandoned)

Shipped MVI + middleware + five coordinate spaces + sealed hierarchies
for everything — and OCR still didn't work. The standing rule that came
out of it: pure functions + data classes + the simplest interface that
lets a test be written; abstract when the second concrete implementation
exists, not before.
