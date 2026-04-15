# Step Counter — REST API Reference

**API version:** 1.0 (released 2026-04-15)  
Base URL: `<server>/api/devices`  
All requests and responses use `Content-Type: application/json`.

---

## 1. Get API Version

```
GET /api/devices/stepcounter/version
```

Returns the API version advertised by this server. No authentication required. Use this to verify compatibility before attempting to authenticate.

### Response 200

```json
{
  "api":      "stepcounter",
  "version":  "1.0",
  "released": "2026-04-15"
}
```

---

## 2. Authenticate Device

```
POST /api/devices/auth
```

Call once per session before submitting measurements. The server auto-registers the device on first login (upsert on `device_guid`).

### Request body

```json
{
  "username":    "client_login",
  "password":    "secret",
  "device_guid": "550e8400-e29b-41d4-a716-446655440002",
  "device_type": "stepcounter"
}
```

| Field         | Type   | Required | Description |
|---------------|--------|----------|-------------|
| `username`    | string | ✓        | Client account username |
| `password`    | string | ✓        | Client account password |
| `device_guid` | string | ✓        | UUID identifying the physical device (generated once by the app and persisted) |
| `device_type` | string | ✓        | Must be `"stepcounter"` |

### Response 200

```json
{
  "token":        "<JWT>",
  "session_guid": "15b5d463-0adb-4975-b1e8-c545c741d035",
  "expires_in":   3600
}
```

| Field          | Description |
|----------------|-------------|
| `token`        | JWT (HS256). Include as `Authorization: Bearer <token>` in all subsequent requests. Valid for 1 hour. |
| `session_guid` | Server-generated UUID that groups all measurements sent during this login session. Also embedded in the JWT claims — carry it for logging/display only; never send it back explicitly. |
| `expires_in`   | Token lifetime in seconds (always 3600). |

### JWT claims

```json
{
  "sub":          "<client_user_guid>",
  "client_id":    42,
  "device_id":    8,
  "device_guid":  "550e8400-e29b-41d4-a716-446655440002",
  "device_type":  "stepcounter",
  "session_guid": "15b5d463-0adb-4975-b1e8-c545c741d035",
  "session_id":   2,
  "iat":          1713100000,
  "exp":          1713103600
}
```

### Error responses

| HTTP | Condition |
|------|-----------|
| 400  | Missing or invalid fields |
| 401  | Wrong username or password |
| 422  | Unknown `device_type` |

---

## 3. Submit Step-Counter Batch

```
POST /api/devices/measurements/stepcounter
Authorization: Bearer <JWT>
```

Send one or more step-counting window events in a single batch. Each event represents an incremental time window (steps since the previous sync). The server processes records individually — valid records are accepted even if others in the same batch are rejected.

### Request body

```json
{
  "measurements": [
    {
      "guid":      "2a3f8c1d-45e6-7890-12ab-34cd56ef7890",
      "humanId":   "550e8400-e29b-41d4-a716-446655440000",
      "startTime": 1712000000000,
      "endTime":   1712000300000,
      "stepCount": 847,
      "note":      "optional note"
    }
  ]
}
```

| Field       | Type     | Required | Description |
|-------------|----------|----------|-------------|
| `guid`      | string   | ✓        | UUID (dashed or compact) generated on-device — used as global idempotency key |
| `humanId`   | string   | ✓        | Client's user GUID (must match JWT `sub`; prevents cross-client data injection) |
| `startTime` | int (ms) | ✓        | Epoch milliseconds — start of the step-counting window |
| `endTime`   | int (ms) | ✓        | Epoch milliseconds — end of the step-counting window; must be greater than `startTime` |
| `stepCount` | int      | ✓        | Non-negative integer — steps counted within the window |
| `note`      | string   | –        | Optional free-text note |

**Max batch size: 100 records.** Batches exceeding this are rejected entirely (HTTP 400).

### Response 200

```json
{
  "accepted": ["2a3f8c1d45e6789012ab34cd56ef7890"],
  "rejected": [
    { "guid": "guid-3", "error": "DUPLICATE" },
    { "guid": "guid-4", "error": "MALFORMED", "detail": "endTime must be greater than startTime" },
    { "guid": "guid-5", "error": "CLIENT_MISMATCH" }
  ]
}
```

HTTP 200 is returned even when some records are rejected. Check both lists.

> Note: GUIDs in `accepted` are returned in **compact (no-dash) format**.

**Per-record error codes:**

| Code              | Meaning |
|-------------------|---------|
| `DUPLICATE`       | GUID already stored — the record was already accepted on a previous submission (idempotent) |
| `MALFORMED`       | Field validation failed; `detail` describes the specific problem |
| `CLIENT_MISMATCH` | `humanId` does not match the JWT `sub` claim |

**Server-side validation rules:**

| Rule | Error |
|------|-------|
| `guid` non-empty after normalisation | MALFORMED |
| `humanId` non-empty after normalisation | MALFORMED |
| `humanId` equals JWT `sub` | CLIENT_MISMATCH |
| `startTime` is an integer | MALFORMED |
| `endTime` is an integer | MALFORMED |
| `endTime` > `startTime` | MALFORMED |
| `stepCount` is a non-negative integer | MALFORMED |

**Batch-level errors (entire batch rejected):**

| HTTP | Condition |
|------|-----------|
| 400  | `measurements` key missing, batch empty, or batch exceeds 100 records |
| 401  | JWT absent, expired, or invalid signature |
| 403  | Device GUID in JWT does not match the authenticated device record, or device is inactive |

---

## 4. Idempotency

Re-sending a GUID that was already accepted returns it in `accepted` again (no duplicate row is created). This allows the app to safely retry an entire batch after a network failure without producing duplicate records.

---

## 5. Session Linking

Every measurement submitted with a given JWT is automatically linked — server-side — to the `sensor_sessions` row created during authentication. `measured_at` for each record is derived from the event's `endTime` (the moment the counting window closed).

Sessions are used by the training plan integration to correlate a measurement batch with a specific exercise item (see `../../TRAINING-PLAN-PAIRING.md`).
