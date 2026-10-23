# README Visual Refresh Implementation Plan

> Execute inline with the writing-plans, imagegen, verification-before-completion and EPrint versioning skills. User requests a formal README plus an actual ADB screenshot edited with a cartoon camera view; keep the repository private and do not publish a release.

**Goal:** Present EPrint as a credible native Android offline label workstation using concise product documentation and a representative visual based on the running application.

**Design:** Keep the real green/orange tablet interface, populate a non-sensitive demonstration component, replace the camera image with a cartoon component-label scene, and clearly label the resulting hero as edited demonstration imagery. Preserve the app layout and avoid claiming a physical print or printer connection that did not happen.

**Scope:** README and documentation/media, plus the version metadata needed to install the latest source locally. No product UI redesign, public visibility change, tag or GitHub Release.

- [x] Back up device preferences and inspect the current UI.
- [x] Build/test/lint current source as local v1.5.42 / 48, install and cold-start via ADB; retain local build evidence.
- [x] Temporarily populate synthetic sample data, inspect the real latest UI and capture it; do not send a print job.
- [x] Use built-in ImageGen to replace the camera area and sanitize identifiers; inspect the image and save the selected hero inside `docs/media/`.
- [x] Rewrite README around purpose, workflow, capabilities, build entry points, support boundaries and licensing; move details to existing docs.
- [x] Verify Markdown links and media, restore original device preferences, check app stability, and prepare the reviewed update for the existing private repository.

## Local build record

The device initially had v1.5.41 / 47, which predates source authorization cleanup. The local screenshot verification build uses the next patch/versionCode as required by the project skill. No installable artifact is to be committed or published.

## Verification record

- `testDebugUnitTest lintDebug assembleDebug`: successful; 232 tests passed, no failures/errors/skips. Lint reports 0 errors and 387 warnings.
- APK signature v2 and 4-byte/16-KiB alignment checks passed; the built package has no `INTERNET` permission.
- Installed v1.5.42 / 48 on the SM-X800 running Android 14. Cold start completed; original preferences restored and app restarted. The checked log window contained no fatal exception, fatal signal or ANR.
- Selected hero is a 1584 × 993 opaque PNG without EXIF. Only the edited image and its description/prompt enter Git; raw screenshots and device preference backups remain locally ignored.
- GitHub-style local rendering inspected; 16 README links/anchors checked; asset verification passed 37/37. An independent read-only review found no required fixes.
- Repository visibility, tags and releases are outside this update. Synchronization uses an ordinary push to the existing `origin/main`.
