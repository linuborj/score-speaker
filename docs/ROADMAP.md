# Roadmap

Things we might do, roughly ordered. (What was tried and removed is in
[EXPERIMENTS.md](EXPERIMENTS.md).)

## Next

- **Verify the zoom lens-switch on a real device.** The zoom control
  itself is *done* — `AppSettings.zoomRatio`, a Settings slider, pinch-to-zoom
  on the live screen (which edits the same persisted value), and
  `setZoomRatio` clamped to the device's range on bind and on change. What was never checked in the field is the one-frame jump as
  a multi-camera phone crosses ~2× and hands over to the telephoto: the
  pipeline should absorb it (refine fails → full re-detect), but that is
  an assumption, not an observation.
- **More miss samples.** The off-target badge detector ships on a sample
  of **two** misses, from one session on one display. The size and rim
  bands are set generously and the glyph gate is strong, but the bands
  want re-checking against a wider set — `GreenBlobSurvey` prints the
  numbers that set them. Until then it sits behind `announceMisses`.
- **Analyzer resolution modes (1080p vs 4K).** Source resolution sets how
  many pixels every measurement gets; a 4K mode buys *precision* —
  sharper dot centroid, finer corner refinement, more matchable glyph
  detail. Shape: two discrete modes, each with its own tuned constant
  profile, as a Settings segmented control. Surface the resolution
  CameraX actually granted, and mind that lower fps stretches the
  frame-counted confirmation gates. Now that zoom has shipped, the open
  question is whether optical magnification already covers the need — a
  field comparison at a useful zoom ratio should come before this work.
- **Cap the warm-branch work rate.** Nothing in the app expresses a frame
  rate — with `KEEP_ONLY_LATEST` the warm loop runs at whatever the device's
  stream and CPU allow (~30 fps on an FP4), so a faster phone spends the
  headroom on more redundant passes instead of banking it. Shooting cadence
  makes nearly all of it waste: a shot stands on the display until the next
  supersedes it, and the shooter has to reload and re-aim, so ~10 fps is
  ample to notice one. The payoff is more than battery — pinning the rate
  gives `ShotTracker.confirmationFrames` and `ChangeTracker.nullHoldThreshold`
  a fixed wall-clock meaning rather than one that varies per device. Must be
  time-based (every-Nth-frame just inherits the device rate) and warm-only:
  COLD already self-limits to 2–3 fps at ORB's own cost and is the state
  where latency matters. The risk to measure first is the refiner's tracking
  band — slower sampling multiplies the displacement it must absorb between
  frames, so log how often 10 fps drops to COLD versus 30 before fixing a
  number.
- **Multi-screen-mode `CellLayout`.** The SIUS shows a different layout
  during pistol matches; the hardcoded per-shot ratios put Cell D and
  TARGET in the wrong places there. Detect the screen mode, dispatch to
  the right layout. As variety grows, the debug overlay should show
  *derivation* (measured vs fallback centre), not just position.
- **Voice-prompted queries.** The app only broadcasts; a shooter should
  be able to *ask* ("last score?", "average?", "sum?"). On-device
  `SpeechRecognizer`, small keyword grammar, answers through the
  announcer at HIGH priority. The hardware-key bindings already cover
  the two most-wanted queries.
- **Drift detection as a spoken signal.** Setup is a sighted helper's
  job; the gap is mid-session drift (tripod bump, lighting) after the
  helper leaves. Detect degraded reading (detection confidence, glyph
  parse rate) and say "phone needs re-calibrating" — a clear signal, not
  auto-recovery. Behind a toggle until field sessions prove the signal;
  a false alarm interrupts shooting.
- **Corpus anonymisation (desktop).** Cell H carries the shooter's name,
  which is what keeps range photos out of the repo. Blanking it from the
  desktop app would make a case shareable. Two details decide whether it
  actually works: redact **`source.png`**, not just the rectified view —
  the source frame is the file that travels and it holds the whole
  display; and **refuse rather than guess** when detection fails, since
  an unlocated Cell H means the naive implementation writes an
  un-redacted frame. Cell H is already a ratio-defined region, so
  locating it is one homography away, and blanking a known rectangle
  beats detecting text — nothing can be missed that wasn't recognised.
  Automate that invariant; leave the rare, irregular case (a neighbouring
  lane's display creeping into frame) to a human look before publishing.
  The prize is a publishable corpus tier — currently the one gap in the
  open-source story, since corpus tests skip on a fresh clone and nobody
  else can run the suite.
- **History polish.** Off-main-thread reads, more summary stats (mostly
  feeders for voice queries), `.sqm` migrations once the schema is
  declared stable.
