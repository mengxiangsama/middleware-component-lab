#!/usr/bin/env python3
"""Run a k6 release gate against stable and candidate loopback HTTP fixtures."""

import argparse
from collections import Counter, defaultdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from tempfile import TemporaryDirectory
from threading import Lock, Thread

LAB = Path(__file__).resolve().parent
REQUESTS = 20
THRESHOLD_FAILED = 99


def inventory_handler(candidate, observed):
    lock = Lock()

    class InventoryHandler(BaseHTTPRequestHandler):
        def do_GET(self):
            match = re.fullmatch(r"/inventory/sku-([0-9]+)", self.path)
            if match is None:
                self.send_error(404)
                return

            sku = int(match[1])
            error = candidate and sku % 5 == 0
            status = 503 if error else 200
            body = b'{"error":"warehouse unavailable"}' if error else b'{"available":true}'
            with lock:
                observed[sku, status] += 1
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format, *args):
            pass

    return InventoryHandler


def verify_scenario(k6, candidate, output_dir):
    profile = "candidate" if candidate else "stable"
    expected_exit = THRESHOLD_FAILED if candidate else 0
    expected_failures = 4 if candidate else 0
    observed = Counter()
    metrics_file = output_dir / f"{profile}.jsonl"
    with ThreadingHTTPServer(("127.0.0.1", 0), inventory_handler(candidate, observed)) as server:
        thread = Thread(target=server.serve_forever)
        thread.start()
        try:
            result = subprocess.run(
                [k6, "run", "--quiet", "--no-color", "--no-usage-report",
                 "--out", f"json={metrics_file}", str(LAB / "release-gate.js")],
                env={**os.environ, "INVENTORY_URL": f"http://127.0.0.1:{server.server_port}"},
                capture_output=True, text=True, timeout=30, check=False,
            )
        finally:
            server.shutdown()
            thread.join()

    expected = Counter(
        (sku, 503 if candidate and sku % 5 == 0 else 200) for sku in range(REQUESTS)
    )
    if result.returncode != expected_exit or observed != expected or not metrics_file.is_file():
        raise RuntimeError(
            f"{profile}: expected exit {expected_exit} and {expected},"
            f" got exit {result.returncode} and {observed}\n{result.stdout}\n{result.stderr}"
        )

    points = defaultdict(list)
    with metrics_file.open() as series:
        for line in series:
            event = json.loads(line)
            if event["type"] == "Point":
                points[event["metric"]].append(event["data"]["value"])

    failed = points["http_req_failed"]
    checks = points["checks"]
    if (sum(points["http_reqs"]), sum(points["iterations"]), sum(points["dropped_iterations"])) != (
        REQUESTS, REQUESTS, 0
    ):
        raise RuntimeError(f"{profile}: expected {REQUESTS} requests and completed iterations, with none dropped")
    if len(failed) != REQUESTS or sum(failed) != expected_failures:
        raise RuntimeError(f"{profile}: unexpected HTTP failure samples {failed}")
    if len(checks) != REQUESTS or sum(checks) != REQUESTS - expected_failures:
        raise RuntimeError(f"{profile}: unexpected contract check samples {checks}")

    print(f"{profile}: {REQUESTS} requests, {sum(failed) / len(failed):.0%} HTTP failures,"
          f" {sum(checks) / len(checks):.0%} checks passed, k6 exit {result.returncode}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--k6", default="k6", help="k6 executable name or path")
    args = parser.parse_args()
    k6 = shutil.which(args.k6)
    if k6 is None:
        parser.error(f"executable not found: {args.k6}")
    with TemporaryDirectory(prefix="k6-inventory-gate-") as name:
        output_dir = Path(name)
        verify_scenario(k6, candidate=False, output_dir=output_dir)
        verify_scenario(k6, candidate=True, output_dir=output_dir)


if __name__ == "__main__":
    main()
