#!/usr/bin/env python3
"""Summarize a ThermalSession log file produced by SystemMonitor.DEBUG CSV mode.

Log format (CSV, one line per tick, produced by SystemMonitor.collectMetrics
with BuildConfig.DEBUG):
  ts_ms,batteryLevel,batteryTemp,cpuMax,perfil,cpu-0-0=val,cpu-1-3=val,...

The script computes per-column min/media/p50/p95/max, counts jumps >10°C
between consecutive ticks, identifies which cpu-* zone causes the most jumps,
and reports how many jumps survive the median-of-5 filter.

Usage:
    python3 summarize_session.py session.log
    # or, if run from the project root:
    ./tools/summarize_session.py session.log
"""

import sys
import os
import re
import statistics
import math
from collections import defaultdict
from typing import List, Dict, Tuple, Optional


def parse_log_line(line: str) -> Optional[Dict[str, float]]:
    """Parse a single CSV log line.

    Expected format:
      ts_ms,batteryLevel,batteryTemp,cpuMax,perfil,cpu-0-0=val,cpu-1-3=val,...

    Returns a dict with parsed float values, or None if the line is malformed.
    """
    line = line.strip()
    if not line:
        return None

    parts = line.split(",")
    if len(parts) < 5:
        return None

    try:
        result = {
            "ts_ms": float(parts[0]),
            "batteryLevel": float(parts[1]) if parts[1] != "NaN" else None,
            "batteryTemp": float(parts[2]) if parts[2] != "NaN" else None,
            "cpuMax": float(parts[3]) if parts[3] != "NaN" else None,
            "perfil": float(parts[4]) if parts[4] != "null" and parts[4] != "NaN" else None,
        }
        # Parse cpu-* zones (key=val pairs starting at index 5)
        i = 5
        while i < len(parts):
            pair = parts[i]
            if "=" in pair:
                key, val = pair.split("=", 1)
                key = key.strip()
                try:
                    result[key] = float(val.strip())
                except ValueError:
                    pass  # skip unparseable values
            i += 1
        return result
    except (ValueError, IndexError):
        return None


def compute_stats(values: List[Optional[float]]) -> Dict[str, Optional[float]]:
    """Compute min/median/p50/p95/max for a list of values (None = skip)."""
    vals = [v for v in values if v is not None]
    if not vals:
        return {"min": None, "p50": None, "median": None, "p95": None, "max": None}

    sorted_vals = sorted(vals)
    n = len(sorted_vals)

    def percentile(p: float) -> float:
        if n == 1:
            return sorted_vals[0]
        k = (n - 1) * p / 100.0
        f = math.floor(k)
        c = math.ceil(k)
        if f == c:
            return sorted_vals[int(k)]
        d0 = sorted_vals[int(f)] * (c - k)
        d1 = sorted_vals[int(c)] * (k - f)
        return d0 + d1  # weighted interpolation

    return {
        "min": min(vals),
        "p50": percentile(50),
        "median": statistics.median(vals),
        "p95": percentile(95),
        "max": max(vals),
    }


def main(log_path: str) -> None:
    if not os.path.isfile(log_path):
        print(f"Error: log file not found: {log_path}", file=sys.stderr)
        sys.exit(1)

    # Parse all lines
    rows: List[Dict[str, float]] = []
    with open(log_path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            parsed = parse_log_line(line)
            if parsed is not None:
                rows.append(parsed)

    if not rows:
        print("No parseable rows found in the log.", file=sys.stderr)
        sys.exit(0)

    num_ticks = len(rows)

    # --- Identify cpu-* columns ---
    cpu_keys = sorted([k for k in rows[0].keys() if k.startswith("cpu-")])
    # Also collect all non-numeric meta keys we know to skip
    meta_keys = {"ts_ms", "batteryLevel", "batteryTemp", "cpuMax", "perfil"}

    # --- Per-column statistics ---
    print(f"=== Log summary: {num_ticks} ticks ===\n")

    # Overall numeric columns
    print("--- Numeric columns ---")
    for key in ["batteryLevel", "batteryTemp", "cpuMax"]:
        vals = [row[key] for row in rows if row.get(key) is not None]
        if vals:
            stats = compute_stats(vals)
            print(f"{key}: min={stats['min']:.1f}  p50={stats['p50']:.1f}  median={stats['median']:.1f}  p95={stats['p95']:.1f}  max={stats['max']:.1f}")
        else:
            print(f"{key}: no data")

    # Per-cpu-zone statistics
    if cpu_keys:
        print(f"\n--- CPU zones ({', '.join(cpu_keys)}) ---")
        for key in cpu_keys:
            vals = [row[key] for row in rows if row.get(key) is not None]
            if vals:
                stats = compute_stats(vals)
                print(f"{key}: min={stats['min']:.1f}  p50={stats['p50']:.1f}  median={stats['median']:.1f}  p95={stats['p95']:.1f}  max={stats['max']:.1f}")

    # --- Jumps >10°C between consecutive ticks ---
    print(f"\n--- Jumps >10°C between consecutive ticks ---")

    # Jumps for cpuMax
    cpu_max_vals = [row["cpuMax"] for row in rows if row.get("cpuMax") is not None]
    jumps_cpu_max = 0
    jump_details = []

    for i in range(1, len(cpu_max_vals)):
        prev = cpu_max_vals[i - 1]
        curr = cpu_max_vals[i]
        if prev is not None and curr is not None:
            delta = abs(curr - prev)
            if delta > 10:
                jumps_cpu_max += 1
                # Try to identify which cpu-* zone caused a significant change
                # by looking at zone differences at this tick
                jump_details.append((i, delta, prev, curr))

    print(f"cpuMax jumps >10°C: {jumps_cpu_max} out of {len(cpu_max_vals)-1} transitions")

    # Jumps per cpu-* zone
    zone_jumps: Dict[str, int] = defaultdict(int)
    for i in range(1, len(rows)):
        prev_row = rows[i - 1]
        curr_row = rows[i]
        for key in cpu_keys:
            prev_val = prev_row.get(key)
            curr_val = curr_row.get(key)
            if prev_val is not None and curr_val is not None:
                delta = abs(curr_val - prev_val)
                if delta > 10:
                    zone_jumps[key] += 1

    if zone_jumps:
        print("Jumps >10°C per zone:")
        for zone, count in sorted(zone_jumps.items(), key=lambda x: -x[1]):
            print(f"  {zone}: {count} jumps")
        # Zone with most jumps
        worst_zone = max(zone_jumps, key=zone_jumps.get)
        print(f"  -> Most volatile zone: {worst_zone} ({zone_jumps[worst_zone]} jumps)")
    else:
        print("No jumps >10°C detected in any cpu-* zone.")

    # --- Jumps that survive median-of-5 filter ---
    # A jump "survives" if the absolute difference between the medians of two
    # consecutive windows of size 5 exceeds 10°C.
    print(f"\n--- Jumps >10°C after median-of-5 filter ---")
    median_window = 5

    surviving_jumps = 0
    for i in range(median_window, len(cpu_max_vals)):
        # Window ending at i-1 and window ending at i
        win_prev = cpu_max_vals[i - median_window:i]
        win_curr = cpu_max_vals[i - median_window + 1:i + 1]

        # Compute medians
        med_prev = statistics.median(win_prev) if win_prev else None
        med_curr = statistics.median(win_curr) if win_curr else None

        if med_prev is not None and med_curr is not None:
            delta = abs(med_curr - med_prev)
            if delta > 10:
                surviving_jumps += 1

    print(f"cpuMax jumps >10°C (surviving median-of-5): {surviving_jumps} out of "
          f"{len(cpu_max_vals) - median_window} transitions")

    # --- p95 of S (smoothed signal) and battery ---
    # S is the median-smoothed cpuMax; we approximate by computing p95 of raw cpuMax
    # and p95 of batteryTemp for display purposes.
    print(f"\n--- p95 statistics ---")
    cpu_max_vals_sorted = sorted([v for v in cpu_max_vals if v is not None])
    bat_temp_vals = [row["batteryTemp"] for row in rows if row.get("batteryTemp") is not None]

    if cpu_max_vals_sorted:
        # p95 approximation: index-based
        n = len(cpu_max_vals_sorted)
        p95_idx = int(n * 0.95)
        p95_idx = min(p95_idx, n - 1)
        p95_cpu = cpu_max_vals_sorted[p95_idx]
        print(f"p95 of cpuMax: {p95_cpu:.1f}°C")

    if bat_temp_vals:
        bat_sorted = sorted(bat_temp_vals)
        n = len(bat_sorted)
        p95_idx = int(n * 0.95)
        p95_idx = min(p95_idx, n - 1)
        p95_bat = bat_sorted[p95_idx]
        print(f"p95 of batteryTemp: {p95_bat:.1f}°C")

    # --- Summary ---
    print(f"\n=== Summary ===")
    print(f"Total ticks: {num_ticks}")
    print(f"cpuMax jumps >10°C (raw): {jumps_cpu_max} / {len(cpu_max_vals)-1}")
    print(f"cpuMax jumps >10°C (median-filtered): {surviving_jumps} / {len(cpu_max_vals)-median_window}")
    if zone_jumps:
        worst = max(zone_jumps, key=zone_jumps.get)
        print(f"Most volatile zone: {worst} ({zone_jumps[worst]} jumps >10°C)")


if __name__ == "__main__":
    log_path = sys.argv[1] if len(sys.argv) > 1 else None
    if log_path is None:
        # Try default: session.log in current dir or home
        for candidate in ["./session.log", os.path.expanduser("~/session.log")]:
            if os.path.isfile(candidate):
                log_path = candidate
                break
        else:
            print("Usage: python3 summarize_session.py <session.log>", file=sys.stderr)
            print("  Or place session.log in current dir or ~/session.log", file=sys.stderr)
            sys.exit(1)
    main(log_path)