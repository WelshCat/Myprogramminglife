"""삼성 레거시(2010~2013년형, C/D/E/F 시리즈) TV 리모컨 프로토콜.

UN32ES6400F 같은 2012년형 ES 시리즈는 TCP 55000 포트로 키 코드를 받는다.
패킷 구조 (리틀 엔디언 길이 + 내용):

    0x00 | len(appstring) | appstring | len(payload) | payload

인증 payload:  0x64 0x00 | b64(내 IP) | b64(기기 ID) | b64(리모컨 이름)
키 payload:    0x00 0x00 0x00 | b64("KEY_VOLUP")
"""

from __future__ import annotations

import base64
import logging
import socket
import struct
import threading
import time

log = logging.getLogger(__name__)

DEFAULT_PORT = 55000
APP_STRING = "iphone..iapp.samsung"


class SamsungError(Exception):
    pass


class AccessDenied(SamsungError):
    """TV 화면에서 '거부'를 눌렀거나, 이전에 거부된 기기."""


class AuthTimeout(SamsungError):
    """TV 화면의 허용 팝업에 응답하지 않음."""


class ConnectionClosed(SamsungError):
    pass


def _field(data: bytes) -> bytes:
    """2바이트 리틀 엔디언 길이 + 데이터."""
    return struct.pack("<H", len(data)) + data


def _b64(text: str) -> bytes:
    return base64.b64encode(text.encode("utf-8"))


def build_packet(payload: bytes, app_string: str = APP_STRING) -> bytes:
    return b"\x00" + _field(app_string.encode()) + _field(payload)


def build_auth_packet(remote_ip: str, remote_id: str, remote_name: str,
                      app_string: str = APP_STRING) -> bytes:
    payload = (b"\x64\x00" + _field(_b64(remote_ip)) + _field(_b64(remote_id))
               + _field(_b64(remote_name)))
    return build_packet(payload, app_string)


def build_key_packet(key: str, app_string: str = APP_STRING) -> bytes:
    return build_packet(b"\x00\x00\x00" + _field(_b64(key)), app_string)


# TV 응답 payload
RESP_GRANTED = b"\x64\x00\x01\x00"
RESP_DENIED = b"\x64\x00\x00\x00"
RESP_KEY_OK = b"\x00\x00\x00\x00"


class SamsungLegacyRemote:
    """TV와의 연결을 유지하고, 끊기면 자동으로 다시 연결한다 (스레드 안전)."""

    def __init__(self, host: str, port: int = DEFAULT_PORT,
                 name: str = "Python Remote", remote_id: str = "python-tv-remote",
                 timeout: float = 5.0, auth_timeout: float = 30.0,
                 key_interval: float = 0.05):
        self.host = host
        self.port = port
        self.name = name
        self.remote_id = remote_id
        self.timeout = timeout
        self.auth_timeout = auth_timeout
        self.key_interval = key_interval
        self._sock: socket.socket | None = None
        self._lock = threading.Lock()

    # ---------- 저수준 I/O ----------
    def _recv_exact(self, n: int) -> bytes:
        assert self._sock is not None
        buf = b""
        while len(buf) < n:
            chunk = self._sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionClosed("TV가 연결을 끊었습니다")
            buf += chunk
        return buf

    def _read_response(self) -> bytes:
        self._recv_exact(1)                                   # 0x00 or 0x01
        name_len = struct.unpack("<H", self._recv_exact(2))[0]
        self._recv_exact(name_len)                            # TV appstring
        resp_len = struct.unpack("<H", self._recv_exact(2))[0]
        return self._recv_exact(resp_len)

    # ---------- 연결 / 인증 ----------
    def _connect(self) -> None:
        self.close()
        log.info("TV %s:%d 에 연결 중...", self.host, self.port)
        sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        self._sock = sock
        local_ip = sock.getsockname()[0]
        sock.sendall(build_auth_packet(local_ip, self.remote_id, self.name))

        deadline = time.monotonic() + self.auth_timeout
        while True:
            try:
                resp = self._read_response()
            except socket.timeout:
                if time.monotonic() > deadline:
                    self.close()
                    raise AuthTimeout("TV 화면에서 리모컨 연결을 '허용'해 주세요")
                continue
            if resp == RESP_GRANTED:
                log.info("TV 접근 허용됨")
                return
            if resp == RESP_DENIED:
                self.close()
                raise AccessDenied(
                    "TV가 접근을 거부했습니다. TV 메뉴 > 네트워크 > AllShare 설정"
                    "(또는 기기 관리자)에서 이 기기를 '허용'으로 바꿔 주세요")
            if resp[:1] == b"\x0a":       # 허용 팝업 표시 중, 사용자 대기
                log.info("TV 화면의 허용 팝업을 기다리는 중...")
                continue
            if resp[:1] == b"\x65":
                self.close()
                raise AuthTimeout("TV에서 인증이 취소되었습니다")
            log.debug("알 수 없는 인증 응답: %r", resp)

    def close(self) -> None:
        if self._sock is not None:
            try:
                self._sock.close()
            except OSError:
                pass
            self._sock = None

    @property
    def connected(self) -> bool:
        return self._sock is not None

    def connect(self) -> None:
        with self._lock:
            self._connect()

    # ---------- 키 전송 ----------
    def send_key(self, key: str) -> None:
        key = key.strip().upper()
        if not key.startswith("KEY_"):
            key = "KEY_" + key
        with self._lock:
            for attempt in (1, 2):
                try:
                    if self._sock is None:
                        self._connect()
                    self._sock.sendall(build_key_packet(key))
                    # TV는 키마다 응답을 보내지만 내용은 무시해도 된다.
                    try:
                        self._read_response()
                    except socket.timeout:
                        # 응답이 중간에 끊겼을 수 있으니 다음 키에서 새로 연결
                        self.close()
                    time.sleep(self.key_interval)
                    return
                except (AccessDenied, AuthTimeout):
                    raise
                except (OSError, ConnectionClosed) as e:
                    self.close()
                    if attempt == 2:
                        raise SamsungError(
                            f"TV({self.host})에 연결할 수 없습니다: {e}. "
                            "TV가 켜져 있고 같은 네트워크에 있는지 확인하세요") from e
                    log.info("연결이 끊겨 재연결합니다 (%s)", e)


def discover(timeout: float = 3.0) -> list[dict]:
    """SSDP로 같은 네트워크의 삼성 TV를 찾는다."""
    msg = ("M-SEARCH * HTTP/1.1\r\n"
           "HOST: 239.255.255.250:1900\r\n"
           'MAN: "ssdp:discover"\r\n'
           "MX: 2\r\n"
           "ST: urn:samsung.com:device:RemoteControlReceiver:1\r\n\r\n").encode()
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
    sock.settimeout(timeout)
    found: dict[str, dict] = {}
    try:
        sock.sendto(msg, ("239.255.255.250", 1900))
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            try:
                data, (ip, _) = sock.recvfrom(4096)
            except socket.timeout:
                break
            headers = {}
            for line in data.decode(errors="ignore").split("\r\n")[1:]:
                if ":" in line:
                    k, v = line.split(":", 1)
                    headers[k.strip().lower()] = v.strip()
            found[ip] = {"ip": ip, "server": headers.get("server", ""),
                         "location": headers.get("location", "")}
    finally:
        sock.close()
    return list(found.values())
