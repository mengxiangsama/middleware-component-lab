import argparse
import json
from pathlib import Path
from shutil import which
import socket
import subprocess
from tempfile import TemporaryDirectory
from time import monotonic, sleep
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener


LAB = Path(__file__).resolve().parent
API_PREFIX = "/apis/registry/v3"
EXPECTED_RELEASE = "3.3.3"
OPENER = build_opener(ProxyHandler({}))


def call(base_url, method, path, expected_status, body=None):
    request = Request(
        base_url + API_PREFIX + path,
        data=json.dumps(body).encode("utf-8") if body is not None else None,
        headers={"Content-Type": "application/json"},
        method=method,
    )
    try:
        response = OPENER.open(request, timeout=3)
    except HTTPError as error:
        response = error

    with response:
        raw = response.read()
        if response.code != expected_status:
            raise RuntimeError(f"{method} {path}: expected HTTP {expected_status}, got {response.code}: {raw!r}")
        return json.loads(raw) if raw else None


def wait_for_registry(base_url, process):
    deadline = monotonic() + 60
    last_error = None
    while monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Registry exited with code {process.returncode}")
        try:
            return call(base_url, "GET", "/system/info", 200)
        except (OSError, RuntimeError) as error:
            last_error = error
            sleep(0.5)
    raise RuntimeError(f"Registry API did not become ready: {last_error}")


def version(number, filename):
    schema = (LAB / "schemas" / filename).read_text(encoding="utf-8")
    return {"version": number, "content": {"content": schema, "contentType": "application/json"}}


def verify(base_url, process):
    info = wait_for_registry(base_url, process)
    if info["version"] != EXPECTED_RELEASE:
        raise RuntimeError(f"Expected Registry {EXPECTED_RELEASE}, got {info['version']}")
    print(f"Apicurio Registry {info['version']}", flush=True)

    artifacts = "/groups/orders/artifacts"
    artifact = artifacts + "/order-created"
    create_response = call(base_url, "POST", artifacts, 200, {
        "artifactId": "order-created",
        "artifactType": "AVRO",
        "firstVersion": version("1", "v1.avsc"),
    })
    if create_response["artifact"]["artifactType"] != "AVRO" or create_response["version"]["version"] != "1":
        raise RuntimeError(f"Unexpected v1 registration: {create_response}")
    print("v1: HTTP 200, AVRO artifact created", flush=True)

    rule = {"ruleType": "COMPATIBILITY", "config": "BACKWARD"}
    call(base_url, "POST", artifact + "/rules", 204, rule)
    stored_rule = call(base_url, "GET", artifact + "/rules/COMPATIBILITY", 200)
    if stored_rule != rule:
        raise RuntimeError(f"Unexpected artifact rule: {stored_rule}")
    print("rule: COMPATIBILITY=BACKWARD", flush=True)

    versions_path = artifact + "/versions"
    published = call(base_url, "POST", versions_path, 200, version("2", "v2.avsc"))
    if published["version"] != "2":
        raise RuntimeError(f"Unexpected v2 registration: {published}")
    print("v2: HTTP 200, currency with default accepted", flush=True)

    rejected = call(base_url, "POST", versions_path, 400, version("3", "v3-incompatible.avsc"))
    if rejected.get("name") != "RuleViolationException" or "warehouseId" not in json.dumps(rejected):
        raise RuntimeError(f"Expected a compatibility problem about warehouseId: {rejected}")
    print(f"v3: HTTP {rejected['status']}, {rejected['name']} for warehouseId", flush=True)

    stored = call(base_url, "GET", versions_path, 200)
    stored_versions = sorted(item["version"] for item in stored["versions"])
    if stored["count"] != 2 or stored_versions != ["1", "2"]:
        raise RuntimeError(f"Unexpected published versions: {stored}")
    print(f"PASS: published versions remain {', '.join(stored_versions)}")


def run_registry(jar, java):
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]

    with TemporaryDirectory(prefix="apicurio-avro-") as directory:
        log_path = Path(directory) / "apicurio.log"
        with log_path.open("wb") as log:
            process = subprocess.Popen([
                java,
                "-Dquarkus.http.host=127.0.0.1",
                f"-Dquarkus.http.port={port}",
                "-Dquarkus.management.host=127.0.0.1",
                "-Dquarkus.management.port=0",
                f"-Djava.io.tmpdir={directory}",
                "-Dapicurio.storage.kind=sql",
                "-Dapicurio.storage.sql.kind=h2",
                "-jar", str(jar.resolve()),
            ], cwd=directory, stdout=log, stderr=subprocess.STDOUT)

        try:
            verify(f"http://127.0.0.1:{port}", process)
        except (OSError, RuntimeError):
            print("\n".join(log_path.read_text(errors="replace").splitlines()[-20:]), flush=True)
            raise
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Verify backward compatibility using a local Apicurio Registry")
    parser.add_argument("jar", type=Path, help="path to quarkus-run.jar from the official 3.3.3 release")
    parser.add_argument("--java", default="java", help="path to a Java 21 executable")
    args = parser.parse_args()
    java = which(args.java)
    if java is None:
        parser.error(f"Java executable not found: {args.java}")
    if not args.jar.is_file():
        parser.error(f"Registry JAR not found: {args.jar}")
    run_registry(args.jar, str(Path(java).resolve()))
