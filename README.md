# Spotter Pose Spike

A standalone Android module to answer the PRD's open technical questions
(§12, items 1 and 3) before the full app gets built on top of this pipeline:

1. Does MediaPipe hold ~30fps on real hardware, sustained over a 60-90s set
   (not just the first few frames before thermal throttling)?
2. How noisy is the raw joint-angle signal — clean enough to rep-count
   directly, or does it need heavier smoothing / a debounce window?
3. Does the GPU delegate actually initialize reliably, or do we need the
   CPU fallback path in practice on the devices you test?

This is **not** the Spotter app. No auth, no database, no navigation, no
lift-specific logic. One screen, camera in, skeleton + numbers out.

---

## Before you build

### 1. Download the pose landmarker model

The `.task` model file is not included (binary asset, ~5-30MB depending on
variant, not something to generate). Download it and place it here:

```
app/src/main/assets/pose_landmarker_lite.task
```

Get it from Google's MediaPipe model index:
https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task

The **lite** variant is what `PoseLandmarkerHelper.MODEL_PATH` points to.
If you want to compare accuracy vs. speed, MediaPipe also ships `full` and
`heavy` variants at the same URL path (swap the folder name) — the
`MODEL_PATH` constant is the only place you'd need to change to test them.

### 2. Open in Android Studio

This project was authored directly as source files (no `gradlew` binary
generated in this environment — I have no network/JDK access here to
actually invoke Gradle). Android Studio will auto-generate the wrapper jar
on first sync as long as `gradle-wrapper.properties` is present, which it
is. If it doesn't, run:

```
gradle wrapper --gradle-version 8.7
```

from a machine with Gradle installed, once, inside this project root.

### 3. Build and run on your physical device

**Do not use an emulator for this.** Pose inference timing and GPU delegate
behavior on an emulator tell you nothing about real-device performance —
emulator camera feeds are either absent or synthetic, and there's no real
GPU delegate path. This is exactly why you having a physical device ready
was the right call.

Grant camera permission when prompted. Stand ~6-8 feet from the phone at
roughly a 45° angle (matches PRD F-05's planned camera placement guide) so
the angle math in `JointAngleCalculator` is measuring something
representative, not a face-on view that flattens joint angles.

---

## What to actually capture

The on-screen HUD (top-left overlay) shows, live:

- **delegate** — GPU or CPU. If it silently falls back to CPU on your
  device, that's itself a finding — note which device/chipset.
- **fps** — rolling average over the last ~2 seconds.
- **inference avg/p95 latency** — per-frame model inference time. If fps is
  low but latency is also low, the bottleneck is probably the bitmap
  conversion in `CameraController.toBitmapRotated()`, not the model itself
  — worth knowing which one to optimize.
- **knee raw vs. smoothed angle** — do a few bodyweight squat reps in
  frame. If raw and smoothed track closely, the native signal is clean. If
  raw jumps around a lot frame-to-frame while smoothed stays stable,
  rep-counting logic downstream will need real filtering, not just a
  threshold crossing.

Run it for a full 60-90 second set, not just a few seconds — thermal
throttling on mid-range chipsets often only shows up after 30-45s of
sustained GPU delegate use, and that's a real risk for a 30fps requirement
during an actual working set.

### Suggested test matrix

Run on whatever devices you can get your hands on, but at minimum:

| Device tier | Why it matters |
|---|---|
| Your current physical device | Baseline — start here |
| Something 3-4 years old, mid-range | This is closer to your actual median user given the $17.99/mo price point, not a flagship |
| One Samsung + one non-Samsung if possible | One UI's camera stack has historically had more CameraX quirks than stock/PixelUI |

---

## What this spike deliberately does not answer

- **Bar path tracking** (PRD §12 open question #1's ONNX layer) — this
  spike is body-landmarks only, per MediaPipe's actual capability. Barbell
  tracking is a separate model/spike, not covered here.
- **Rep-counting logic** — angle math and smoothing are here, but the
  state machine that turns "knee angle crossed X" into a counted rep is
  downstream, deliberately not built yet until we know the signal quality
  from this step.
- **Form scoring / cue generation** — same reasoning, downstream of this.
- **Low-light detection, exposure lock** — PRD §6.2 calls for this; not
  implemented here since it's additive to the camera pipeline, not part of
  the fps/accuracy question this spike is isolating.

## Next step after you run this

Come back with the fps/latency/jitter numbers (or just paste a screen
recording) and we'll use that to decide: ship MediaPipe as-is, tune
confidence thresholds, or reconsider the lite vs. full model tradeoff —
and then move on to rep-counting logic on top of whichever signal quality
you actually measured.
