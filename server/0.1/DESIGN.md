# Step Counter Sensor — Design Document

**Date:** 2026-04-14  
**Status:** FOR REVIEW

---

## 1. Overview

A mobile app uses the Android `STEP_COUNTER` hardware sensor to accumulate step
counts. The sensor fires events with the total steps since boot; the app converts
those to **incremental windows** (steps since the last sync) and sends batches
to the server.

Each batch item represents one time window with a start, an end, and the number
of steps counted within that window.

---

## 2. New device type

| Field       | Value                                        |
|-------------|----------------------------------------------|
| `code`      | `stepcounter`                                |
| `name`      | `Step Counter`                               |
| description | Android STEP\_COUNTER sensor, mobile app, incremental step events |

Seeded in `sensor_device_types` by the migration.

---

## 3. API endpoint

```
POST /api/devices/measurements/stepcounter
Authorization: Bearer <device JWT>
Content-Type: application/json
```

### Auth

Same device-JWT flow as Spirogym:

1. App calls `POST /api/devices/auth` → receives `{ "token": "..." }`.
2. All subsequent measurement uploads include `Authorization: Bearer <token>`.
3. JWT payload: `{ sub: client_guid, client_id, device_id }`.

### Request body

```json
{
  "measurements": [
    {
      "guid":      "2a3f8c1d-45e6-7890-12ab-34cd56ef7890",
      "humanId":   "550e8400-e29b-41d4-a716-446655440000",
      "startTime": 1712000000000,
      "endTime":   1712003600000,
      "stepCount": 847,
      "note":      "optional free-text"
    }
  ]
}
```

| Field       | Type     | Required | Description |
|-------------|----------|----------|-------------|
| `guid`      | string   | ✓        | UUID (dashed or compact) — global idempotency key |
| `humanId`   | string   | ✓        | Client UUID (dashed or compact) — must match JWT `sub` |
| `startTime` | int (ms) | ✓        | Epoch milliseconds — start of the counting window |
| `endTime`   | int (ms) | ✓        | Epoch milliseconds — end of the counting window, must be > startTime |
| `stepCount` | int      | ✓        | Non-negative integer — steps counted in this window |
| `note`      | string   | –        | Optional free-text note |

Max batch size: **100 items** (same as Spirogym).

### Response 200

```json
{
  "accepted": ["2a3f8c1d45e6789012ab34cd56ef7890"],
  "rejected": []
}
```

`accepted` guids are always in compact (no-dash) format.  
Already-stored guids are reported as accepted (idempotent).

### Error responses

| Code | Reason |
|------|--------|
| 400  | Malformed JSON, missing `measurements`, empty batch, batch > 100, invalid field values |
| 401  | Missing / expired / invalid JWT |

---

## 4. DB schema

### New table: `sensor_measurements_stepcounter`

```sql
CREATE TABLE `sensor_measurements_stepcounter` (
    `measurement_id`  INT UNSIGNED  NOT NULL
        COMMENT 'PK + FK to sensor_measurements.id (1:1)',
    `start_time`      BIGINT        NOT NULL
        COMMENT 'Start of step-counting window, ms epoch from device clock',
    `end_time`        BIGINT        NOT NULL
        COMMENT 'End of step-counting window, ms epoch from device clock',
    `step_count`      INT UNSIGNED  NOT NULL
        COMMENT 'Number of steps recorded in this window',
    `note`            TEXT          NULL,
    PRIMARY KEY (`measurement_id`),
    CONSTRAINT `fk_smsc_meas`
        FOREIGN KEY (`measurement_id`)
        REFERENCES `sensor_measurements` (`id`)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### Relationship to `sensor_measurements`

`sensor_measurements` stores the shared header (guid, device_id, client_id,
`measured_at`, `received_at`). For stepcounter, `measured_at` is set from
`endTime` (converted from ms epoch to a TIMESTAMP), matching the semantics of
"when the measurement period ended".

---

## 5. Validation rules (server-side)

| Rule | Error key |
|------|-----------|
| `guid` non-empty after normalisation | MALFORMED |
| `humanId` non-empty after normalisation | MALFORMED |
| `humanId` == JWT `sub` (client GUID) | CLIENT_MISMATCH |
| `startTime` is integer | MALFORMED |
| `endTime` is integer | MALFORMED |
| `endTime` > `startTime` | MALFORMED |
| `stepCount` is non-negative integer | MALFORMED |

---

## 6. Hexagonal architecture layers

### Files to create

```
migrations/
  20260414140000_add_stepcounter_sensor.php

src/Domain/Sensor/Entity/
  StepCounterMeasurement.php

src/Domain/Sensor/Repository/
  StepCounterMeasurementRepositoryInterface.php

src/Application/Sensor/UseCase/SubmitStepCounterBatch/
  StepCounterMeasurementInput.php
  SubmitStepCounterBatchCommand.php
  SubmitStepCounterBatchHandler.php
  SubmitStepCounterBatchResponse.php

src/Infrastructure/Persistence/PDO/
  PDOStepCounterMeasurementRepository.php

src/Presentation/Web/Controller/
  StepCounterMeasurementController.php

devices/designs/stepcounter/test-client/
  test_client.py
  requirements.txt
```

### Files to edit

```
config/routes.php      — add POST route
config/container.php   — add interface + handler + controller bindings
```

---

## 7. Python test client

Location: `devices/designs/stepcounter/test-client/test_client.py`

Capabilities (mirrors Spirogym client):
- Interactive mode (prompts) and non-interactive mode (`--base-url`, `--username`,
  `--password`, `--device-guid`, `--count` flags)
- Calls `POST /api/devices/auth` to get device JWT
- Generates `--count` synthetic step-counter events with random step counts and
  plausible time windows (each window = 5 minutes)
- Sends in a single batch
- Prints HTTP status + JSON response for every request
- Exercises idempotency: sends the same batch a second time, expects all guids
  in `accepted`

---

## 8. Out of scope for this iteration

- Aggregation / daily summary queries (can be added to `SensorApiController` later)
- Step-goal tracking or gamification
- Admin web dashboard changes (stepcounter rows will already appear in the generic
  `/sensors` dashboard via the `sensor_measurements` join)
