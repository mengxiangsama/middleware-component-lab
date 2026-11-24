import argparse
import json
import os
import shutil
import socket
import subprocess
import tempfile
import threading
import time
from contextlib import ExitStack, contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import URLError
from urllib.request import ProxyHandler, Request, build_opener

HOST = "127.0.0.1"
DISCOVERY = "/v1/health/service/storefront"
HTTP = build_opener(ProxyHandler({}))


class Storefront(ThreadingHTTPServer):
    def __init__(self, instance_id):
        super().__init__((HOST, 0), StorefrontHandler)
        self.instance_id = instance_id
        self.healthy = threading.Event()
        self.healthy.set()


class StorefrontHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/healthz":
            self.send_response(200 if self.server.healthy.is_set() else 503)
            self.end_headers()
        elif self.path == "/catalog":
            body = json.dumps({"instance": self.server.instance_id}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_error(404)

    def log_message(self, fmt, *args):
        pass


@contextmanager
def storefront(instance_id):
    with Storefront(instance_id) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield server
        finally:
            server.shutdown()
            thread.join()


def local_ports():
    with ExitStack() as stack:
        tcp = [stack.enter_context(socket.socket()) for _ in range(3)]
        for listener in tcp:
            listener.bind((HOST, 0))
        http, rpc, lan = [listener.getsockname()[1] for listener in tcp]
        udp = stack.enter_context(socket.socket(socket.AF_INET, socket.SOCK_DGRAM))
        udp.bind((HOST, lan))
        return http, rpc, lan


@contextmanager
def consul_agent(binary):
    http, rpc, lan = local_ports()
    with tempfile.TemporaryDirectory(prefix="consul-health-lab-") as workdir:
        config = Path(workdir) / "agent.json"
        config.write_text(json.dumps({
            "bind_addr": HOST,
            "advertise_addr": HOST,
            "client_addr": HOST,
            "disable_update_check": True,
            "ports": {
                "http": http,
                "server": rpc,
                "serf_lan": lan,
                "serf_wan": -1,
                "dns": -1,
                "https": -1,
                "grpc": -1,
                "grpc_tls": -1,
            },
        }), encoding="utf-8")
        with open(Path(workdir) / "consul.log", "w+", encoding="utf-8") as log:
            process = subprocess.Popen(
                [binary, "agent", "-dev", "-config-file", str(config)],
                cwd=workdir,
                env={"HOME": workdir, "TMPDIR": workdir, "GOMAXPROCS": "2"},
                stdout=log,
                stderr=subprocess.STDOUT,
            )
            try:
                endpoint = f"http://{HOST}:{http}"
                wait_until(
                    lambda: process.poll() is None and api(endpoint, "/v1/status/leader"),
                    "Consul leader",
                    process,
                )
                yield endpoint
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


def api(endpoint, path, method="GET", data=None):
    body = None if data is None else json.dumps(data).encode()
    request = Request(
        endpoint + path,
        data=body,
        headers={"Content-Type": "application/json"} if body is not None else {},
        method=method,
    )
    with HTTP.open(request, timeout=2) as response:
        payload = response.read()
    return json.loads(payload) if payload else None


def wait_until(check, description, process=None):
    end = time.monotonic() + 20
    while time.monotonic() < end:
        if process is not None and process.poll() is not None:
            raise RuntimeError(f"Consul process exited with status {process.returncode}")
        try:
            result = check()
            if result:
                return result
        except URLError:
            if process is None:
                raise
        time.sleep(0.1)
    raise TimeoutError(f"timed out waiting for {description}")


def register(endpoint, service):
    api(endpoint, "/v1/agent/service/register", method="PUT", data={
        "Name": "storefront",
        "ID": service.instance_id,
        "Address": HOST,
        "Port": service.server_port,
        "Check": {
            "CheckID": f"http:{service.instance_id}",
            "HTTP": f"http://{HOST}:{service.server_port}/healthz",
            "Interval": "1s",
            "Timeout": "500ms",
        },
    })


def verify_snapshot(endpoint, expected_status):
    expected_passing = {instance for instance, status in expected_status.items() if status == "passing"}

    def sample():
        statuses = {
            entry["Service"]["ID"]: check["Status"]
            for entry in api(endpoint, DISCOVERY)
            for check in entry["Checks"]
            if check["CheckID"] == f'http:{entry["Service"]["ID"]}'
        }
        passing = api(endpoint, DISCOVERY + "?passing=true")
        passing_ids = {entry["Service"]["ID"] for entry in passing}
        if statuses == expected_status and passing_ids == expected_passing:
            return passing
        return None

    return wait_until(sample, f"checks {expected_status} and passing instances {sorted(expected_passing)}")


def verify_routes(passing, fixtures):
    served = []
    for row in passing:
        service = row["Service"]
        instance = service["ID"]
        if (service["Address"], service["Port"]) != (HOST, fixtures[instance].server_port):
            raise RuntimeError(f"unexpected address for {instance}")
        result = api(f"http://{HOST}:{service['Port']}", "/catalog")
        if result["instance"] != instance:
            raise RuntimeError(f"request to {instance} returned a different storefront")
        served.append(instance)
    return sorted(served)


def main(binary):
    with storefront("storefront-a") as a, storefront("storefront-b") as b, consul_agent(binary) as endpoint:
        fixtures = {service.instance_id: service for service in (a, b)}
        for service in fixtures.values():
            register(endpoint, service)

        both_passing = {instance: "passing" for instance in fixtures}
        initial = verify_routes(verify_snapshot(endpoint, both_passing), fixtures)

        b.healthy.clear()
        critical = {**both_passing, b.instance_id: "critical"}
        degraded = verify_routes(verify_snapshot(endpoint, critical), fixtures)

        b.healthy.set()
        recovered = verify_routes(verify_snapshot(endpoint, both_passing), fixtures)

        print("initial passing:", ", ".join(initial))
        print("storefront-b critical; passing:", ", ".join(degraded))
        print("recovered passing:", ", ".join(recovered))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Verify Consul HTTP health-based discovery locally")
    parser.add_argument("--consul", default=os.environ.get("CONSUL_BINARY", "consul"))
    args = parser.parse_args()
    binary = shutil.which(args.consul)
    if not binary:
        parser.error("set --consul to the Consul 2.1.0 executable")
    main(str(Path(binary).resolve()))
