#!/usr/bin/env python3
"""
Step Counter REST API — Python Test Client
==========================================
Simulates the Android mobile app sending step-counter measurement batches
to the server.

Works with both local XAMPP (http://) and production (https://) deployments.

Usage
-----
    # Interactive mode (prompts for all settings)
    python test_client.py

    # Non-interactive with CLI flags
    python test_client.py \\
        --base-url http://localhost/VideoLibraryDemo/public \\
        --username client1 \\
        --password secret \\
        --device-guid 550e8400-e29b-41d4-a716-446655440002 \\
        --count 5

Requirements
------------
    pip install -r requirements.txt
"""

import argparse
import base64
import json
import random
import sys
import time
import uuid
from datetime import datetime, timezone

import requests

try:
    import urllib3
    urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
except ImportError:
    pass

# ── Defaults ──────────────────────────────────────────────────────────────────

DEFAULT_BASE_URL    = "http://localhost/VideoLibraryDemo/public"
DEFAULT_USERNAME    = "client1"
DEFAULT_PASSWORD    = "secret"
DEFAULT_DEVICE_GUID = str(uuid.uuid4())   # fresh random UUID each run unless specified
DEFAULT_DEVICE_TYPE = "stepcounter"
DEFAULT_COUNT       = 5
WINDOW_MINUTES      = 5     # each synthetic event covers 5 minutes

# ── Helpers ───────────────────────────────────────────────────────────────────

SEP = "─" * 60


def print_section(title: str) -> None:
    print(f"\n{SEP}")
    print(f"  {title}")
    print(SEP)


def prompt(label: str, default: str) -> str:
    value = input(f"  {label} [{default}]: ").strip()
    return value if value else default


def decode_jwt_claims(token: str) -> dict:
    """Decode JWT payload without signature verification."""
    part = token.split(".")[1]
    # Re-pad base64url
    part += "=" * (-len(part) % 4)
    return json.loads(base64.urlsafe_b64decode(part))


# ── Synthetic data generation ─────────────────────────────────────────────────

def make_events(human_id: str, count: int) -> list[dict]:
    """
    Generate `count` consecutive step-counter events.
    Each event covers a WINDOW_MINUTES-minute window ending at the current time,
    working backwards so the last event ends now.
    """
    now_ms      = int(time.time() * 1000)
    window_ms   = WINDOW_MINUTES * 60 * 1000
    events      = []

    for i in range(count - 1, -1, -1):
        end_ms   = now_ms   - i * window_ms
        start_ms = end_ms   - window_ms
        steps    = random.randint(50, 600)   # plausible 5-minute step count

        events.append({
            "guid":      str(uuid.uuid4()),
            "humanId":   human_id,
            "startTime": start_ms,
            "endTime":   end_ms,
            "stepCount": steps,
            "note":      f"synthetic event {count - i}/{count} from Python test client",
        })

    return events


# ── API calls ─────────────────────────────────────────────────────────────────

def authenticate(
    session: requests.Session,
    base_url: str,
    username: str,
    password: str,
    device_guid: str,
    device_type: str,
    verify_ssl: bool,
) -> tuple[str, str, str]:
    """
    POST /api/devices/auth
    Returns (token, client_guid, session_guid).
    Raises RuntimeError on failure.
    """
    url     = f"{base_url.rstrip('/')}/api/devices/auth"
    payload = {
        "username":    username,
        "password":    password,
        "device_guid": device_guid,
        "device_type": device_type,
    }

    print(f"\n[AUTH]  POST {url}")
    print(f"        payload: {json.dumps(payload, indent=2)}")

    resp = session.post(url, json=payload, timeout=15, verify=verify_ssl)

    print(f"        → HTTP {resp.status_code}")
    try:
        body = resp.json()
    except Exception:
        raise RuntimeError(
            f"Auth failed ({resp.status_code}) — non-JSON response:\n{resp.text[:2000]}"
        )
    print(f"        → {json.dumps(body, indent=2)}")

    if resp.status_code != 200:
        raise RuntimeError(f"Auth failed ({resp.status_code}): {body.get('error', body)}")

    token       = body["token"]
    claims      = decode_jwt_claims(token)
    client_guid  = claims.get("sub", "")
    session_guid = body.get("session_guid") or claims.get("session_guid", "")
    print(f"        → client_guid  (JWT sub):         {client_guid}")
    print(f"        → session_guid (body + JWT claim): {session_guid}")

    return token, client_guid, session_guid


def submit_batch(
    session: requests.Session,
    base_url: str,
    token: str,
    events: list[dict],
    verify_ssl: bool,
) -> dict:
    """
    POST /api/devices/measurements/stepcounter
    Returns the response body dict.
    Raises RuntimeError on non-200.
    """
    url     = f"{base_url.rstrip('/')}/api/devices/measurements/stepcounter"
    headers = {"Authorization": f"Bearer {token}"}
    payload = {"measurements": events}

    print(f"\n[BATCH] POST {url}")
    print(f"        sending {len(events)} event(s)")
    for e in events:
        start_iso = datetime.fromtimestamp(e["startTime"] / 1000, tz=timezone.utc).strftime("%H:%M:%S")
        end_iso   = datetime.fromtimestamp(e["endTime"]   / 1000, tz=timezone.utc).strftime("%H:%M:%S")
        print(f"          guid={e['guid']}  steps={e['stepCount']:4d}  {start_iso}→{end_iso}")

    resp = session.post(url, json=payload, headers=headers, timeout=30, verify=verify_ssl)

    print(f"        → HTTP {resp.status_code}")
    try:
        body = resp.json()
    except Exception:
        raise RuntimeError(
            f"Batch submit failed ({resp.status_code}) — non-JSON response:\n{resp.text[:2000]}"
        )
    print(f"        → {json.dumps(body, indent=2)}")

    if resp.status_code != 200:
        raise RuntimeError(f"Batch submit failed ({resp.status_code}): {body.get('error', body)}")

    return body


def test_idempotency(
    session: requests.Session,
    base_url: str,
    token: str,
    events: list[dict],
    verify_ssl: bool,
) -> None:
    """Re-send the same batch — all GUIDs must come back as accepted."""
    print_section("IDEMPOTENCY TEST — re-sending same GUIDs")
    result = submit_batch(session, base_url, token, events, verify_ssl)
    accepted = result.get("accepted", [])
    rejected = result.get("rejected", [])
    print(f"\n  Accepted (idempotent): {len(accepted)}/{len(events)}")
    if rejected:
        print(f"  !! Unexpected rejections: {rejected}")
    else:
        print("  ✓ All re-sends accepted (idempotent behaviour confirmed)")


def test_malformed(
    session: requests.Session,
    base_url: str,
    token: str,
    human_id: str,
    verify_ssl: bool,
) -> None:
    """Send a batch with one valid and several malformed events."""
    print_section("MALFORMED RECORDS TEST")

    now_ms = int(time.time() * 1000)

    good = {
        "guid":      str(uuid.uuid4()),
        "humanId":   human_id,
        "startTime": now_ms - 300_000,
        "endTime":   now_ms,
        "stepCount": 200,
    }

    # Missing humanId
    bad_no_human = {
        "guid":      str(uuid.uuid4()),
        "startTime": now_ms - 300_000,
        "endTime":   now_ms,
        "stepCount": 100,
    }

    # endTime <= startTime
    bad_times = {
        "guid":      str(uuid.uuid4()),
        "humanId":   human_id,
        "startTime": now_ms,
        "endTime":   now_ms - 300_000,   # reversed
        "stepCount": 50,
    }

    # Negative step count
    bad_steps = {
        "guid":      str(uuid.uuid4()),
        "humanId":   human_id,
        "startTime": now_ms - 300_000,
        "endTime":   now_ms,
        "stepCount": -5,
    }

    submit_batch(session, base_url, token, [good, bad_no_human, bad_times, bad_steps], verify_ssl)
    print("  (Expect: 1 accepted, 3 rejected)")


def test_client_mismatch(
    session: requests.Session,
    base_url: str,
    token: str,
    verify_ssl: bool,
) -> None:
    """Send a record with a humanId that doesn't match the JWT sub."""
    print_section("CLIENT MISMATCH TEST")
    now_ms  = int(time.time() * 1000)
    wrong_guid = str(uuid.uuid4()).replace("-", "")
    event = {
        "guid":      str(uuid.uuid4()),
        "humanId":   wrong_guid,
        "startTime": now_ms - 300_000,
        "endTime":   now_ms,
        "stepCount": 100,
    }
    submit_batch(session, base_url, token, [event], verify_ssl)
    print("  (Expect: 0 accepted, 1 rejected CLIENT_MISMATCH)")


# ── Argument parsing ──────────────────────────────────────────────────────────

def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Step Counter REST API test client",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--base-url",       default=None, help=f"Server base URL (default: {DEFAULT_BASE_URL})")
    parser.add_argument("--username",       default=None, help="Client username")
    parser.add_argument("--password",       default=None, help="Client password")
    parser.add_argument("--device-guid",    default=None, help="Device UUID (default: random)")
    parser.add_argument("--device-type",    default=DEFAULT_DEVICE_TYPE)
    parser.add_argument("--count",          type=int, default=DEFAULT_COUNT,
                        help=f"Number of step events to send (default: {DEFAULT_COUNT})")
    parser.add_argument("--no-extra-tests", action="store_true",
                        help="Skip idempotency / malformed / mismatch tests")
    return parser.parse_args()


# ── Main ──────────────────────────────────────────────────────────────────────

def main() -> None:
    args = parse_args()

    print_section("Step Counter REST API — Test Client")

    interactive = not any([args.base_url, args.username, args.password])

    if interactive:
        print("\n  No arguments provided — running in interactive mode.\n")
        base_url    = prompt("Server base URL",   DEFAULT_BASE_URL)
        username    = prompt("Client username",   DEFAULT_USERNAME)
        password    = prompt("Client password",   DEFAULT_PASSWORD)
        device_guid = prompt("Device GUID",       DEFAULT_DEVICE_GUID)
        count       = int(prompt("Event count",   str(DEFAULT_COUNT)))
        extra_tests = input("  Run extra tests (idempotency/malformed/mismatch)? [Y/n]: ").strip().lower() != "n"
    else:
        base_url    = args.base_url    or DEFAULT_BASE_URL
        username    = args.username    or DEFAULT_USERNAME
        password    = args.password    or DEFAULT_PASSWORD
        device_guid = args.device_guid or DEFAULT_DEVICE_GUID
        count       = args.count
        extra_tests = not args.no_extra_tests

    device_type = args.device_type
    verify_ssl  = not base_url.startswith("http://")

    print(f"\n  base_url:    {base_url}")
    print(f"  username:    {username}")
    print(f"  device_guid: {device_guid}")
    print(f"  device_type: {device_type}")
    print(f"  event count: {count}")
    print(f"  SSL verify:  {verify_ssl}")

    session = requests.Session()

    try:
        # ── 1. Authenticate ───────────────────────────────────────────────────
        print_section("STEP 1 — Authenticate")
        token, client_guid, session_guid = authenticate(
            session, base_url, username, password, device_guid, device_type, verify_ssl
        )
        print(f"  session_guid: {session_guid}")

        # ── 2. Build synthetic events ─────────────────────────────────────────
        print_section(f"STEP 2 — Generate {count} synthetic step events")
        events = make_events(human_id=client_guid, count=count)
        total_steps = sum(e["stepCount"] for e in events)
        print(f"  Generated {len(events)} events  |  total steps: {total_steps}")

        # ── 3. Submit batch ───────────────────────────────────────────────────
        print_section("STEP 3 — Submit batch")
        result = submit_batch(session, base_url, token, events, verify_ssl)
        accepted = result.get("accepted", [])
        rejected = result.get("rejected", [])
        print(f"\n  Accepted: {len(accepted)}  |  Rejected: {len(rejected)}")

        if rejected:
            print(f"  !! Rejections: {json.dumps(rejected, indent=4)}")
            sys.exit(1)

        # ── 4. Extra tests ────────────────────────────────────────────────────
        if extra_tests:
            test_idempotency(session, base_url, token, events, verify_ssl)
            test_malformed(session, base_url, token, client_guid, verify_ssl)
            test_client_mismatch(session, base_url, token, verify_ssl)

        print_section("ALL TESTS PASSED")
        sys.exit(0)

    except RuntimeError as exc:
        print(f"\n  ERROR: {exc}")
        sys.exit(1)
    except KeyboardInterrupt:
        print("\n  Interrupted.")
        sys.exit(1)


if __name__ == "__main__":
    main()
