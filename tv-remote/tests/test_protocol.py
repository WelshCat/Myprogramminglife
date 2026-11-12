"""가짜 TV 서버로 프로토콜과 웹 API를 검증한다:  python3 -m unittest discover tests"""
import base64
import json
import socket
import struct
import sys
import threading
import unittest
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import samsung_legacy as sl  # noqa: E402
import remote  # noqa: E402


def recv_exact(conn, n):
    buf = b""
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            raise EOFError
        buf += chunk
    return buf


def read_packet(conn):
    recv_exact(conn, 1)
    app = recv_exact(conn, struct.unpack("<H", recv_exact(conn, 2))[0])
    payload = recv_exact(conn, struct.unpack("<H", recv_exact(conn, 2))[0])
    return app, payload


def tv_reply(payload, name=b"iapp.samsung"):
    return b"\x00" + struct.pack("<H", len(name)) + name + struct.pack("<H", len(payload)) + payload


class FakeTV(threading.Thread):
    def __init__(self, grant=True, wait_popup=False):
        super().__init__(daemon=True)
        self.grant, self.wait_popup = grant, wait_popup
        self.srv = socket.socket()
        self.srv.bind(("127.0.0.1", 0))
        self.srv.listen()
        self.port = self.srv.getsockname()[1]
        self.keys, self.auth = [], None
        self.connections = 0

    def run(self):
        while True:
            try:
                conn, _ = self.srv.accept()
            except OSError:
                return
            self.connections += 1
            threading.Thread(target=self.handle, args=(conn,), daemon=True).start()

    def handle(self, conn):
        with conn:
            try:
                app, payload = read_packet(conn)
                assert app == b"iphone..iapp.samsung"
                assert payload[:2] == b"\x64\x00"
                self.auth = payload
                if self.wait_popup:
                    conn.sendall(tv_reply(b"\x0a\x00\x02\x00\x00\x00"))
                conn.sendall(tv_reply(sl.RESP_GRANTED if self.grant else sl.RESP_DENIED))
                if not self.grant:
                    return
                while True:
                    _, payload = read_packet(conn)
                    assert payload[:3] == b"\x00\x00\x00"
                    n = struct.unpack("<H", payload[3:5])[0]
                    self.keys.append(base64.b64decode(payload[5:5 + n]).decode())
                    conn.sendall(tv_reply(sl.RESP_KEY_OK))
            except EOFError:
                pass

    def stop(self):
        self.srv.close()


class ProtocolTest(unittest.TestCase):
    def test_key_packet_layout(self):
        pkt = sl.build_key_packet("KEY_VOLUP")
        b64 = base64.b64encode(b"KEY_VOLUP")
        expected = (b"\x00\x14\x00iphone..iapp.samsung"
                    + struct.pack("<H", 5 + len(b64)) + b"\x00\x00\x00"
                    + struct.pack("<H", len(b64)) + b64)
        self.assertEqual(pkt, expected)

    def test_send_keys_and_popup(self):
        tv = FakeTV(wait_popup=True); tv.start()
        r = sl.SamsungLegacyRemote("127.0.0.1", tv.port, name="테스트", key_interval=0)
        r.send_key("KEY_VOLUP"); r.send_key("mute"); r.send_key("KEY_0")
        self.assertEqual(tv.keys, ["KEY_VOLUP", "KEY_MUTE", "KEY_0"])
        self.assertIn(base64.b64encode("테스트".encode()), tv.auth)
        self.assertEqual(tv.connections, 1)
        r.close(); tv.stop()

    def test_reconnect_after_drop(self):
        tv = FakeTV(); tv.start()
        r = sl.SamsungLegacyRemote("127.0.0.1", tv.port, key_interval=0)
        r.send_key("KEY_UP")
        r._sock.close()  # 연결 끊김 흉내
        r.send_key("KEY_DOWN")
        self.assertEqual(tv.keys, ["KEY_UP", "KEY_DOWN"])
        r.close(); tv.stop()

    def test_denied(self):
        tv = FakeTV(grant=False); tv.start()
        r = sl.SamsungLegacyRemote("127.0.0.1", tv.port, key_interval=0)
        with self.assertRaises(sl.AccessDenied):
            r.send_key("KEY_UP")
        tv.stop()

    def test_tv_off(self):
        s = socket.socket(); s.bind(("127.0.0.1", 0)); port = s.getsockname()[1]; s.close()
        r = sl.SamsungLegacyRemote("127.0.0.1", port, timeout=1, key_interval=0)
        with self.assertRaises(sl.SamsungError):
            r.send_key("KEY_UP")


class WebApiTest(unittest.TestCase):
    def test_http_api(self):
        tv = FakeTV(); tv.start()
        r = sl.SamsungLegacyRemote("127.0.0.1", tv.port, key_interval=0)
        httpd = ThreadingHTTPServer(("127.0.0.1", 0), remote.make_handler(r))
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{httpd.server_address[1]}"
        try:
            html = urllib.request.urlopen(base + "/").read().decode()
            self.assertIn("KEY_POWEROFF", html)
            req = urllib.request.Request(base + "/api/key", method="POST",
                                         data=json.dumps({"keys": ["KEY_CHUP", "KEY_CHDOWN"]}).encode(),
                                         headers={"Content-Type": "application/json"})
            self.assertTrue(json.loads(urllib.request.urlopen(req).read())["ok"])
            self.assertEqual(tv.keys, ["KEY_CHUP", "KEY_CHDOWN"])
            with self.assertRaises(urllib.error.HTTPError) as cm:
                urllib.request.urlopen(base + "/../remote.py")
            self.assertEqual(cm.exception.code, 404)
        finally:
            httpd.shutdown(); r.close(); tv.stop()


if __name__ == "__main__":
    unittest.main()
