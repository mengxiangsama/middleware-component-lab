"""Local HTTP example for policy-based shipment reads."""

import argparse
import json
from http.client import HTTPException
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


# Synthetic credentials and records stand in for gateway identity and shipment lookups.
PRINCIPALS = {
    "Bearer lab-clerk-north": {"role": "warehouse_clerk", "warehouse": "WH-NORTH"},
    "Bearer lab-visitor-north": {"role": "visitor", "warehouse": "WH-NORTH"},
}
SHIPMENTS = {
    "S-1001": {"id": "S-1001", "warehouse": "WH-NORTH"},
    "S-2001": {"id": "S-2001", "warehouse": "WH-SOUTH"},
}


def opa_allow(opa_url, principal, shipment):
    payload = {
        "input": {
            "action": "shipment.read",
            "principal": principal,
            "shipment": {"warehouse": shipment["warehouse"]},
        }
    }
    request = Request(
        f"{opa_url.rstrip('/')}/v1/data/shipment/authorization/allow",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=1) as response:
            document = json.load(response)
    except (OSError, ValueError, HTTPException):
        return None

    result = document.get("result") if isinstance(document, dict) else None
    return result if isinstance(result, bool) else None


def create_gateway(opa_url, port=0):
    class GatewayHandler(BaseHTTPRequestHandler):
        def do_GET(self):
            principal = PRINCIPALS.get(self.headers.get("Authorization"))
            if principal is None:
                self.send_json(401, {"error": "authentication required"})
                return

            path = urlsplit(self.path).path
            shipment_id = path.removeprefix("/shipments/")
            shipment = SHIPMENTS.get(shipment_id) if path.startswith("/shipments/") else None
            if shipment is None:
                self.send_json(404, {"error": "shipment not found"})
                return

            allowed = opa_allow(opa_url, principal, shipment)
            if allowed is None:
                self.send_json(503, {"error": "policy decision unavailable"})
            elif allowed:
                self.send_json(200, shipment)
            else:
                self.send_json(403, {"error": "shipment access denied"})

        def send_json(self, status, document):
            body = json.dumps(document).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    return ThreadingHTTPServer(("127.0.0.1", port), GatewayHandler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--opa-url", default="http://127.0.0.1:8181")
    parser.add_argument("--port", type=int, default=8095)
    args = parser.parse_args()

    with create_gateway(args.opa_url, args.port) as server:
        print(f"Gateway listening on http://127.0.0.1:{server.server_port}", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
