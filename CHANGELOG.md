# Changelog

## Unreleased (branch radical-simplify)

### Changed
- **UI scheme A**: BottomDock simplified — no search-width ValueAnimator, no segment corner merging, fixed 200dp search bar with fade only.
- Keep Xiaomi (MiuiLocalNotificationFix / PowerkeeperFix) and OnePlus/Oplus (OplusProxyFix) hooks.
- Further radical cleanup in progress on this branch.

---

## 1.3.2 (2026-08-18)

### Changed
- Motion: `integers.xml` tokens (`enter/exit/scale/fast`); BottomDock uses them (no hard-coded ms).
- Spacing: expand dock_* / space_md/lg tokens; wire activity_main hard-coded dp.
- ReconnectManagerFix: log branding fcmself (prefs key `fcmfix_config` kept for migration).
- CI: `actions/cache@v5` (native Node 24); rename-package `checkout@v5`.
- Drop BroadcastFix high-level fallback (A16 locked path only).
- ReconnectManagerFix: remove GMS settings inject buttons; always use Tiramisu+ receiver API.
- XposedMain: load MIUI/Oplus hooks only on matching OEM families.
- Health panel: long-press status text to copy.
- CI: force Node 24, setup-android@v4, cache SDK platforms.

### Fixed
- ObsoleteSdkInt branches under minSdk 36 cleared (lint disable removed).
- BottomDock: restore full API (`bindCounts`/`fab`/`release`/`updateFab`) after truncated push.

---

## 1.3.1 (2026-08-18)

### Fixed
- SDK bootstrap: install `platforms;android-37.0` then **rename** to `android-37` (no symlink — symlink caused AGP/sdkmanager "inconsistent location" and flaky builds).
- `gradle.properties`: lower heap default, suppress AGP generic warnings.

### Changed
- `BroadcastFix`: A16-first resolution (BroadcastController → AMS, name/type based indices).
- Version 1.3.1 / versionCode 59.

---

## 1.3.0 (2026-08-18)

### Breaking
- minSdk **36** (Android 16 only).

### Changed
- Unified motion + spacing tokens.
- KeepNotification A16-only layout.

---

## 1.2.0 / 1.1.0 / 1.0.0

See git history for earlier personal refactors.
