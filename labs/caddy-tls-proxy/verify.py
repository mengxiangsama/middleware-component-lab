import argparse
import json
import os
import queue
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import time
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import HTTPSHandler, ProxyHandler, build_opener


HOST = "127.0.0.1"
ITEM_PATH = "/catalog/items?sku=K-17"


class CatalogServer(ThreadingHTTPServer):
    def __init__(self):
        super().__init__((HOST, 0), CatalogHandler)
        self.requests = queue.Queue()


class CatalogHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.server.requests.put(self.path)
        if self.path != ITEM_PATH:
            self.send_error(404)
            return
        result = json.dumps({
            "sku": "K-17",
            "available": 3,
            "path": self.path,
            "host": self.headers.get("Host"),
            "forwarded_host": self.headers.get("X-Forwarded-Host"),
            "forwarded_proto": self.headers.get("X-Forwarded-Proto"),
        }).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(result)))
        self.end_headers()
        self.wfile.write(result)

    def log_message(self, format, *args):
        pass


@contextmanager
def serve_catalog():
    with CatalogServer() as backend:
        worker = threading.Thread(target=backend.serve_forever, daemon=True)
        worker.start()
        try:
            yield backend
        finally:
            backend.shutdown()
            worker.join()


def await_tls(ca_file, port, process):
    end = time.monotonic() + 20
    last_error = None
    while time.monotonic() < end:
        if process.poll() is not None:
            raise RuntimeError(f"Caddy exited with status {process.returncode}")
        if ca_file.is_file():
            try:
                context = ssl.create_default_context(cafile=str(ca_file))
                with socket.create_connection((HOST, port), timeout=1) as connection:
                    with context.wrap_socket(connection, server_hostname=HOST):
                        return context
            except OSError as exc:
                last_error = exc
        time.sleep(0.1)
    raise TimeoutError(f"Caddy TLS listener not ready: {last_error}")


@contextmanager
def start_caddy(binary, backend_port):
    with socket.socket() as reservation:
        reservation.bind((HOST, 0))
        port = reservation.getsockname()[1]

    caddyfile = Path(__file__).with_name("Caddyfile").resolve()
    with tempfile.TemporaryDirectory(prefix="caddy-tls-lab-") as workdir:
        data_dir = Path(workdir) / "data"
        ca_file = data_dir / "pki/authorities/local/root.crt"
        with open(Path(workdir) / "caddy.log", "w+", encoding="utf-8") as log:
            process = subprocess.Popen(
                [binary, "run", "--config", str(caddyfile), "--adapter", "caddyfile"],
                cwd=workdir,
                env={
                    "HOME": workdir,
                    "XDG_CONFIG_HOME": workdir,
                    "XDG_DATA_HOME": workdir,
                    "GOMAXPROCS": "2",
                    "LAB_CADDY_DATA": str(data_dir),
                    "LAB_HTTPS_PORT": str(port),
                    "LAB_BACKEND_PORT": str(backend_port),
                },
                stdout=log,
                stderr=subprocess.STDOUT,
            )
            try:
                yield port, await_tls(ca_file, port, process)
            except Exception:
                log.seek(0)
                print(log.read()[-4000:])
                raise
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()


def https_client(context):
    return build_opener(ProxyHandler({}), HTTPSHandler(context=context))


def verify(binary):
    with serve_catalog() as backend, start_caddy(binary, backend.server_port) as (port, trusted_context):
        frontend = f"{HOST}:{port}"
        base_url = f"https://{frontend}"

        untrusted = https_client(ssl.create_default_context())
        try:
            with untrusted.open(base_url + ITEM_PATH, timeout=3):
                raise AssertionError("client unexpectedly accepted the temporary CA")
        except URLError as exc:
            if not isinstance(exc.reason, ssl.SSLCertVerificationError):
                raise

        trusted = https_client(trusted_context)
        with trusted.open(base_url + ITEM_PATH, timeout=3) as response:
            result = json.load(response)
        expected = {
            "sku": "K-17",
            "available": 3,
            "path": ITEM_PATH,
            "host": frontend,
            "forwarded_host": frontend,
            "forwarded_proto": "https",
        }
        if result != expected:
            raise AssertionError(f"unexpected proxy response: {result}")

        try:
            with trusted.open(base_url + "/other", timeout=3):
                raise AssertionError("unmatched path unexpectedly succeeded")
        except HTTPError as exc:
            with exc:
                if exc.code != 404:
                    raise
        if backend.requests.get_nowait() != ITEM_PATH or not backend.requests.empty():
            raise AssertionError("request reached the backend outside the verified route")

        print("Untrusted client: certificate rejected")
        print("Scoped CA: HTTPS catalog returned K-17 with availability 3")
        print("Backend: original Host, path and query preserved, X-Forwarded-Proto=https")
        print("Unmatched path: 404 without a backend request")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Verify local Caddy TLS and HTTP reverse proxy")
    parser.add_argument("--caddy", default=os.environ.get("CADDY_BINARY", "caddy"))
    args = parser.parse_args()
    binary = shutil.which(args.caddy)
    if not binary:
        parser.error("set --caddy to the Caddy 2.11.9 executable")
    verify(str(Path(binary).resolve()))
