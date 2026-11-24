#!/usr/bin/env python3
import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, Request, build_opener


def free_local_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def wait_for_health(api, server):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if server.poll() is not None:
            raise RuntimeError(f"Meilisearch exited with code {server.returncode}")
        try:
            if api("GET", "/health")["status"] == "available":
                return
        except (URLError, TimeoutError):
            pass
        time.sleep(0.1)
    raise RuntimeError("Meilisearch did not become healthy")


def wait_for_task(api, method, path, body):
    uid = api(method, path, body, expected_status=202)["taskUid"]
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        task = api("GET", f"/tasks/{uid}")
        status = task["status"]
        if status == "succeeded":
            print(f"task {uid} {task['type']} succeeded")
            return
        if status in {"failed", "canceled"}:
            raise RuntimeError(f"task {uid} {status}: {task.get('error')}")
        time.sleep(0.05)
    raise RuntimeError(f"task {uid} did not finish")


def search(api, **query):
    response = api("POST", "/indexes/products/search", {
        "q": "keyboard",
        "attributesToRetrieve": ["id", "price_cents"],
        **query,
    })
    return [(hit["id"], hit["price_cents"]) for hit in response["hits"]]


def expect(label, actual, expected):
    if actual != expected:
        raise RuntimeError(f"{label}: expected {expected}, got {actual}")
    print(f"{label}: {actual}")


def verify(api):
    print("Meilisearch", api("GET", "/version")["pkgVersion"])
    wait_for_task(api, "POST", "/indexes", {"uid": "products", "primaryKey": "id"})
    wait_for_task(api, "PATCH", "/indexes/products/settings", {
        "searchableAttributes": ["title"],
        "filterableAttributes": ["category", "in_stock"],
        "sortableAttributes": ["price_cents"],
    })
    products = json.loads(Path(__file__).with_name("products.json").read_text(encoding="utf-8"))
    wait_for_task(api, "POST", "/indexes/products/documents", products)

    expect("keyword matches", sorted(product_id for product_id, _ in search(api)),
           ["cover", "kb-clearance", "kb-compact", "kb-pro"])
    condition = 'category = "keyboards" AND in_stock = true'
    expect("available keyboards by ascending price",
           search(api, filter=condition, sort=["price_cents:asc"]),
           [("kb-compact", 8900), ("kb-pro", 14900)])
    expect("available keyboards by descending price",
           search(api, filter=condition, sort=["price_cents:desc"]),
           [("kb-pro", 14900), ("kb-compact", 8900)])

    wait_for_task(api, "PUT", "/indexes/products/documents", [
        {"id": "kb-compact", "in_stock": False},
    ])
    expect("available keyboards after stock update",
           search(api, filter=condition, sort=["price_cents:asc"]),
           [("kb-pro", 14900)])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--meilisearch", default="meilisearch", help="path to the Meilisearch executable")
    args = parser.parse_args()
    binary = shutil.which(args.meilisearch)
    if binary is None:
        parser.error("Meilisearch executable not found; pass --meilisearch or add it to PATH")

    host = f"127.0.0.1:{free_local_port()}"
    key = secrets.token_hex(24)
    env = {name: value for name, value in os.environ.items() if not name.startswith("MEILI_")}
    env["MEILI_MASTER_KEY"] = key
    opener = build_opener(ProxyHandler({}))

    def api(method, path, body=None, expected_status=200):
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = Request(f"http://{host}{path}", data=data, method=method, headers={
            "Authorization": f"Bearer {key}",
            "Content-Type": "application/json",
        })
        try:
            with opener.open(request, timeout=3) as response:
                if response.status != expected_status:
                    raise RuntimeError(f"{method} {path} returned HTTP {response.status}")
                return json.load(response)
        except HTTPError as error:
            raise RuntimeError(f"{method} {path}: {error.code} {error.read().decode()}") from error

    with tempfile.TemporaryDirectory(prefix="meilisearch-lab-") as directory:
        log_path = Path(directory) / "server.log"
        with log_path.open("wb") as log:
            server = subprocess.Popen(
                [str(Path(binary).resolve()), "--env", "production", "--http-addr", host,
                 "--db-path", "products.ms", "--no-analytics"],
                cwd=directory, env=env, stdout=log, stderr=subprocess.STDOUT,
            )
            try:
                wait_for_health(api, server)
                verify(api)
            except Exception:
                print(log_path.read_text(errors="replace")[-3000:].replace(key, "[lab key]"))
                raise
            finally:
                if server.poll() is None:
                    server.terminate()
                    try:
                        server.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        server.kill()
                        server.wait()


if __name__ == "__main__":
    main()
