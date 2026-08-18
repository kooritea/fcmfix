# Changelog

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
