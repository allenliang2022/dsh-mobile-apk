# Headless ARM64 system probe

This experimental harness is separate from the feature branch acceptance matrix.
The initial ARM64 software probe reported Android boot completion, then the generic
emulator action failed while injecting an unlock key because the input service was
unavailable. No APK-helper test was reached; that result is not PRoot execution
failure or successful guest acceptance.

The refined controller uses the official API30 AOSP ARM64 image, a small display,
and the same software/HVF-disabled flags. It starts ADB explicitly and waits for
boot completion plus stable PackageManager/ActivityManager responses twice. It
never requires the UI input service and never sends unlock keys. Readiness uses a
real wall-clock budget including command waits; the overall CI job is also bounded.
Only then is the existing APK-helper suite run against fixed, previously built
artifacts. Full Linux guest acceptance remains false even if the helper suite passes.

It requires a standard ARM64 macOS GitHub runner and cannot run on a local handset.
Only bounded technical summaries and selected emulator diagnostics are uploaded.
No production APK payload or running environment is changed by this experiment.
