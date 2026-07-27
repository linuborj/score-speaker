<picture>
  <source media="(prefers-color-scheme: dark)" srcset="art/score-speaker-lockup-dark.svg">
  <img src="art/score-speaker-lockup.svg" alt="Score Speaker" width="380">
</picture>

Android app that reads electronic shooting-target scores from a SIUS display via a camera and announces them via text-to-speech — for shooters (especially visually impaired) who don't want to look away from their position to check the score.

The Android app is the product. The desktop app is a development harness — fast iteration on the vision algorithms against still images, plus a labeling GUI for the annotated photo corpus.

## What it does

- Point the phone's rear camera at the SIUS display; detection and
  lock-on are automatic — no calibration UI.
- Each shot is announced as it lands: shot number (read by glyph
  matching), score and aim offset (measured geometrically from the
  display's shot marker) — *"Shot 7, 9 point 8 — 11 o'clock."*
- Shots that miss the target are announced too — the display marks them
  at the edge rather than on the face — with the direction they went out:
  *"Shot 16, miss — 7 o'clock."*
- Shots persist on-device: sessions, per-shot scores, group plot,
  missed-shot gap notes.
- Works with TalkBack; speaks English and Swedish; bindable hardware
  keys for spoken queries (last shot, average error).
- Fully offline — the app does not hold the `INTERNET` permission.

## Quickstart

### Desktop harness

```bash
nix-shell   # or any JDK 17

# Labeling GUI
./gradlew :desktopApp:run

# Headless corpus run ($SCORE_SPEAKER_CORPUS, default ~/score-speaker-corpus)
./gradlew :desktopApp:run --args="--process-all --use-expected --ocr"

# Tests (corpus tests skip if the corpus is absent)
./gradlew :desktopApp:jvmTest
```

### Android

```bash
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew :androidApp:installDebug
```

Grant camera permission and aim so the whole display sits in the frame.
Debug builds add a pipeline overlay (detection HUD + cell crops) behind
a Developer setting, and a **Capture** button that dumps an annotatable
test case:

Captures are written one zip per shot, grouped by day:
`captures/<YYYY-MM-DD>/<HHmmss-SSS>-shot<N>-<hex>.zip`, each holding
`source.png`, `rectified.png`, `crops/D.png` and the read values. Unzip
each into its own corpus directory, then annotate with the labeling GUI:

```bash
adb pull /sdcard/Android/data/se.linusborjesson.scorespeaker/files/captures /tmp
for z in /tmp/captures/*/*.zip; do
  d=~/score-speaker-corpus/$(basename "$z" .zip)
  mkdir -p "$d" && unzip -q "$z" -d "$d"
done
```

The **Auto-capture** developer setting writes one of these per confirmed
shot, which is the practical way to build a corpus from a live session —
mind the free-space floor (it disables itself under 1 GB).

### Rebuilding the glyph alphabet

The shot reader matches against real glyphs harvested from annotated
captures. The harvest tools rewrite committed test data, so they only run
when asked:

```bash
SCORE_SPEAKER_HARVEST=1 ./gradlew :desktopApp:jvmTest \
  --tests '*GlyphAlphabetTools.harvestCellD'
cp -r desktopApp/test-data/glyphs/shot-font \
      androidApp/src/main/assets/glyphs/          # the product's own copy
./gradlew :desktopApp:jvmTest --tests '*GlyphAlphabetTools.evaluateHeldOut'
```

`evaluateHeldOut` is the number to trust: it reads each case with that
case's own contributed glyphs excluded.

Inspect the persisted shot history:

```bash
adb shell run-as se.linusborjesson.scorespeaker sqlite3 \
  databases/scorespeaker.db 'select shot_number, mode, score from shot;'
```

## Docs

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — principles, module layout, source-set graph, pipeline flow
- [docs/PIPELINE.md](docs/PIPELINE.md) — the vision pipeline end to end: each algorithm, how it works, and why it's shaped that way
- [docs/EXPERIMENTS.md](docs/EXPERIMENTS.md) — approaches that were tried and removed, and why
- [docs/ROADMAP.md](docs/ROADMAP.md) — what's next and the open questions
- [docs/SIUS.md](docs/SIUS.md) — SIUS display cell reference

## License

[Apache-2.0](LICENSE)
