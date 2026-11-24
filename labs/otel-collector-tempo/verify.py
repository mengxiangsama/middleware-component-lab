"""Send two OTLP spans through the Collector and read them back from Tempo."""

import base64
from contextlib import ExitStack
import json
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, Request, build_opener


LAB = Path(__file__).resolve().parent
COLLECTOR_PORT = 18618
TEMPO_API = "http://127.0.0.1:18320"
HTTP = build_opener(ProxyHandler({}))


def start(stack, runtime, executable, args):
    log = stack.enter_context((runtime / f"{executable}.log").open("w+"))
    process = stack.enter_context(subprocess.Popen(
        [shutil.which(executable), *args],
        cwd=runtime,
        stdout=log,
        stderr=subprocess.STDOUT,
    ))

    def stop():
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()

    stack.callback(stop)
    return process


def request(path, **kwargs):
    with HTTP.open(Request(path, **kwargs), timeout=3) as response:
        return response.read()


def wait_for(probe, process, description):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"{description}: process exited with {process.returncode}")
        try:
            return probe()
        except HTTPError as error:
            if error.code not in (404, 503):
                raise
        except (OSError, URLError):
            pass
        time.sleep(0.25)
    raise RuntimeError(f"{description}: no response within 45 seconds")


def collector_ready():
    with socket.create_connection(("127.0.0.1", COLLECTOR_PORT), timeout=1):
        pass


def resource_span(service, name, trace_id, span_id, start_ns, end_ns, parent_id=None):
    span = {
        "traceId": trace_id,
        "spanId": span_id,
        "name": name,
        "kind": 2,
        "startTimeUnixNano": str(start_ns),
        "endTimeUnixNano": str(end_ns),
    }
    if parent_id is not None:
        span["parentSpanId"] = parent_id
    return {
        "resource": {"attributes": [{"key": "service.name", "value": {"stringValue": service}}]},
        "scopeSpans": [{"scope": {"name": "middleware-component-lab"}, "spans": [span]}],
    }


def hex_id(value, size):
    # Tempo's query JSON may encode protobuf bytes as Base64.
    if len(value) == size * 2:
        return bytes.fromhex(value).hex()
    return base64.b64decode(value, validate=True).hex()


def verify(tempo):
    trace_id, parent_id, child_id = (secrets.token_hex(size) for size in (16, 8, 8))
    end = time.time_ns()
    payload = {"resourceSpans": [
        resource_span("checkout-api", "POST /checkout", trace_id, parent_id, end - 240_000_000, end),
        resource_span(
            "inventory-api", "Reserve stock", trace_id, child_id,
            end - 225_000_000, end - 25_000_000, parent_id,
        ),
    ]}
    result = json.loads(request(
        f"http://127.0.0.1:{COLLECTOR_PORT}/v1/traces",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
    ))
    if int(result.get("partialSuccess", {}).get("rejectedSpans", 0)):
        raise RuntimeError(f"Collector rejected spans: {result}")

    response = wait_for(
        lambda: request(f"{TEMPO_API}/api/traces/{trace_id}", headers={"Accept": "application/json"}),
        tempo,
        "Tempo trace query",
    )
    trace = json.loads(response)
    actual = set()
    for batch in trace["batches"]:
        attributes = {item["key"]: item["value"] for item in batch["resource"]["attributes"]}
        service = attributes["service.name"]["stringValue"]
        for scope in batch["scopeSpans"]:
            for span in scope["spans"]:
                span_id = hex_id(span["spanId"], 8)
                span_parent = hex_id(span["parentSpanId"], 8) if span.get("parentSpanId") else None
                duration = int(span["endTimeUnixNano"]) - int(span["startTimeUnixNano"])
                actual.add((hex_id(span["traceId"], 16), span_id, span_parent, service, span["name"], duration))
    expected = {
        (trace_id, parent_id, None, "checkout-api", "POST /checkout", 240_000_000),
        (trace_id, child_id, parent_id, "inventory-api", "Reserve stock", 200_000_000),
    }
    if actual != expected:
        raise RuntimeError(f"Unexpected trace: {actual}")
    print(f"trace={trace_id}: checkout-api 240ms → inventory-api 200ms (same trace, parent-child)")


def main():
    for program in ("tempo", "otelcol"):
        if shutil.which(program) is None:
            raise SystemExit(f"{program} not found on PATH")
    for port in (18320, 18321, 18322, 18617, COLLECTOR_PORT):
        with socket.socket() as client:
            client.settimeout(1)
            if client.connect_ex(("127.0.0.1", port)) == 0:
                raise SystemExit(f"Port {port} is already listening on loopback")
    with tempfile.TemporaryDirectory(prefix="collector-tempo-") as directory:
        runtime = Path(directory)
        try:
            with ExitStack() as stack:
                tempo = start(stack, runtime, "tempo", [f"-config.file={LAB / 'tempo.yaml'}"])
                wait_for(lambda: request(f"{TEMPO_API}/ready"), tempo, "Tempo readiness")
                collector = start(stack, runtime, "otelcol", [f"--config={LAB / 'collector.yaml'}"])
                wait_for(collector_ready, collector, "Collector readiness")
                verify(tempo)
        except Exception as error:
            for log in sorted(runtime.glob("*.log")):
                print(f"{log.stem}:\n{log.read_text()}", flush=True)
            raise SystemExit(str(error)) from error


if __name__ == "__main__":
    main()
