import argparse
from collections import deque
import os
from pathlib import Path
import queue
import shutil
import subprocess
import threading
from time import monotonic


JAR = Path(__file__).resolve().parent / "target" / "grpc-health-probe-lab.jar"
PROBE_RESULTS = {
    "SERVING": (0, "status: SERVING"),
    "NOT_SERVING": (4, "NOT_SERVING"),
    "NotFound": (3, "code = NotFound"),
}


def collect_output(stream, replies):
    try:
        for line in stream:
            replies.put(line.strip())
    finally:
        replies.put(None)


def await_reply(replies, prefix):
    deadline = monotonic() + 15
    recent = deque(maxlen=12)
    while monotonic() < deadline:
        try:
            line = replies.get(timeout=max(0, deadline - monotonic()))
        except queue.Empty:
            break
        if line is None:
            break
        if line.startswith(prefix):
            return line[len(prefix):]
        recent.append(line)
    raise RuntimeError(f"Java fixture did not report {prefix}. Recent output: {list(recent)}")


def send(server, command):
    server.stdin.write(command + "\n")
    server.stdin.flush()


def set_state(server, replies, command, expected):
    send(server, command)
    observed = await_reply(replies, "STATE=")
    if observed != expected:
        raise RuntimeError(f"{command} returned STATE={observed}, expected {expected}")


def check_probe(probe, address, service, expected):
    command = [probe, f"-addr={address}", "-connect-timeout=2s", "-rpc-timeout=2s"]
    if service:
        command.append(f"-service={service}")
    result = subprocess.run(command, capture_output=True, text=True, timeout=6)
    output = (result.stdout + result.stderr).strip()
    exit_code, marker = PROBE_RESULTS[expected]
    label = service or "unnamed"
    if result.returncode != exit_code or marker not in output:
        raise RuntimeError(
            f"{label}: expected {expected} (exit {exit_code}), "
            f"got exit {result.returncode} with output {output!r}"
        )
    return f"{label}={expected} (exit {result.returncode})"


def check_state(probe, address, stage, status):
    results = (
        check_probe(probe, address, "readiness", status),
        check_probe(probe, address, "liveness", "SERVING"),
        check_probe(probe, address, "", status),
    )
    print(f"{stage}: {', '.join(results)}", flush=True)


def verify(java, probe):
    with subprocess.Popen(
        [java, "-jar", str(JAR)],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    ) as server:
        replies = queue.Queue()
        reader = threading.Thread(target=collect_output, args=(server.stdout, replies), daemon=True)
        reader.start()
        try:
            reported_port = await_reply(replies, "PORT=")
            if not reported_port.isdecimal() or not 0 < int(reported_port) <= 65535:
                raise RuntimeError(f"Invalid fixture port: {reported_port!r}")
            address = f"127.0.0.1:{reported_port}"
            print(f"Java fixture at {address}", flush=True)

            check_state(probe, address, "startup", "NOT_SERVING")
            print(check_probe(probe, address, "unknown-service", "NotFound"), flush=True)
            for stage, command, status in (
                ("ready", "ready", "SERVING"),
                ("unready", "unready", "NOT_SERVING"),
                ("recovered", "ready", "SERVING"),
            ):
                set_state(server, replies, command, status)
                check_state(probe, address, stage, status)

            send(server, "stop")
            exit_code = server.wait(timeout=5)
            if exit_code != 0:
                raise RuntimeError(f"Java fixture exited with code {exit_code}")
        finally:
            if server.poll() is None:
                server.terminate()
                try:
                    server.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait(timeout=3)
            reader.join(timeout=2)
    print("PASS: all gRPC health checks matched and the fixture stopped")


def main():
    parser = argparse.ArgumentParser(description="Verify a local Java gRPC Health service with grpc_health_probe")
    parser.add_argument("--probe", default="grpc_health_probe", help="path to the grpc_health_probe executable")
    args = parser.parse_args()

    probe = shutil.which(args.probe)
    if probe is None:
        parser.error(f"Probe executable not found: {args.probe}")
    java_home = os.environ.get("JAVA_HOME")
    java_command = str(Path(java_home) / "bin" / "java") if java_home else "java"
    java = shutil.which(java_command)
    if java is None:
        parser.error(f"Java executable not found: {java_command}")
    if not JAR.is_file():
        parser.error(f"Build the fixture via Maven first: {JAR}")

    try:
        verify(java, probe)
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        parser.exit(1, f"Verification failed: {error}\n")


if __name__ == "__main__":
    main()
