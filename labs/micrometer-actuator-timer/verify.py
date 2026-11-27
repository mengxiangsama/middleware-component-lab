#!/usr/bin/env python3
"""Send real HTTP requests and check the timer via a local Prometheus server."""

import argparse
from contextlib import ExitStack, closing
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import urlopen

LAB = Path(__file__).resolve().parent
JAR = LAB / "target" / "micrometer-actuator-timer-1.0.0.jar"


def free_ports():
    with closing(socket.socket()) as first, closing(socket.socket()) as second:
        first.bind(("127.0.0.1", 0))
        second.bind(("127.0.0.1", 0))
        return first.getsockname()[1], second.getsockname()[1]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def stop(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def wait_for(description, process, check, timeout=45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        require(process.poll() is None, f"{description}: process exited with {process.returncode}")
        try:
            if check():
                return
        except URLError:
            pass
        time.sleep(0.2)
    raise RuntimeError(f"Timed out waiting for {description}")


def fetch(url):
    with urlopen(url, timeout=3) as response:
        return response.read().decode()


def check_shipment(app_url, shipment, expected_status, expected_body):
    request_url = f"{app_url}/shipments/{shipment}/eligibility"
    try:
        response = urlopen(request_url, timeout=5)
    except HTTPError as error:
        response = error

    with response:
        require(response.code == expected_status, f"{shipment}: HTTP {response.code}")
        require(response.read().decode() == expected_body, f"{shipment}: unexpected response body")
        nanos = int(response.headers["X-Lab-Elapsed-Nanos"])
    require(nanos > 0, f"{shipment}: expected a positive duration")
    print(f"{shipment}: HTTP {expected_status}, {expected_body}, {nanos / 1_000_000:.3f} ms", flush=True)
    return nanos


def query(prometheus_url, expression):
    endpoint = f"{prometheus_url}/api/v1/query?{urlencode({'query': expression})}"
    response = json.loads(fetch(endpoint))
    require(response["status"] == "success", f"Query failed: {response}")
    return response["data"]["result"]


def sample_value(prometheus_url, expression):
    samples = query(prometheus_url, expression)
    if not samples:
        return None
    require(len(samples) == 1, f"{expression}: expected one series, got {samples}")
    return float(samples[0]["value"][1])


def verify_samples(prometheus_url, observations):
    require(sample_value(prometheus_url, 'up{job="shipment-lab"}') == 1, "Target must be up")
    for result, durations in observations.items():
        count_selector = f'job="shipment-lab",result="{result}"'
        timer_count = f"shipment_validation_seconds_count{{{count_selector}}}"
        require(sample_value(prometheus_url, timer_count) == len(durations), f"{result}: bad count")
        for label, nanos in (("0.05", 50_000_000), ("0.25", 250_000_000), ("+Inf", float("inf"))):
            bucket = f'shipment_validation_seconds_bucket{{{count_selector},le="{label}"}}'
            expected = sum(duration <= nanos for duration in durations)
            actual = sample_value(prometheus_url, bucket)
            require(actual == expected, f"{bucket}: expected {expected}, got {actual}")
            print(f"Prometheus {result} le={label}: {int(actual)}", flush=True)


def run(prometheus):
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / "java") if java_home else "java"
    with tempfile.TemporaryDirectory(prefix="shipment-metrics-") as directory:
        work = Path(directory)
        app_port, prometheus_port = free_ports()
        app_url = f"http://127.0.0.1:{app_port}"
        prometheus_url = f"http://127.0.0.1:{prometheus_port}"
        config = work / "prometheus.yml"
        config.write_text(
            "global:\n"
            "  scrape_interval: 1s\n"
            "  scrape_timeout: 1s\n"
            "scrape_configs:\n"
            "  - job_name: shipment-lab\n"
            "    metrics_path: /actuator/prometheus\n"
            "    static_configs:\n"
            f"      - targets: ['127.0.0.1:{app_port}']\n",
            encoding="utf-8",
        )

        try:
            with ExitStack() as stack:
                app_log = stack.enter_context((work / "app.log").open("w"))
                app = subprocess.Popen(
                    [java, "-jar", JAR, f"--server.port={app_port}"],
                    stdout=app_log, stderr=subprocess.STDOUT,
                )
                stack.callback(stop, app)
                wait_for("Spring Boot health", app, lambda: json.loads(fetch(f"{app_url}/actuator/health"))["status"] == "UP")
                normal = check_shipment(app_url, "ship-101", 200, "accepted")
                rejected = check_shipment(app_url, "ship-102", 422, "rejected")
                slow = check_shipment(app_url, "ship-103", 200, "accepted")
                require(slow > 50_000_000, "The simulated slow lookup must exceed 50 ms")
                export = fetch(f"{app_url}/actuator/prometheus")
                require("# TYPE shipment_validation_seconds histogram" in export, "Actuator did not export the histogram")

                prometheus_log = stack.enter_context((work / "prometheus.log").open("w"))
                server = subprocess.Popen(
                    [prometheus, f"--config.file={config}", f"--storage.tsdb.path={work / 'tsdb'}",
                     f"--web.listen-address=127.0.0.1:{prometheus_port}"],
                    stdout=prometheus_log, stderr=subprocess.STDOUT,
                )
                stack.callback(stop, server)
                wait_for("first successful Prometheus scrape", server,
                         lambda: sample_value(prometheus_url, 'up{job="shipment-lab"}') == 1)
                verify_samples(prometheus_url, {"accepted": [normal, slow], "rejected": [rejected]})
        except Exception:
            for path in (work / "app.log", work / "prometheus.log"):
                if path.exists():
                    print(f"--- {path.name} ---\n{path.read_text(encoding='utf-8', errors='replace')}")
            raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("prometheus", type=Path, help="path to the Prometheus executable")
    args = parser.parse_args()
    prometheus = args.prometheus.resolve()
    if not JAR.is_file():
        parser.error("build the executable JAR first with the Maven command in this lab's README")
    if not prometheus.is_file() or not os.access(prometheus, os.X_OK):
        parser.error(f"Prometheus executable not found: {prometheus}")
    run(prometheus)


if __name__ == "__main__":
    main()
