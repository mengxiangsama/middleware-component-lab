import argparse
from contextlib import ExitStack
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import socket
from string import Template
import subprocess
from tempfile import TemporaryDirectory
from threading import Event, Thread
from time import monotonic, sleep
from urllib.parse import urlencode
from urllib.request import ProxyHandler, build_opener


LOOPBACK = "127.0.0.1"
READINESS_PATH = "/actuator/health/readiness"
LAB = Path(__file__).resolve().parent
QUERY = '{job="readiness_probe",__name__=~"up|probe_success|probe_http_status_code"}'


class ReadinessHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != READINESS_PATH:
            self.send_error(404)
            return

        ready = self.server.ready.is_set()
        body = json.dumps({"status": "UP" if ready else "OUT_OF_SERVICE"}).encode("utf-8")
        self.send_response(200 if ready else 503)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        pass


def available_addresses():
    # Hold both sockets while allocating to keep the ports distinct.
    with socket.socket() as exporter, socket.socket() as prometheus:
        exporter.bind((LOOPBACK, 0))
        prometheus.bind((LOOPBACK, 0))
        bound_addresses = (exporter.getsockname(), prometheus.getsockname())
        return tuple(f"{host}:{port}" for host, port in bound_addresses)


def stop_process(process):
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def start_process(stack, command, log_path):
    with log_path.open("wb") as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
    stack.callback(stop_process, process)
    return process


def wait_for_metrics(opener, query_url, target, probe_success, code, processes):
    expected = {"up": "1", "probe_success": str(probe_success), "probe_http_status_code": str(code)}
    deadline = monotonic() + 15
    last_observation = None
    while monotonic() < deadline:
        for process in processes:
            if process.poll() is not None:
                raise RuntimeError(f"{process.args[0]} exited with code {process.returncode}")
        try:
            with opener.open(query_url, timeout=1) as response:
                payload = json.load(response)
        except OSError as error:
            last_observation = str(error)
            sleep(0.2)
            continue

        if payload["status"] != "success" or payload["data"]["resultType"] != "vector":
            raise RuntimeError(f"Unexpected instant query response: {payload}")
        samples = payload["data"]["result"]
        if any(sample["metric"]["instance"] != target for sample in samples):
            raise RuntimeError(f"Expected samples for instance {target}: {samples}")
        last_observation = {sample["metric"]["__name__"]: sample["value"][1] for sample in samples}
        if len(samples) == len(expected) and last_observation == expected:
            return ", ".join(f"{name}={value}" for name, value in expected.items())
        sleep(0.2)
    raise RuntimeError(f"Expected {expected}, last observation: {last_observation}")


def verify(args):
    with TemporaryDirectory(prefix="blackbox-readiness-") as directory:
        runtime = Path(directory)
        exporter_log = runtime / "blackbox-exporter.log"
        prometheus_log = runtime / "prometheus.log"
        try:
            with ExitStack() as stack:
                fixture = stack.enter_context(ThreadingHTTPServer((LOOPBACK, 0), ReadinessHandler))
                fixture.ready = Event()
                fixture.ready.set()
                Thread(target=fixture.serve_forever, daemon=True).start()
                stack.callback(fixture.shutdown)
                target = f"http://{LOOPBACK}:{fixture.server_port}{READINESS_PATH}"
                exporter_addr, prometheus_addr = available_addresses()
                config = runtime / "prometheus.yml"
                template = Template((LAB / "prometheus.yml.tmpl").read_text(encoding="utf-8"))
                config.write_text(template.substitute(target=target, exporter=exporter_addr), encoding="utf-8")

                exporter = start_process(stack, [
                    args.blackbox_exporter,
                    f"--config.file={LAB / 'blackbox.yml'}",
                    f"--web.listen-address={exporter_addr}",
                ], exporter_log)
                prometheus = start_process(stack, [
                    args.prometheus,
                    f"--config.file={config}",
                    f"--storage.tsdb.path={runtime / 'data'}",
                    f"--web.listen-address={prometheus_addr}",
                ], prometheus_log)
                print(f"Fixture: {target}", flush=True)
                query_url = f"http://{prometheus_addr}/api/v1/query?{urlencode({'query': QUERY})}"
                opener = build_opener(ProxyHandler({}))
                processes = (exporter, prometheus)
                phases = (("ready", 1, 200), ("unready", 0, 503), ("recovered", 1, 200))
                for phase, probe_success, code in phases:
                    if probe_success:
                        fixture.ready.set()
                    else:
                        fixture.ready.clear()
                    observation = wait_for_metrics(opener, query_url, target, probe_success, code, processes)
                    print(f"{phase}: {observation}", flush=True)
        except (OSError, RuntimeError):
            for log in (exporter_log, prometheus_log):
                if log.exists():
                    lines = log.read_text(errors="replace").splitlines()[-12:]
                    print(f"\n{log.name}\n" + "\n".join(lines), flush=True)
            raise
    print("PASS: Prometheus observed the probe failure and recovery, with up=1 in all three stages")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Verify a Blackbox Exporter readiness probe through Prometheus")
    parser.add_argument("--prometheus", default="prometheus", help="path to the Prometheus executable")
    parser.add_argument("--blackbox-exporter", default="blackbox_exporter", help="path to the Blackbox Exporter executable")
    verify(parser.parse_args())
