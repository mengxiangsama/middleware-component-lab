import argparse
from contextlib import ExitStack, contextmanager
import json
import os
from pathlib import Path
import socket
import subprocess
from tempfile import TemporaryDirectory
from time import monotonic, sleep
from urllib.request import ProxyHandler, build_opener


HOST = "127.0.0.1"
TOPIC = "shipment-events"
SOURCE = "shipment-file-source"
SINK = "shipment-file-sink"
INITIAL = ("shipment-101 CREATED", "shipment-102 CREATED")
APPENDED = ("shipment-103 CREATED", "shipment-104 CREATED")
LOCAL_HTTP = build_opener(ProxyHandler({}))


def local_ports():
    with ExitStack() as stack:
        sockets = [stack.enter_context(socket.socket()) for _ in range(3)]
        for listener in sockets:
            listener.bind((HOST, 0))
        return [listener.getsockname()[1] for listener in sockets]


def write_properties(path, properties):
    path.write_text("".join(f"{key}={value}\n" for key, value in properties.items()), encoding="utf-8")
    return path


def run_script(kafka, env, name, *args):
    result = subprocess.run(
        [kafka / "bin" / name, *args], cwd=kafka, env=env,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=50,
    )
    if result.returncode:
        raise RuntimeError(f"{name} exited with {result.returncode}:\n{result.stdout}")
    return result.stdout.strip()


@contextmanager
def start_service(kafka, env, log, name, *args):
    with log.open("wb") as output:
        process = subprocess.Popen(
            [kafka / "bin" / name, *args], cwd=kafka, env=env, stdout=output, stderr=subprocess.STDOUT,
        )
        try:
            yield process
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                    raise RuntimeError(f"{name} did not stop after SIGTERM")


def wait_for(process, log, description, condition):
    deadline = monotonic() + 45
    while process.poll() is None and monotonic() < deadline:
        try:
            if condition():
                return
        except OSError:
            pass
        sleep(0.2)
    tail = "\n".join(log.read_text(encoding="utf-8", errors="replace").splitlines()[-25:])
    raise RuntimeError(f"Could not verify {description}. Exit status: {process.poll()}\n{tail}")


def accepts_connections(port):
    with socket.create_connection((HOST, port), timeout=0.5):
        return True


def read_offsets(rest_url, name):
    with LOCAL_HTTP.open(f"{rest_url}/connectors/{name}/offsets", timeout=2) as response:
        return json.load(response)["offsets"]


def copied_and_committed(rest_url, source, sink, expected):
    output = sink.read_text(encoding="utf-8") if sink.exists() else ""
    if output and not output.endswith("\n"):
        return False
    actual = output.splitlines()
    if actual != list(expected[:len(actual)]):
        raise AssertionError(f"Unexpected file contents: {actual!r}")
    if actual != list(expected):
        return False
    return (read_offsets(rest_url, SOURCE) == [
        {"partition": {"filename": str(source)}, "offset": {"position": source.stat().st_size}}
    ] and read_offsets(rest_url, SINK) == [
        {"partition": {"kafka_topic": TOPIC, "kafka_partition": 0}, "offset": {"kafka_offset": len(expected)}}
    ])


def verify(kafka, workspace):
    broker_port, controller_port, rest_port = local_ports()
    broker_address = f"{HOST}:{broker_port}"
    rest_url = f"http://{HOST}:{rest_port}"
    plugin = kafka / "libs" / "connect-file-4.4.0.jar"
    if not plugin.is_file():
        raise FileNotFoundError(f"Use the Apache Kafka 4.4.0 binary distribution with {plugin.name}")
    env = dict(os.environ, KAFKA_HEAP_OPTS="-Xms128m -Xmx512m", LOG_DIR=str(workspace / "runtime-logs"))
    broker_config = write_properties(workspace / "server.properties", {
        "process.roles": "broker,controller",
        "node.id": 1,
        "controller.quorum.bootstrap.servers": f"{HOST}:{controller_port}",
        "controller.listener.names": "CONTROLLER",
        "listeners": f"PLAINTEXT://{broker_address},CONTROLLER://{HOST}:{controller_port}",
        "advertised.listeners": f"PLAINTEXT://{broker_address}",
        "listener.security.protocol.map": "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT",
        "inter.broker.listener.name": "PLAINTEXT",
        "log.dirs": workspace / "broker-data",
        "offsets.topic.replication.factor": 1,
        "group.initial.rebalance.delay.ms": 0,
    })
    offset_file = workspace / "connect-offsets"
    worker_config = write_properties(workspace / "connect.properties", {
        "bootstrap.servers": broker_address,
        "listeners": rest_url,
        "plugin.path": plugin,
        "key.converter": "org.apache.kafka.connect.storage.StringConverter",
        "value.converter": "org.apache.kafka.connect.storage.StringConverter",
        "offset.storage.file.filename": offset_file,
        "offset.flush.interval.ms": 250,
    })
    source = workspace / "shipments.in"
    sink = workspace / "shipments.out"
    source.write_text("\n".join(INITIAL) + "\n", encoding="ascii")
    source_config = write_properties(workspace / "source.properties", {
        "name": SOURCE,
        "connector.class": "org.apache.kafka.connect.file.FileStreamSourceConnector",
        "tasks.max": 1,
        "file": source,
        "topic": TOPIC,
    })
    sink_config = write_properties(workspace / "sink.properties", {
        "name": SINK,
        "connector.class": "org.apache.kafka.connect.file.FileStreamSinkConnector",
        "tasks.max": 1,
        "file": sink,
        "topics": TOPIC,
    })

    cluster_id = run_script(kafka, env, "kafka-storage.sh", "random-uuid")
    run_script(kafka, env, "kafka-storage.sh", "format", "--standalone", "-t", cluster_id, "-c", broker_config)
    broker_log = workspace / "broker.log"
    worker_args = (worker_config, source_config, sink_config)
    with start_service(kafka, env, broker_log, "kafka-server-start.sh", broker_config) as broker:
        wait_for(broker, broker_log, "the Kafka listener", lambda: accepts_connections(broker_port))
        run_script(kafka, env, "kafka-topics.sh", "--bootstrap-server", broker_address, "--create",
                   "--topic", TOPIC, "--partitions", "1", "--replication-factor", "1")
        first_log = workspace / "connect-first.log"
        with start_service(kafka, env, first_log, "connect-standalone.sh", *worker_args) as worker:
            wait_for(worker, first_log, "the first two records and committed offsets",
                     lambda: copied_and_committed(rest_url, source, sink, INITIAL))
            first_position = source.stat().st_size
        if "Kafka Connect stopped" not in first_log.read_text(encoding="utf-8"):
            raise AssertionError("The first worker did not log a graceful shutdown")
        if not offset_file.is_file():
            raise AssertionError("Standalone worker did not persist its source offset file")
        if sink.read_text(encoding="utf-8").splitlines() != list(INITIAL):
            raise AssertionError("The first shutdown changed the expected sink output")
        with source.open("a", encoding="ascii") as stream:
            stream.write("\n".join(APPENDED) + "\n")
        restart_log = workspace / "connect-restarted.log"
        with start_service(kafka, env, restart_log, "connect-standalone.sh", *worker_args) as worker:
            wait_for(worker, restart_log, "all four records and committed restart offsets",
                     lambda: copied_and_committed(rest_url, source, sink, INITIAL + APPENDED))

    final_rows = sink.read_text(encoding="utf-8").splitlines()
    if final_rows != list(INITIAL + APPENDED):
        raise AssertionError(f"Unexpected final sink output: {final_rows!r}")
    print(f"Before restart: 2 sink rows, source position={first_position}, sink next offset=2")
    print(f"After restart: 4 sink rows, source position={source.stat().st_size}, sink next offset=4")
    print("\n".join(final_rows))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("kafka_home", type=Path, help="Extracted Apache Kafka 4.4.0 binary distribution")
    arguments = parser.parse_args()
    with TemporaryDirectory(prefix="kafka-connect-filestream-") as directory:
        verify(arguments.kafka_home.resolve(), Path(directory))
