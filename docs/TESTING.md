# Testing the Pose Spike — Phone Only, No Laptop

You have no laptop, so the build happens on GitHub's servers, not your
phone. Termux is just the terminal you use to push code. Rough flow:

```
Termux (push code) → GitHub Actions (builds APK) → download APK in your
phone's browser → tap to install
```

No `adb`, no Android Studio, no local Gradle/SDK on your phone.

---

## 1. Termux setup (once)

```bash
pkg update -y
pkg install git -y
```

## 2. Push the project to GitHub

If you haven't already, create an empty repo at github.com in your
phone's browser (New repository → don't initialize with a README, you
already have files).

From Termux, in the `spotter-pose-spike` folder:

```bash
cd spotter-pose-spike
git init
git add .
git commit -m "pose spike"
git remote add origin https://github.com/<you>/spotter-pose-spike.git
git branch -M main
git push -u origin main
```

When it asks for a password, it wants a **personal access token**, not
your GitHub password (GitHub dropped password auth for git operations).
Generate one: github.com → your profile photo → Settings → Developer
settings → Personal access tokens → Tokens (classic) → Generate new
token → check the `repo` scope → generate → copy it somewhere, you'll
paste it as the password when Termux prompts.

## 3. Let GitHub Actions build it

The push itself triggers the build (`.github/workflows/build-apk.yml`
runs automatically on push to `main`). No local Gradle involved — the
runner installs its own JDK, Android SDK, and Gradle, and also fetches
`pose_landmarker_lite.task` automatically since it isn't committed to the
repo (kept out on purpose — it's a multi-MB binary and you're on mobile
data).

Watch it build: go to your repo on github.com → **Actions** tab → click
the running workflow. Takes a few minutes — first run is slower since
nothing's cached yet.

If it fails, the logs are right there in the Actions tab; the most likely
failure is a typo in the workflow YAML or a transient SDK download issue
on GitHub's end — re-run via the "Re-run all jobs" button if the latter.

## 4. Download the APK to your phone

Once the run finishes green: same Actions run page → scroll to
**Artifacts** at the bottom → tap `pose-spike-debug-apk` → downloads a
`.zip` containing `app-debug.apk`.

Your phone's Downloads app (or the browser's download manager) can open
the zip and extract the APK — most Android file managers handle this
without extra software. If yours can't unzip, install any free "zip
extractor" app from the Play Store.

## 5. Install it

Tap the extracted `app-debug.apk`. Android will likely block it the first
time with "For your security, your phone is not allowed to install
unknown apps from this source" — tap **Settings** on that prompt, enable
**Allow from this source** for whichever app opened it (your file
manager or browser), then go back and tap the APK again.

## 6. Grant camera permission

First launch prompts for it — allow it. If you accidentally deny, go to
Android Settings → Apps → **Spotter Pose Spike** → Permissions → Camera →
Allow.

## 7. Set up the test environment

- **Distance:** 6-8 feet from wherever you prop the phone.
- **Angle:** 45° to your side, not face-on — matches PRD F-05's planned
  camera placement and is what the joint-angle math assumes.
- **Lighting:** normal indoor light; avoid a bright window directly
  behind you.
- **Framing:** full body in frame with margin, floor to head.
- Prop the phone against something stable (books, a chair) rather than
  handheld — hand shake pollutes the jitter reading you're trying to
  measure.

Since this is the same phone running the app, you'll need to prop it up
and step back into frame yourself, then check the HUD after your set.

## 8. What to look at

Do 5-10 bodyweight squat reps at normal tempo, for a full 60-90 seconds.
Read the on-screen HUD (top-left overlay):

| Field | What it tells you |
|---|---|
| `delegate` | GPU or CPU. CPU means GPU init failed on this device — worth noting |
| `fps` | Should hold ~30. A drop after ~30-45s suggests thermal throttling |
| `inference avg/p95` | Per-frame model time in ms |
| `knee raw` vs `knee smoothed` | Close together = clean signal. Raw jumping around a lot = needs heavier filtering before rep-counting logic gets built |

Simplest way to capture it: screen-record the test with your phone's
built-in screen recorder (swipe down from notification shade, most
Android versions have a Screen Record quick-tile) so you can review the
HUD numbers afterward instead of trying to read them live while also
squatting.

## 9. If it crashes on launch

Most likely cause: the model fetch step in CI failed silently in some
edge case, or the app's expecting the file at a path that didn't get
bundled. Check the build log in the Actions tab for the "Fetch model
asset" step — it prints the file size at the end (`ls -la`); if that
shows 0 bytes or the step errored, the download from Google's bucket
failed and needs a re-run.

There's no `adb logcat` available to you without a laptop, so if it
crashes with no useful on-screen message, the fastest debugging loop is:
re-check the Actions build log, confirm the model file is present and
non-empty, and re-run the workflow.

## 10. Repeat on another device if you get access to one

Not required for this first pass, but the PRD's real target user is
running a 3-4 year old mid-range phone, not necessarily your device — if
you ever get your hands on a second phone, the same steps 4-9 apply,
minus rebuilding (just redownload the same APK artifact and sideload it
there too).

---

Bring back: delegate used, steady-state fps, whether fps degraded over
the 60-90s window, and whether raw/smoothed knee angle diverged
noticeably. That decides the next step.
