# Changelog

## 1.2.0 (2026-08-18)

Personal aggressive refactor, continued.

### Breaking
- **minSdk 31 → 33** (Android 13+ only). Android 12 is no longer supported.

### Changed
- `AutoStartFix`: probe each OEM path independently; health panel now reports hit count and short names instead of a generic “探测完成”.
- `KeepNotification`: further simplified for API 33+ layouts only.
- Version bump to 1.2.0 / versionCode 57.

### Docs
- Added this CHANGELOG.

---

## 1.1.0 (2026-08-18)

Personal aggressive refactor (first pass).

### Breaking
- **minSdk 29 → 31** (Android 12+ only). Android 10/11 special cases removed.

### Changed
- `BroadcastFix`: dropped Q/R parameter tables; modern defaults + name-based fallback; locked hook primary, high-level only as fallback.
- `KeepNotification`: API 31+ layouts only.
- `ReconnectManagerFix`: cleaned experimental TODOs; clearer health status on detection failure / old GMS.
- Version 1.1.0 / versionCode 56.

### Docs
- README updated for personal-build policy.

---

## 1.0.0

Initial public baseline (based on fcmfix + FcmFuck / FLAG_RECEIVER_FOREGROUND / PowerExemptionManager / libxposed API 102).
