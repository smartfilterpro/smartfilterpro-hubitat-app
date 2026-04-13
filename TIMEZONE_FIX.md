# Fix: Hubitat Timestamp Timezone Issue

## Problem

Hubitat app is generating timestamps in EST (local time) but sending them to Core as if they were UTC, causing a 5-hour drift.

### Evidence

```sql
-- From Core equipment_events table:
recorded_at (DB UTC):     2026-02-18 05:40:09 ✅
event_timestamp (Hubitat): 2026-02-18 00:40:02 ❌ (5 hours behind)
Difference: Exactly 5.00 hours (UTC-5 / EST)
```

This causes `device_status.last_seen_at` to be stuck 5 hours in the past, eventually triggering false "unreachable" status.

### Root Cause

In `SmartFilterProHubitatApp.groovy`, timestamps are being generated with:

```groovy
new Date().toString()
```

This creates an ISO 8601 timestamp **in the Hubitat hub's local timezone** (EST), but without timezone indicators, Core treats it as UTC.

## Solution

Change all timestamp generation to use UTC explicitly:

```groovy
// ❌ WRONG - uses local timezone (EST)
timestamp: new Date().toString()

// ✅ CORRECT - uses UTC
timestamp: new Date().toInstant().toString()
```

### Files to Fix

Search `SmartFilterProHubitatApp.groovy` for these patterns:

1. **Event posting functions** - likely in `postToCoreIngest()` or similar
2. **Telemetry updates** - periodic temperature/humidity posts  
3. **Runtime tracking** - mode change events
4. **Any Map/payload construction** with a `timestamp:` field

### Search Pattern

```bash
# Find all timestamp assignments
grep -n "timestamp:" SmartFilterProHubitatApp.groovy

# Find all new Date() usage
grep -n "new Date()" SmartFilterProHubitatApp.groovy
```

### Expected Locations

Based on the app structure, timestamps are likely generated in:

1. **Runtime event posting** (mode changes: Heating, Cooling, Idle, etc.)
2. **Telemetry updates** (periodic temperature/humidity reports)
3. **Initial device registration**

### Fix Template

For each location found, replace:

```groovy
// Before:
def eventPayload = [
    device_key: getDeviceKey(),
    timestamp: new Date().toString(),  // ❌
    equipment_status: status,
    last_temperature: temp
]

// After:
def eventPayload = [
    device_key: getDeviceKey(),
    timestamp: new Date().toInstant().toString(),  // ✅
    equipment_status: status,
    last_temperature: temp
]
```

## Testing

After deploying the fix:

1. Wait for next event from Hubitat (max 20 minutes based on polling interval)
2. Query Core to verify timestamps match:

```sql
SELECT 
    device_key,
    recorded_at,
    event_timestamp,
    EXTRACT(EPOCH FROM (recorded_at - event_timestamp)) as drift_seconds
FROM equipment_events
WHERE device_key = 'ocFUiWbMS0juCcR - Hub'
  AND recorded_at > NOW() - INTERVAL '30 minutes'
ORDER BY recorded_at DESC
LIMIT 5;
```

3. Drift should be < 1 second (transaction overhead only)
4. `device_status.last_seen_at` should update correctly

## Impact

- **Risk**: Low - only changes timestamp format, no logic changes
- **Scope**: Affects only Hubitat devices (1 device currently)
- **Benefit**: Fixes false unreachable status
- **Compatibility**: UTC timestamps are the correct format per Core API spec

## Related

- Core ingest should also be hardened to use NOW() for device_status.last_seen_at (defensive fix)
- This protects against future timezone issues from any bridge
- See: Core-ingest branch `fix/device-status-last-seen-sync`
