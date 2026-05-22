# Fall Detection Detail Fields — Child App

**Date:** 2026-05-22
**Objective:** Add 6 new fields to fall event parsing + Room storage so the child app receives and persists full fall detection details from WS push.

## Changes Made (3 files)

### 1. WSClient.kt
- `fall_event` JSON parsing: already had all 6 fields parsed, but `physicalScore`/`weightedScore`/`feedRate` were `Double` — changed to `.toFloat()` conversion
- `WSEvent.FallEvent` data class: changed `physicalScore`, `weightedScore`, `feedRate` types from `Double` to `Float` with `Float` defaults

### 2. FallNotification.kt (Room Entity)
- Added `import androidx.room.ColumnInfo`
- Added `@ColumnInfo(defaultValue=...)` to all 6 new fields for safe schema migration:
  - `ffDuration` → `defaultValue = "0"`
  - `physicalScore` → `defaultValue = "0.0"` (type changed `Double` → `Float`)
  - `weightedScore` → `defaultValue = "0.0"` (type changed `Double` → `Float`)
  - `decisionPath` → `defaultValue = ""`
  - `sensorDataJson` → `defaultValue = "[]"`
  - `feedRate` → `defaultValue = "0.0"` (type changed `Double` → `Float`)

### 3. HomeFragment.kt
- `checkAndSaveFallNotification()` (from `CloudBaseClient.LastFallEvent`): added `.toFloat()` on `physicalScore`, `weightedScore`, `feedRate` to convert from `Double` → `Float`
- WS `FallEvent` handler: no changes needed — source fields are already `Float` after WSClient changes

## Key Decisions
- Used `Float` instead of `Double` for score fields per the requirement
- `@ColumnInfo(defaultValue=...)` ensures Room auto-migrates without manual migration script
- `CloudBaseClient.LastFallEvent` kept as `Double` (it comes from Gson/JSON server response, no need to change)
