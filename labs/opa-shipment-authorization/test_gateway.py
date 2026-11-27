import json
import os
import socket
import subprocess
import tempfile
import threading
import time
import unittest
from contextlib import ExitStack, contextmanager
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from gateway import create_gateway


LAB = Path(__file__).resolve().parent
CLERK = "Bearer lab-clerk-north"
VISITOR = "Bearer lab-visitor-north"


def loopback_url(port):
    return f"http://127.0.0.1:{port}"


@contextmanager
def running_gateway(opa_url):
    with create_gateway(opa_url) as gateway:
        thread = threading.Thread(target=gateway.serve_forever)
        thread.start()
        try:
            yield loopback_url(gateway.server_port)
        finally:
            gateway.shutdown()
            thread.join()


def request_shipment(gateway_url, shipment_id, credential=None):
    request = Request(f"{gateway_url}/shipments/{shipment_id}")
    if credential is not None:
        request.add_header("Authorization", credential)
    # The gateway obtains role and warehouse from its own fixtures.
    request.add_header("X-Role", "warehouse_clerk")
    request.add_header("X-Warehouse", "WH-SOUTH" if shipment_id == "S-2001" else "WH-NORTH")
    try:
        response = urlopen(request, timeout=4)
    except HTTPError as error:
        response = error
    with response:
        return response.status, json.load(response)


def stop_opa(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


class ShipmentAuthorizationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        resources = ExitStack()
        cls.addClassCleanup(resources.close)
        with socket.socket() as port_probe:
            port_probe.bind(("127.0.0.1", 0))
            opa_port = port_probe.getsockname()[1]

        opa_log = resources.enter_context(tempfile.TemporaryFile(mode="w+"))
        process = subprocess.Popen(
            [
                os.environ.get("OPA_BIN", "opa"), "run", "--server", "--addr",
                f"127.0.0.1:{opa_port}", "--log-level", "error", str(LAB / "shipment.rego"),
            ],
            stdout=opa_log,
            stderr=subprocess.STDOUT,
        )
        resources.callback(stop_opa, process)
        opa_url = loopback_url(opa_port)
        deadline = time.monotonic() + 10
        while process.poll() is None and time.monotonic() < deadline:
            try:
                with urlopen(f"{opa_url}/health", timeout=0.5):
                    break
            except OSError:
                time.sleep(0.05)
        else:
            opa_log.seek(0)
            raise RuntimeError(f"OPA failed to start: {opa_log.read()}")
        cls.gateway_url = resources.enter_context(running_gateway(opa_url))

    def test_same_warehouse_clerk_can_read_shipment(self):
        status, body = request_shipment(self.gateway_url, "S-1001", CLERK)
        self.assertEqual(200, status)
        self.assertEqual({"id": "S-1001", "warehouse": "WH-NORTH"}, body)

    def test_cross_warehouse_clerk_is_denied_despite_request_header(self):
        status, body = request_shipment(self.gateway_url, "S-2001", CLERK)
        self.assertEqual(403, status)
        self.assertEqual({"error": "shipment access denied"}, body)

    def test_visitor_is_denied_despite_request_header(self):
        status, body = request_shipment(self.gateway_url, "S-1001", VISITOR)
        self.assertEqual(403, status)
        self.assertEqual({"error": "shipment access denied"}, body)

    def test_request_without_fixture_credential_is_unauthenticated(self):
        status, body = request_shipment(self.gateway_url, "S-1001")
        self.assertEqual(401, status)
        self.assertEqual({"error": "authentication required"}, body)

    def test_unavailable_opa_does_not_release_shipment(self):
        # Reserve an address with no listener so the gateway gets a real connection failure.
        with socket.socket() as unused_address:
            unused_address.bind(("127.0.0.1", 0))
            opa_url = loopback_url(unused_address.getsockname()[1])
            with running_gateway(opa_url) as gateway_url:
                status, body = request_shipment(gateway_url, "S-1001", CLERK)
        self.assertEqual(503, status)
        self.assertEqual({"error": "policy decision unavailable"}, body)


if __name__ == "__main__":
    unittest.main()
