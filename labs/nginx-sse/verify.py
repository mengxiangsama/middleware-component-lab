#!/usr/bin/env python3
import argparse
from contextlib import closing, suppress
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import threading
import time


HOST = "127.0.0.1"
EVENT_PATH = "/orders/42/events"
FIRST = b"id: 1\ndata: packed\n\n"
SECOND = b"id: 2\ndata: shipped\n\n"


def upstream_handler(release_second):
    class OrderEvents(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_GET(self):
            if self.path != EVENT_PATH:
                self.send_error(404)
                return

            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()

            self.write_chunk(FIRST)
            # Hold the response open until the external HTTP client reads FIRST.
            if not release_second.wait(timeout=15):
                self.close_connection = True
                return
            with suppress(BrokenPipeError, ConnectionResetError):
                self.write_chunk(SECOND)
                self.wfile.write(b"0\r\n\r\n")
                self.wfile.flush()
            self.close_connection = True

        def write_chunk(self, data):
            self.wfile.write(f"{len(data):x}\r\n".encode("ascii") + data + b"\r\n")
            self.wfile.flush()

        def log_message(self, *_args):
            pass

    return OrderEvents


def free_local_port():
    with socket.socket() as listener:
        listener.bind((HOST, 0))
        return listener.getsockname()[1]


def wait_for_proxy(proxy, port):
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if proxy.poll() is not None:
            raise RuntimeError(f"NGINX exited with code {proxy.returncode}")
        try:
            with socket.create_connection((HOST, port), timeout=0.25):
                return
        except OSError:
            time.sleep(0.05)
    raise RuntimeError("NGINX did not start listening")


def verify(port, release_second):
    with closing(HTTPConnection(HOST, port, timeout=5)) as client:
        client.request("GET", EVENT_PATH, headers={"Accept": "text/event-stream"})
        with client.getresponse() as response:
            if response.status != 200 or response.getheader("Content-Type") != "text/event-stream":
                raise RuntimeError(f"unexpected SSE response: {response.status} {response.headers}")
            if response.read(len(FIRST)) != FIRST:
                raise RuntimeError("first SSE event did not match")
            print("received packed while the upstream was still waiting", flush=True)
            release_second.set()

            if response.read(len(SECOND)) != SECOND or response.read(1):
                raise RuntimeError("second SSE event or stream ending did not match")
            print("released upstream, received shipped, and reached EOF", flush=True)


def stop(proxy):
    if proxy.poll() is None:
        proxy.terminate()
        try:
            proxy.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proxy.kill()
            proxy.wait()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--nginx", default="nginx", help="path to the NGINX executable")
    args = parser.parse_args()
    binary = shutil.which(args.nginx)
    if binary is None:
        parser.error("NGINX executable not found; pass --nginx or add it to PATH")
    binary = str(Path(binary).resolve())
    subprocess.run([binary, "-v"], check=True)

    release_second = threading.Event()
    with ThreadingHTTPServer((HOST, 0), upstream_handler(release_second)) as upstream:
        server = threading.Thread(target=upstream.serve_forever, daemon=True)
        server.start()
        try:
            port = free_local_port()
            template = Path(__file__).with_name("nginx.conf.in").read_text(encoding="utf-8")
            config = template.replace("@PROXY_PORT@", str(port)).replace(
                "@UPSTREAM_PORT@", str(upstream.server_port),
            )
            with tempfile.TemporaryDirectory(prefix="nginx-sse-lab-") as directory:
                config_path = Path(directory) / "nginx.conf"
                config_path.write_text(config, encoding="utf-8")
                command = [binary, "-p", directory + "/", "-c", str(config_path), "-e", "stderr"]
                log_path = Path(directory) / "nginx.log"
                with log_path.open("wb") as log:
                    proxy = subprocess.Popen(
                        command + ["-g", "daemon off; master_process off;"],
                        stdout=log, stderr=subprocess.STDOUT,
                    )
                    try:
                        wait_for_proxy(proxy, port)
                        verify(port, release_second)
                    except Exception:
                        print(log_path.read_text(errors="replace")[-3000:])
                        raise
                    finally:
                        release_second.set()
                        stop(proxy)
        finally:
            upstream.shutdown()
            server.join()


if __name__ == "__main__":
    main()
