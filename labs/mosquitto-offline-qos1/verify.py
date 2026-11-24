import argparse
from contextlib import contextmanager
from pathlib import Path
import socket
from tempfile import TemporaryDirectory
from time import monotonic, sleep
import subprocess
from uuid import uuid4

import paho.mqtt.client as mqtt


HOST = "127.0.0.1"
IMAGE = (
    "eclipse-mosquitto:2.0.22"
    "@sha256:81a6193ae693942a3231cd3ac52875b02b7e3a19da671b2fb4b55cc874c5b279"
)
TIMEOUT = 10


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def docker(*args, check=True):
    result = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=20)
    if check:
        require(result.returncode == 0, result.stderr.strip() or result.stdout.strip())
    return (result.stdout + result.stderr).strip()


def wait_for_broker(port):
    deadline = monotonic() + TIMEOUT
    last_error = None
    while monotonic() < deadline:
        try:
            readiness = ClientSession("lab-readiness", clean_session=True)
            readiness.client.connect_timeout = 0.5
            with readiness.connect(port, timeout=0.5):
                return
        except (OSError, RuntimeError) as error:
            last_error = error
            sleep(0.1)
    raise TimeoutError("Mosquitto did not acknowledge an MQTT connection") from last_error


@contextmanager
def docker_broker():
    name = f"mqtt-offline-lab-{uuid4().hex}"
    config = Path(__file__).with_name("mosquitto.conf").resolve()
    try:
        docker("run", "--detach", "--rm", "--name", name, "--pull", "never",
               "--publish", f"{HOST}::1883/tcp",
               "--volume", f"{config}:/mosquitto/config/mosquitto.conf:ro", IMAGE)
        address = docker("port", name, "1883/tcp")
        host, port = address.rsplit(":", 1)
        require(host == HOST, f"Unexpected broker binding: {address}")
        yield int(port), lambda: docker("logs", name)
    finally:
        docker("rm", "--force", name, check=False)


@contextmanager
def native_broker(binary):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as reservation:
        reservation.bind((HOST, 0))
        port = reservation.getsockname()[1]
    with TemporaryDirectory(prefix="mqtt-offline-lab-") as scratch:
        config = Path(scratch, "mosquitto.conf")
        template = Path(__file__).with_name("mosquitto.conf").read_text(encoding="utf-8")
        config.write_text(template.replace("listener 1883", f"listener {port} {HOST}", 1), encoding="utf-8")
        output = Path(scratch, "broker.log")
        with output.open("w", encoding="utf-8") as log:
            with subprocess.Popen([binary, "-c", str(config)], stdout=log, stderr=subprocess.STDOUT) as process:
                try:
                    yield port, lambda: output.read_text(encoding="utf-8")
                finally:
                    if process.poll() is None:
                        process.terminate()
                        try:
                            process.wait(timeout=3)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.wait()


def wait_for_broker_disconnect(read_logs, client_id):
    expected = f"Client {client_id} disconnected."
    deadline = monotonic() + TIMEOUT
    while monotonic() < deadline:
        if expected in read_logs():
            return
        sleep(0.1)
    raise TimeoutError(f"Mosquitto did not confirm {client_id} disconnected")


class ClientSession:
    def __init__(self, client_id, clean_session):
        self.client = mqtt.Client(
            mqtt.CallbackAPIVersion.VERSION2,
            client_id=client_id,
            protocol=mqtt.MQTTv311,
            clean_session=clean_session,
        )
        self.session_present = None
        self.suback = None
        self.messages = []
        self.client.on_connect = self.on_connect
        self.client.on_subscribe = self.on_subscribe
        self.client.on_message = self.on_message

    def on_connect(self, client, userdata, flags, reason_code, properties):
        require(not reason_code.is_failure, f"CONNECT failed: {reason_code}")
        self.session_present = flags.session_present

    def on_subscribe(self, client, userdata, mid, reason_codes, properties):
        self.suback = (mid, [code.value for code in reason_codes])

    def on_message(self, client, userdata, message):
        self.messages.append(message)

    def wait_for(self, condition, packet, timeout=TIMEOUT):
        deadline = monotonic() + timeout
        while not condition():
            require(monotonic() < deadline, f"Timed out waiting for {packet}")
            result = self.client.loop(timeout=0.1)
            require(result == mqtt.MQTT_ERR_SUCCESS, mqtt.error_string(result))

    @contextmanager
    def connect(self, port, timeout=TIMEOUT):
        try:
            result = self.client.connect(HOST, port)
            require(result == mqtt.MQTT_ERR_SUCCESS, mqtt.error_string(result))
            self.wait_for(lambda: self.session_present is not None, "CONNACK", timeout)
            yield self.client
        finally:
            self.client.disconnect()


def verify(port, read_logs):
    client_id = "lab-subscriber"
    topic = "lab/devices/demo/command"
    payload = b"mode=eco"

    first = ClientSession(client_id, clean_session=False)
    with first.connect(port) as subscriber:
        require(first.session_present is False, "Expected a new subscriber session")
        result, mid = subscriber.subscribe(topic, qos=1)
        require(result == mqtt.MQTT_ERR_SUCCESS, mqtt.error_string(result))
        first.wait_for(lambda: first.suback is not None, "SUBACK")
        require(first.suback == (mid, [1]), f"Expected QoS 1 SUBACK, got {first.suback}")
        print("First connect: session_present=False, SUBACK granted QoS 1")

    wait_for_broker_disconnect(read_logs, client_id)
    print("Mosquitto confirmed subscriber disconnected")

    publisher = ClientSession("lab-publisher", clean_session=True)
    with publisher.connect(port) as client:
        publication = client.publish(topic, payload, qos=1, retain=False)
        require(publication.rc == mqtt.MQTT_ERR_SUCCESS, mqtt.error_string(publication.rc))
        publisher.wait_for(publication.is_published, "PUBACK")
        print("Offline publish: PUBACK received, QoS 1, retain=False")

    resumed = ClientSession(client_id, clean_session=False)
    with resumed.connect(port):
        require(resumed.session_present is True, "Expected the broker to resume the session")
        resumed.wait_for(lambda: bool(resumed.messages), "queued PUBLISH")
        message = resumed.messages[0]
        require(
            (message.topic, message.payload, message.qos, message.retain)
            == (topic, payload, 1, False),
            "The queued message did not match the publish",
        )
        print(f"Reconnect: session_present=True, {message.topic} {message.payload!r}, "
              f"QoS {message.qos}, retain={message.retain}")

    clean = ClientSession(client_id, clean_session=True)
    with clean.connect(port):
        require(clean.session_present is False, "Clean session should not resume")
        print("Clean session control: session_present=False")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--mosquitto", help="Path to a local Mosquitto executable instead of Docker")
    args = parser.parse_args()
    broker = native_broker(args.mosquitto) if args.mosquitto else docker_broker()
    with broker as (port, read_logs):
        wait_for_broker(port)
        verify(port, read_logs)
