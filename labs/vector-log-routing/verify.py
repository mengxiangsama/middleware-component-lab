import argparse
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
from threading import Thread


CONFIG = Path(__file__).with_name("vector.toml")
SAMPLE = (
    '{"order_id":"O-101","event":"SHIPPED","email":"example@example.test",'
    '"authorization":"Bearer synthetic-token"}\n'
    '{"order_id":"O-102",invalid-json\n'
    '{"order_id":103,"event":"PACKED"}\n'
)


def verify(binary):
    requests = []

    class Intake(BaseHTTPRequestHandler):
        def do_POST(self):
            size = int(self.headers["Content-Length"])
            requests.append((self.path, json.loads(self.rfile.read(size))))
            self.send_response(204)
            self.end_headers()

        def log_message(self, format, *args):
            pass

    with TemporaryDirectory(prefix="vector-log-lab-") as data_dir:
        with HTTPServer(("127.0.0.1", 0), Intake) as server:
            listener = Thread(target=server.serve_forever)
            listener.start()
            env = os.environ.copy()
            env["LAB_VECTOR_DATA_DIR"] = data_dir
            try:
                config = Path(data_dir, "vector.toml")
                config.write_text(
                    CONFIG.read_text(encoding="utf-8").replace(
                        "http://127.0.0.1:9000/logs",
                        f"http://127.0.0.1:{server.server_port}/logs",
                    ),
                    encoding="utf-8",
                )
                run = subprocess.run(
                    [binary, "--config", str(config)],
                    input=SAMPLE, capture_output=True, text=True, env=env, timeout=30,
                )
            finally:
                server.shutdown()
                listener.join()

    if run.returncode:
        raise RuntimeError(f"Vector exited with {run.returncode}:\n{run.stderr}")
    expected = [("/logs", {"kind": "shipment", "order_id": "O-101", "event": "SHIPPED"})]
    if requests != expected:
        raise AssertionError(f"Unexpected HTTP records: {requests!r}")
    rejected = [json.loads(line) for line in run.stdout.splitlines() if line.strip()]
    expected_rejected = [
        {"kind": "rejected", "reason": "invalid_fields"},
        {"kind": "rejected", "reason": "malformed_json"},
    ]
    if sorted(rejected, key=lambda row: row["reason"]) != expected_rejected:
        raise AssertionError(f"Unexpected rejected records: {rejected!r}")
    print(f"HTTP accepted: {requests[0][1]}")
    print("Rejection reasons: invalid_fields, malformed_json")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--vector", default="vector", help="Path to the official Vector binary")
    verify(parser.parse_args().vector)
