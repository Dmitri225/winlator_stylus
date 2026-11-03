# Winlator 11.2 + S Pen

Build branch: GitHub Actions checks out the official Winlator 11.2 source
(`brunodev85/winlator-app` @ `a030f55`, the commit `brunodev85/winlator` main
pins as its `app` submodule), applies `patches/*.patch`, builds the APK and
compares every file in it with the official `Winlator_11.2.apk` by SHA-256
(`APK_COMPARISON.md` in each release).

Patch contents:
- S Pen as absolute mouse: hover = cursor, tap = LMB, drag = LMB drag,
  long press (configurable, default 400 ms) or side button + touch = RMB held
- Background Protection (foreground service) enabled by default, one-time
  battery-optimization exemption request, no activity restart (= session
  loss) on dark mode / font / locale / SIM changes
