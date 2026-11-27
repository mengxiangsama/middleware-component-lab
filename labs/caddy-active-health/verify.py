"""Exercise Caddy's active readiness checks against two local HTTP backends."""

import argparse
import os
import shutil
import socket
import subprocess
import tempfile
import threading
import time
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import ProxyHandler, build_opener


HOST = "127.0.0.1"
CLIENT = build_opener(ProxyHandler({}))


class InventoryBackend(ThreadingHTTPServer):
    def __init__(self, name):
        super().__init__((HOST, 0), InventoryHandler)
        self.name = name
        self.ready = threading.Event()
        self.ready.set()
        self.saw_probe = threading.Event()


class InventoryHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/readyz":
            status, body = (200, "ready") if self.server.ready.is_set() else (503, "unready")
            self.server.saw_probe.set()
        elif self.path == "/inventory":
            status, body = 200, self.server.name
        else:
            status, body = 404, "not found"

        payload = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, format, *args):
        pass


@contextmanager
def serve_inventory(name):
    with InventoryBackend(name) as backend:
        worker = threading.Thread(target=backend.serve_forever)
        worker.start()
        try:
            yield backend
        finally:
            backend.shutdown()
            worker.join()


@contextmanager
def serve_proxy(binary, primary_port, standby_port):
    with socket.socket() as port_probe:
        port_probe.bind((HOST, 0))
        port = port_probe.getsockname()[1]

    config = Path(__file__).with_name("Caddyfile").resolve()
    with tempfile.TemporaryDirectory(prefix="caddy-health-lab-") as scratch:
        env = {
            **os.environ,
            "XDG_CONFIG_HOME": scratch,
            "XDG_DATA_HOME": scratch,
            "CADDY_LAB_PROXY_PORT": str(port),
            "CADDY_LAB_PRIMARY_PORT": str(primary_port),
            "CADDY_LAB_STANDBY_PORT": str(standby_port),
        }
        with tempfile.TemporaryFile(mode="w+", encoding="utf-8") as log:
            process = subprocess.Popen(
                [binary, "run", "--config", str(config), "--adapter", "caddyfile"],
                cwd=scratch,
                env=env,
                stdout=log,
                stderr=subprocess.STDOUT,
            )
            try:
                yield port, process
            except Exception:
                log.seek(0)
                print(log.read()[-4000:], flush=True)
                raise
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()


def get(port, path):
    try:
        response = CLIENT.open(f"http://{HOST}:{port}{path}", timeout=1)
    except HTTPError as error:
        response = error
    with response:
        return response.status, response.read().decode("utf-8")


def await_backend(port, process, name):
    deadline = time.monotonic() + 10
    last = None
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Caddy exited with status {process.returncode}")
        try:
            last = get(port, "/inventory")
            if last == (200, name):
                return
        except OSError as error:
            last = error
        time.sleep(0.1)
    raise AssertionError(f"expected Caddy to serve inventory from {name}, last result: {last!r}")


def check(label, actual, expected):
    if actual != expected:
        raise AssertionError(f"{label}: expected {expected!r}, got {actual!r}")


def check_proxy(port, process, name):
    await_backend(port, process, name)
    responses = [get(port, "/inventory") for _ in range(5)]
    check("successive proxied requests", responses, [(200, name)] * 5)
    print(f"Caddy /inventory: HTTP 200 from {name} (5/5)")


def verify(binary):
    with (
        serve_inventory("primary") as primary,
        serve_inventory("standby") as standby,
        serve_proxy(binary, primary.server_port, standby.server_port) as (port, process),
    ):
        if not primary.saw_probe.wait(timeout=10) or not standby.saw_probe.wait(timeout=10):
            raise AssertionError("Caddy did not probe /readyz on both backends")
        check("initial primary readiness", get(primary.server_port, "/readyz"), (200, "ready"))
        check("initial standby readiness", get(standby.server_port, "/readyz"), (200, "ready"))
        check_proxy(port, process, "primary")

        primary.ready.clear()
        check("primary readiness while unready", get(primary.server_port, "/readyz"), (503, "unready"))
        check("primary application while unready", get(primary.server_port, "/inventory"), (200, "primary"))
        print("Direct primary /readyz: HTTP 503. Direct /inventory: still HTTP 200.")
        check_proxy(port, process, "standby")

        primary.ready.set()
        check("recovered primary readiness", get(primary.server_port, "/readyz"), (200, "ready"))
        print("Direct primary /readyz after recovery: HTTP 200.")
        check_proxy(port, process, "primary")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--caddy", default=os.environ.get("CADDY_BINARY", "caddy"))
    args = parser.parse_args()
    binary = shutil.which(args.caddy)
    if not binary:
        parser.error("set --caddy to the Caddy 2.11.9 executable")
    verify(str(Path(binary).resolve()))
