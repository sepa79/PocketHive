#!/usr/bin/env python3
"""Exercise the packaged standalone TCP workspace HTTP API across process death.

Uses only a test-owned application, temporary data directory and loopback ports.
This proves process restart, not host power-loss durability or deployed ingress.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--auth-jar", type=Path,
                        help="Select POCKETHIVE and start this packaged auth-service in DEV mode")
    args = parser.parse_args()
    jar = args.jar.resolve()
    auth = "Basic " + base64.b64encode(f"workspace-proof:{secrets.token_urlsafe(24)}".encode()).decode()
    password = base64.b64decode(auth[6:]).decode().split(":", 1)[1]
    process = None
    log = None
    auth_process = None
    auth_log = None
    provider = "POCKETHIVE" if args.auth_jar else "NATIVE"
    expected_owner = ("POCKETHIVE:11111111-1111-1111-1111-111111111111"
                      if args.auth_jar else "NATIVE:workspace-proof")
    with tempfile.TemporaryDirectory(prefix="ph-workspace-proof-") as directory:
        root = Path(directory)
        environment = dict(os.environ, POCKETHIVE_TCP_MOCK_DASHBOARD_USERNAME="workspace-proof",
                           POCKETHIVE_TCP_MOCK_DASHBOARD_PASSWORD=password)

        environment.pop("POCKETHIVE_AUTH_SERVICE_URL", None)

        def http_port(child, logfile):
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                if child.poll() is not None:
                    raise RuntimeError("Packaged application failed startup; credentials are not printed")
                match = re.search(r"Tomcat started on port (\d+)", logfile.read_text())
                if match:
                    return f"http://127.0.0.1:{match.group(1)}"
                time.sleep(0.1)
            raise TimeoutError("Application did not start within 45 seconds")

        def terminate(child, crash=False):
            if child is not None and child.poll() is None:
                child.kill() if crash else child.terminate()
                try:
                    child.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=5)

        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            tcp_port = reservation.getsockname()[1]

        def stop(crash=False):
            nonlocal process, log
            terminate(process, crash)
            if log is not None:
                log.close()

        def start(number):
            nonlocal process, log
            logfile = root / f"application-{number}.log"
            log = logfile.open("w")
            process = subprocess.Popen([
                "java", "-jar", str(jar), "--server.address=127.0.0.1", "--server.port=0",
                f"--tcp-mock.auth.provider={provider}", f"--tcp-mock.port={tcp_port}",
                f"--tcp-mock.data-directory={root / 'data'}",
            ], cwd=root, env=environment, stdout=log, stderr=subprocess.STDOUT)
            base = http_port(process, logfile)
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError("TCP application exited before listener became ready")
                if f"TCP Mock Server started on port {tcp_port}" in logfile.read_text():
                    return base
                time.sleep(0.1)
            raise TimeoutError("TCP listener did not start within 45 seconds")

        def request(base, method, path, body=None, authenticated=True):
            headers = {"Content-Type": "application/json"}
            if authenticated:
                headers["Authorization"] = auth
            payload = None if body is None else json.dumps(body).encode()
            try:
                response = urlopen(Request(base + path, data=payload, headers=headers, method=method), timeout=5)
            except HTTPError as error:
                response = error
            with response:
                raw = response.read()
                return response.status, raw

        def echo(connection):
            message = b"ECHO auth-independence\n"
            connection.sendall(message)
            response = bytearray()
            while not response.endswith(b"\n"):
                part = connection.recv(1024)
                if not part:
                    raise AssertionError("TCP connection closed before response")
                response.extend(part)
            assert response == message

        try:
            if args.auth_jar:
                logfile = root / "auth-service.log"
                auth_log = logfile.open("w")
                auth_process = subprocess.Popen([
                    "java", "-jar", str(args.auth_jar.resolve()),
                    "--server.address=127.0.0.1", "--server.port=0",
                    "--pockethive.auth-service.provider=DEV",
                ], cwd=root, env=environment, stdout=auth_log, stderr=subprocess.STDOUT)
                auth_base = http_port(auth_process, logfile)
                status, body = request(auth_base, "POST", "/api/auth/dev/login",
                                       {"username": "local-admin"}, authenticated=False)
                assert status == 200
                auth = "Bearer " + json.loads(body)["accessToken"]
                environment["POCKETHIVE_AUTH_SERVICE_URL"] = auth_base
            first = start(1)
            status, body = request(first, "GET", "/api/auth/config", authenticated=False)
            assert status == 200 and json.loads(body) == {"provider": provider}
            assert request(first, "GET", "/api/workspaces", authenticated=False)[0] == 401
            status, body = request(first, "POST", "/api/workspaces", {"name": "Retain after crash", "shared": False})
            assert status == 201
            created = json.loads(body)
            assert created["owner"] == expected_owner
            status, body = request(first, "PUT", "/api/workspaces/" + created["id"], {"name": "Renamed before crash", "shared": True})
            assert status == 200
            assert request(first, "DELETE", "/api/workspaces/default")[0] == 409
            status, body = request(first, "GET", "/api/workspaces")
            assert status == 200
            expected = json.loads(body)
            stop(crash=True)
            second = start(2)
            status, body = request(second, "GET", "/api/workspaces")
            assert status == 200 and json.loads(body) == expected
            assert request(second, "DELETE", "/api/workspaces/" + created["id"])[0] == 204
            stop(crash=True)
            third = start(3)
            status, body = request(third, "GET", "/api/workspaces")
            remaining = json.loads(body)
            assert status == 200 and len(remaining) == 1 and remaining[0]["defaultWorkspace"]
            assert request(third, "DELETE", "/api/workspaces/" + created["id"])[0] == 404
            status, page = request(third, "GET", "/", authenticated=False)
            assert status == 200 and b"Entries survive server restarts" in page
            with socket.create_connection(("127.0.0.1", tcp_port), timeout=5) as connection:
                echo(connection)
                if args.auth_jar:
                    saved_auth = auth
                    auth = "Basic " + base64.b64encode(b"admin:admin").decode()
                    assert request(third, "GET", "/api/workspaces")[0] == 401
                    auth = saved_auth
                    before = (root / "data" / "workspace-catalogue.json").read_bytes()
                    terminate(auth_process)
                    assert request(third, "POST", "/api/workspaces",
                                   {"name": "Must not be created", "shared": False})[0] == 503
                    assert (root / "data" / "workspace-catalogue.json").read_bytes() == before
                    echo(connection)
                    with socket.create_connection(("127.0.0.1", tcp_port), timeout=5) as fresh:
                        echo(fresh)
            result = {"passed": True, "processStarts": 3, "forcedProcessDeaths": 2,
                      "provider": provider, "tcpEchoVerified": True,
                      "authOutageTcpVerified": bool(args.auth_jar),
                      "retained": ["identity", "owner", "name", "shared", "order", "default policy", "deletion"],
                      "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
                      "scope": "packaged standalone HTTP component; no host-crash or deployed-ingress claim"}
            if args.auth_jar:
                result["authJarSha256"] = hashlib.sha256(args.auth_jar.read_bytes()).hexdigest()
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(result, indent=2) + "\n")
            print(json.dumps(result))
        finally:
            stop()
            terminate(auth_process)
            if auth_log is not None:
                auth_log.close()


if __name__ == "__main__":
    main()
