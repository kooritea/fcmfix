# Changelog

## 1.3.0 (2026-08-18)

Personal build locked to **Android 16**.

### Breaking
- **minSdk 33 → 36** (Android 16 / API 36 only).

### Changed
- Unified motion tokens (`motion_enter_ms` / `motion_exit_ms` / `motion_scale_ms`) shared by FAB menu and FeedbackBar.
- Spacing tokens in `dimens.xml`: outer margins breathe more, grid gaps stay tight.
- `KeepNotification`: A16-only parameter layout (no multi-API branches).
- Version 1.3.0 / versionCode 58.

---

## 1.2.0 (2026-08-18)

### Breaking
- minSdk 31 → 33 (Android 13+).

### Changed
- AutoStartFix independent probes + health hit list.
- KeepNotification simplified for API 33+.

---

## 1.1.0 (2026-08-18)

### Breaking
- minSdk 29 → 31 (Android 12+).

### Changed
- BroadcastFix / KeepNotification / ReconnectManagerFix cleanup.

---

## 1.0.0

Initial baseline (fcmfix + merges, libxposed API 102).
