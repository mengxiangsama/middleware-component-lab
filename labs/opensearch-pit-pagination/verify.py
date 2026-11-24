import argparse
import http.client
import json
import time
from uuid import uuid4

KEEP_ALIVE = "1m"
PAGE_SIZE = 2
SORT = [{"updated_at": "asc"}, {"order_id": "asc"}]


class OpenSearchError(RuntimeError):
    pass


def request(port, method, path, body=None):
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
    try:
        headers = {"Content-Type": "application/json"} if body is not None else {}
        conn.request(method, path, json.dumps(body) if body is not None else None, headers)
        response = conn.getresponse()
        payload = response.read()
        if response.status >= 400:
            raise OpenSearchError(f"{method} {path}: HTTP {response.status} {payload.decode()}")
        return json.loads(payload)
    finally:
        conn.close()


def wait_until_ready(port):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            health = request(port, "GET", "/_cluster/health?wait_for_status=yellow&timeout=1s")
            if health["status"] in ("yellow", "green") and not health["timed_out"]:
                return
        except (OSError, http.client.HTTPException, OpenSearchError):
            pass
        time.sleep(0.5)
    raise RuntimeError("OpenSearch was not ready within 120 seconds")


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def write_order(port, index, order_id, updated_at, state):
    request(port, "PUT", f"/{index}/_doc/{order_id}", {
        "order_id": order_id, "updated_at": f"2026-11-24T{updated_at}Z", "state": state,
    })


def search(port, index=None, pit_id=None, after=None, size=PAGE_SIZE):
    body = {"size": size, "query": {"match_all": {}}, "sort": SORT}
    path = f"/{index}/_search" if index else "/_search"
    if pit_id is not None:
        body["pit"] = {"id": pit_id, "keep_alive": KEEP_ALIVE}
    if after is not None:
        body["search_after"] = after
    response = request(port, "POST", f"{path}?allow_partial_search_results=false", body)
    require(not response["timed_out"], "Search unexpectedly timed out")
    return response["hits"]["hits"], response.get("pit_id") or pit_id


def orders(hits):
    return [(hit["_source"]["order_id"], hit["_source"]["state"]) for hit in hits]


def verify(port):
    wait_until_ready(port)
    version = request(port, "GET", "/")["version"]["number"]
    print(f"OpenSearch {version}")
    index = f"pit-orders-{uuid4().hex}"
    request(port, "PUT", f"/{index}", {
        "settings": {"number_of_shards": 1, "number_of_replicas": 0},
        "mappings": {"properties": {
            "updated_at": {"type": "date"},
            "order_id": {"type": "keyword"},
            "state": {"type": "keyword"},
        }},
    })

    pit_id = None
    try:
        write_order(port, index, "O101", "09:00:00", "PAID")
        write_order(port, index, "O102", "09:00:00", "PENDING")
        write_order(port, index, "O103", "09:02:00", "PAID")
        write_order(port, index, "O104", "09:03:00", "PAID")
        request(port, "POST", f"/{index}/_refresh")
        pit = request(port, "POST", f"/{index}/_search/point_in_time"
                      f"?keep_alive={KEEP_ALIVE}&allow_partial_pit_creation=false")
        pit_id = pit["pit_id"]

        first, pit_id = search(port, pit_id=pit_id)
        require(orders(first) == [("O101", "PAID"), ("O102", "PENDING")], f"Unexpected page 1: {orders(first)}")
        cursor = first[-1]["sort"]
        require(first[0]["sort"][0] == cursor[0] and cursor[1] == "O102", f"Unexpected tie or cursor: {cursor}")
        print(f"PIT page 1: {orders(first)}; cursor {cursor}")

        write_order(port, index, "O105", "09:01:00", "PENDING")
        write_order(port, index, "O103", "09:05:00", "FULFILLED")
        request(port, "POST", f"/{index}/_refresh")

        second, pit_id = search(port, pit_id=pit_id, after=cursor)
        require(orders(second) == [("O103", "PAID"), ("O104", "PAID")], f"Unexpected page 2: {orders(second)}")
        end, pit_id = search(port, pit_id=pit_id, after=second[-1]["sort"])
        require(not end, f"Expected an empty PIT page 3, got {orders(end)}")
        print(f"PIT page 2: {orders(second)}; page 3 empty")

        live, _ = search(port, index=index, size=10)
        expected_live = [("O101", "PAID"), ("O102", "PENDING"), ("O105", "PENDING"),
                         ("O104", "PAID"), ("O103", "FULFILLED")]
        require(orders(live) == expected_live, f"Unexpected fresh results: {orders(live)}")
        print(f"fresh query: {orders(live)}")
    finally:
        try:
            if pit_id is not None:
                closed = request(port, "DELETE", "/_search/point_in_time", {"pit_id": [pit_id]})
                deleted = closed["pits"]
                require(len(deleted) == 1 and deleted[0]["successful"] and deleted[0]["pit_id"] == pit_id,
                        "PIT deletion failed")
                print("PIT closed")
        finally:
            request(port, "DELETE", f"/{index}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Verify stable, loopback-only OpenSearch PIT pagination.")
    parser.add_argument("port", type=int)
    verify(parser.parse_args().port)
