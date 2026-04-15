# Step Counter — Python Test Client

Simulates the Android mobile app sending step-counter measurement batches to the server.

---

## Contents

```
devices/designs/stepcounter/test-client/
├── test_client.py    # Test client
├── requirements.txt  # pip dependencies
└── README.md         # This file
```

---

## Install

```bash
pip install -r requirements.txt
```

---

## Usage

### Interactive mode

```bash
python test_client.py
```

Prompts for all settings.

### Non-interactive

```bash
python test_client.py \
    --base-url http://telepece-test.cz \
    --username client1 \
    --password secret \
    --device-guid 550e8400-e29b-41d4-a716-446655440002 \
    --count 5
```

### Flags

| Flag               | Default            | Description                                             |
|--------------------|--------------------|---------------------------------------------------------|
| `--base-url`       | `http://localhost/VideoLibraryDemo/public` | Server base URL        |
| `--username`       | `client1`          | Client account username                                 |
| `--password`       | `secret`           | Client account password                                 |
| `--device-guid`    | random UUID        | UUID of the physical device (auto-registered on first use) |
| `--device-type`    | `stepcounter`      | Sensor device type (do not change)                      |
| `--count`          | `5`                | Number of step-counter events to generate and send      |
| `--no-extra-tests` | off                | Skip idempotency / malformed / mismatch tests           |

SSL verification is disabled automatically when the URL starts with `http://`.

---

## What the client does

1. **Authenticate** — calls `POST /api/devices/auth` with username, password, device GUID, and device type. Receives a JWT plus a `session_guid`. The server creates a `sensor_sessions` row and embeds `session_guid` in the JWT. The device is auto-registered on first use.

2. **Generate synthetic events** — creates `--count` consecutive 5-minute step-counting windows ending at the current time. Each window has a random step count (50–600 steps).

3. **Submit batch** — sends all events in one `POST /api/devices/measurements/stepcounter` call. Prints accepted / rejected counts.

4. **Extra tests** (unless `--no-extra-tests`):
   - **Idempotency** — re-sends the same batch; expects all GUIDs back in `accepted`.
   - **Malformed records** — sends events with missing `humanId`, reversed timestamps, and negative `stepCount`; expects 3 rejections and 1 accepted.
   - **Client mismatch** — sends an event whose `humanId` does not match the JWT `sub`; expects `CLIENT_MISMATCH` rejection.

---

## API reference

### Auth

```
POST /api/devices/auth
Content-Type: application/json

{
  "username":    "client1",
  "password":    "secret",
  "device_guid": "550e8400-e29b-41d4-a716-446655440002",
  "device_type": "stepcounter"
}
```

Response:
```json
{ "token": "<JWT>", "session_guid": "<server-generated-UUID>", "expires_in": 3600 }
```

---

### Submit batch

```
POST /api/devices/measurements/stepcounter
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "measurements": [
    {
      "guid":      "2a3f8c1d-45e6-7890-12ab-34cd56ef7890",
      "humanId":   "<client UUID>",
      "startTime": 1712000000000,
      "endTime":   1712000300000,
      "stepCount": 312,
      "note":      "(optional)"
    }
  ]
}
```

Response:
```json
{
  "accepted": ["2a3f8c1d45e6789012ab34cd56ef7890"],
  "rejected": []
}
```

Max batch size: **100 items**.  
Already-stored GUIDs are silently accepted (idempotent).

### Validation rules

| Field       | Rule                                    | Rejection key  |
|-------------|----------------------------------------|----------------|
| `guid`      | non-empty string                       | MALFORMED      |
| `humanId`   | non-empty, must match JWT `sub`        | CLIENT_MISMATCH |
| `startTime` | integer (ms epoch)                     | MALFORMED      |
| `endTime`   | integer (ms epoch), > `startTime`      | MALFORMED      |
| `stepCount` | non-negative integer                   | MALFORMED      |
