#!/usr/bin/env python3
"""Black-box relay regression tests. Stdlib only; requires protoc and cargo build."""
import base64
import json
import os
from pathlib import Path
import secrets
import socket
import struct
import subprocess
import tempfile
import time
import unittest
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]

def proto(text):
    return subprocess.run(["protoc", "--encode=pulse.v1.Msg", "--proto_path=" + str(ROOT / "proto"), "pulse.proto"], input=text.encode(), capture_output=True, check=True).stdout

def decode(data):
    return subprocess.run(["protoc", "--decode=pulse.v1.Msg", "--proto_path=" + str(ROOT / "proto"), "pulse.proto"], input=data, capture_output=True, check=True).stdout.decode()

class WS:
    def __init__(self, port, path, token):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=3)
        key = base64.b64encode(secrets.token_bytes(16)).decode()
        self.sock.sendall((f"GET {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer {token}\r\n\r\n").encode())
        header = b""
        while not header.endswith(b"\r\n\r\n"):
            header += self.exact(1)
        assert header.startswith(b"HTTP/1.1 101"), header
    def exact(self, size):
        out = b""
        while len(out) < size:
            chunk = self.sock.recv(size - len(out))
            if not chunk: raise EOFError("websocket closed")
            out += chunk
        return out
    def send(self, payload):
        mask = secrets.token_bytes(4)
        length = len(payload)
        header = bytes([0x82, 0x80 | length]) if length < 126 else bytes([0x82, 0xFE]) + struct.pack("!H", length)
        self.sock.sendall(header + mask + bytes(b ^ mask[i % 4] for i,b in enumerate(payload)))
    def receive(self):
        while True:
            first, second = self.exact(2)
            size = second & 127
            if size == 126: size = struct.unpack("!H", self.exact(2))[0]
            if size == 127: size = struct.unpack("!Q", self.exact(8))[0]
            data = self.exact(size)
            if first & 15 == 2: return decode(data)
            if first & 15 == 8: raise EOFError("close frame")
    def close(self):
        self.sock.close()

class MonitoringE2E(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        probe = socket.socket(); probe.bind(("127.0.0.1",0)); cls.port = probe.getsockname()[1]; probe.close()
        cls.base = f"http://127.0.0.1:{cls.port}"
        cls.admin = secrets.token_hex(16)
        env = os.environ.copy()
        # Tests never use the developer's push credentials.
        for key in list(env):
            if key.startswith("PULSE_APNS_") or key == "PULSE_FCM_PROJECT_ID": env.pop(key)
        env.update(PULSE_BIND=f"127.0.0.1:{cls.port}", PULSE_ADMIN_TOKEN=cls.admin, PULSE_DB=str(Path(cls.temp.name)/"test.db"))
        cls.log = open(Path(cls.temp.name)/"relay.log", "w+")
        binary = os.environ.get("PULSE_TEST_RELAY_BIN", str(ROOT / "relay/target/debug/pulse-relay"))
        cls.process = subprocess.Popen([binary], env=env, stdout=cls.log, stderr=cls.log)
        for _ in range(200):
            try:
                urllib.request.urlopen(cls.base+"/health", timeout=1).close(); break
            except OSError:
                if cls.process.poll() is not None: raise RuntimeError("relay failed to start")
                time.sleep(.05)
        else: raise RuntimeError("relay did not start")
        cls.client = cls.call("/api/tokens", {"label":"phone","role":"client"}, admin=True)[1]["token"]
    @classmethod
    def tearDownClass(cls):
        cls.process.terminate(); cls.process.wait(timeout=5); cls.log.close(); cls.temp.cleanup()
    @classmethod
    def call(cls, path, body=None, admin=False, method=None, auth=True):
        headers = {"Content-Type":"application/json"}
        if auth: headers.update({"X-Admin-Token":cls.admin} if admin else {"Authorization":"Bearer "+cls.client})
        req = urllib.request.Request(cls.base+path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=3) as response:
                raw=response.read(); return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            try: return e.code, e.read().decode()
            finally: e.close()
    def agent(self, label):
        token=self.call("/api/tokens",{"label":label,"role":"agent"},admin=True)[1]["token"]
        ws=WS(self.port,"/ws/agent",token); self.addCleanup(ws.close)
        self.assertIn("hello_ack",ws.receive()); ws.send(proto('hello { hostname: "test" agent_version: "1" }'))
        return ws
    def wait_incidents(self, agent, predicate):
        for _ in range(100):
            rows=self.call("/api/incidents?agent_id="+agent)[1]
            if predicate(rows): return rows
            time.sleep(.02)
        self.fail("expected incident transition did not arrive")
    def test_threshold_history_and_recovery(self):
        ws=self.agent("threshold")
        policy=self.call("/api/alert-policy?agent_id=threshold")[1]
        policy.update(sustain_seconds=0,cpu_percent=80)
        self.assertEqual(self.call("/api/alert-policy?agent_id=threshold",policy,method="PUT")[0],204)
        ws.send(proto('metrics { cpu_usage_percent: 99 mem_total: 100 mem_used: 20 }'))
        self.wait_incidents("threshold",lambda r:any(i["kind"]=="CPU_HIGH" and i["resolved_at"] is None for i in r))
        points=self.call("/api/metrics?agent_id=threshold&hours=1")[1]
        self.assertEqual(points[-1]["cpu_percent"],99)
        ws.send(proto('metrics { cpu_usage_percent: 20 mem_total: 100 mem_used: 20 }'))
        self.wait_incidents("threshold",lambda r:any(i["kind"]=="CPU_HIGH" and i["resolved_at"] is not None for i in r))
        self.assertEqual(self.call("/api/metrics?agent_id=threshold&hours=0")[0],400)
        policy["cpu_percent"]=101
        self.assertEqual(self.call("/api/alert-policy?agent_id=threshold",policy,method="PUT")[0],400)
    def test_healthcheck_starting_is_not_recovery(self):
        ws=self.agent("health")
        def containers(health):return proto(f'container_list {{ containers {{ id: "c" name: "web" state: RUNNING health: "{health}" compose_project: "prod" compose_service: "web" }} }}')
        ws.send(containers("unhealthy"))
        self.wait_incidents("health",lambda r:len(r)==1)
        ws.send(containers("starting")); time.sleep(.05)
        self.assertIsNone(self.call("/api/incidents?agent_id=health")[1][0]["resolved_at"])
        ws.send(containers("healthy"))
        self.wait_incidents("health",lambda r:r and r[0]["resolved_at"] is not None)
    def test_client_disconnect_cancels_log_stream(self):
        ws=self.agent("logs")
        client=WS(self.port,"/ws/client?agent_id=logs",self.client)
        client.send(proto('target: "logs" logs_request { container_id: "web" stream_id: "unique" tail_lines: 200 follow: true }'))
        self.assertIn('stream_id: "unique"',ws.receive())
        client.close()
        self.assertIn('cancel: true',ws.receive())
    def test_agent_heartbeat_and_authorization(self):
        ws=self.agent("heartbeat"); ws.send(proto('id: 17 ping { seq: 42 }'))
        pong=ws.receive(); self.assertIn("pong",pong); self.assertIn("seq: 42",pong)
        for path in ["/api/agent-ids","/api/incidents","/api/metrics?agent_id=a&hours=1","/api/alert-policy?agent_id=a"]:
            self.assertEqual(self.call(path,auth=False)[0],401)
        self.assertEqual(self.call("/api/alert-policy?agent_id=a",{},method="PUT",auth=False)[0],401)
    def test_restart_loop_is_separate_from_single_exit(self):
        ws=self.agent("restart")
        for _ in range(3):
            ws.send(proto('event_alert { kind: CONTAINER_DIED container_id: "c" container_name: "worker" message: "exit 1" }'))
            ws.send(proto('event_alert { kind: CONTAINER_STARTED container_id: "c" container_name: "worker" }'))
        self.wait_incidents("restart",lambda r:any(i["kind"]=="RESTART_LOOP" and i["resolved_at"] is None for i in r))
    def test_offline_agents_are_available_for_push_registration(self):
        self.call("/api/tokens",{"label":"offline-agent","role":"agent"},admin=True)
        self.assertIn("offline-agent",self.call("/api/agent-ids")[1])

    def test_multi_server_device_registration(self):
        for agent in ["one","two"]:
            self.assertEqual(self.call("/api/devices",{"agent_id":agent,"fcm_token":"test-device"})[0],204)
        self.assertEqual(self.call("/api/devices",{"agent_id":"a","apns_token":"invalid"})[0],400)
        self.assertEqual(self.call("/api/devices",{"agent_id":"a","apns_token":"ab"*32,"sandbox":True})[0],204)

if __name__=="__main__": unittest.main(verbosity=2)
