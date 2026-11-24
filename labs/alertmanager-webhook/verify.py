#!/usr/bin/env python3
import contextlib
import json
import queue
import shutil
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import URLError
from urllib.parse import urlencode
from urllib.request import urlopen

LAB_DIR = Path(__file__).resolve().parent
HOST = "127.0.0.1"
TARGET_PORTS = (18081, 18082)
TARGETS = {f"{HOST}:{port}" for port in TARGET_PORTS}
PROMETHEUS_URL = f"http://{HOST}:19090"


class QuietHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass


@contextlib.contextmanager
def serve(handler, port):
    with ThreadingHTTPServer((HOST, port), handler) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield
        finally:
            server.shutdown()
            thread.join()


@contextlib.contextmanager
def run_service(command, log_path):
    with log_path.open("wb") as log:
        process = subprocess.Popen(command, cwd=LAB_DIR, stdout=log, stderr=subprocess.STDOUT)
        try:
            yield process
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


def wait_for_healthy_targets(*services):
    query = urlencode({"query": 'up{job="orders"}'})
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if any(service.poll() is not None for service in services):
            raise RuntimeError("A service exited while waiting for healthy targets")
        try:
            with urlopen(f"{PROMETHEUS_URL}/api/v1/query?{query}", timeout=1) as response:
                samples = json.load(response)["data"]["result"]
            up_targets = {
                sample["metric"]["instance"] for sample in samples if sample["value"][1] == "1"
            }
            if up_targets == TARGETS:
                return
        except URLError:
            pass
        time.sleep(0.2)
    raise RuntimeError("Prometheus did not observe both order targets as up")


def expect_notification(notifications, status):
    try:
        message = notifications.get(timeout=30)
    except queue.Empty as error:
        raise RuntimeError(f"No {status} notification reached the webhook") from error

    expected_labels = {"alertname": "OrdersMetricsUnreachable", "job": "orders"}
    alerts = message["alerts"]
    observed = {(alert["labels"]["instance"], alert["status"]) for alert in alerts}
    if (
        message["status"] != status
        or message["receiver"] != "orders-webhook"
        or message["groupLabels"] != expected_labels
        or len(alerts) != len(TARGETS)
        or observed != {(target, status) for target in TARGETS}
    ):
        raise AssertionError(f"Unexpected {status} notification: {message}")
    print(f"{status}: one webhook with {', '.join(sorted(TARGETS))}", flush=True)
    return message["groupKey"]


def verify(prometheus, alertmanager, work_dir):
    failing = threading.Event()
    notifications = queue.Queue()

    class MetricsHandler(QuietHandler):
        def do_GET(self):
            if self.path != "/metrics":
                self.send_error(404)
                return
            if failing.is_set():
                self.send_error(503)
                return
            body = b"orders_lab_ready 1\n"
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; version=0.0.4")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    class WebhookHandler(QuietHandler):
        def do_POST(self):
            if self.path != "/webhook":
                self.send_error(404)
                return
            body = self.rfile.read(int(self.headers["Content-Length"]))
            notifications.put(json.loads(body))
            self.send_response(204)
            self.end_headers()

    with contextlib.ExitStack() as stack:
        for port in TARGET_PORTS:
            stack.enter_context(serve(MetricsHandler, port))
        stack.enter_context(serve(WebhookHandler, 18083))
        alertmanager_process = stack.enter_context(run_service(
            [
                str(alertmanager), "--config.file=alertmanager.yml",
                f"--web.listen-address={HOST}:19093", "--cluster.listen-address=",
                f"--storage.path={work_dir / 'alertmanager'}",
            ],
            work_dir / "alertmanager.log",
        ))
        prometheus_process = stack.enter_context(run_service(
            [
                str(prometheus), "--config.file=prometheus.yml",
                f"--web.listen-address={HOST}:19090",
                f"--storage.tsdb.path={work_dir / 'prometheus'}",
            ],
            work_dir / "prometheus.log",
        ))

        wait_for_healthy_targets(alertmanager_process, prometheus_process)
        failing.set()
        firing_group = expect_notification(notifications, "firing")
        failing.clear()
        resolved_group = expect_notification(notifications, "resolved")
        if resolved_group != firing_group:
            raise AssertionError("Recovery must refer to the same notification group")
        try:
            extra = notifications.get(timeout=5)
        except queue.Empty:
            print("No extra webhook within the next group interval", flush=True)
        else:
            raise AssertionError(f"Unexpected extra webhook: {extra}")


def main():
    binaries = []
    for executable in ("prometheus", "alertmanager"):
        path = shutil.which(executable)
        if not path:
            raise SystemExit(f"Required command is missing from PATH: {executable}")
        binaries.append(Path(path).resolve())
        subprocess.run([path, "--version"], check=True)

    with tempfile.TemporaryDirectory(prefix="alertmanager-webhook-") as directory:
        work_dir = Path(directory)
        try:
            verify(*binaries, work_dir)
        except Exception:
            for log in sorted(work_dir.glob("*.log")):
                print(f"--- {log.name} ---", flush=True)
                print(log.read_text(errors="replace"), flush=True)
            raise


if __name__ == "__main__":
    main()
