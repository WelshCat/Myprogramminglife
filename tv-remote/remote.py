#!/usr/bin/env python3
"""삼성 UN32ES6400F (2012 ES 시리즈) 리모컨 — 웹 서버 + 명령줄 도구.

사용법:
    python3 remote.py discover                     # 네트워크에서 TV 찾기
    python3 remote.py serve --tv 192.168.0.20      # 웹 리모컨 실행 (기본 포트 8080)
    python3 remote.py key --tv 192.168.0.20 VOLUP MUTE   # 명령줄로 키 보내기

외부 라이브러리 없이 Python 3.8+ 표준 라이브러리만 사용한다.
"""

from __future__ import annotations

import argparse
import json
import logging
import mimetypes
import socket
import sys
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from samsung_legacy import (AccessDenied, AuthTimeout, SamsungError,
                            SamsungLegacyRemote, discover)

STATIC_DIR = Path(__file__).resolve().parent / "static"
log = logging.getLogger("remote")


def make_handler(remote: SamsungLegacyRemote):
    class Handler(BaseHTTPRequestHandler):
        server_version = "SamsungRemote/1.0"

        def log_message(self, fmt, *args):
            log.debug("%s - " + fmt, self.address_string(), *args)

        def _json(self, status: int, body: dict) -> None:
            data = json.dumps(body, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            path = self.path.split("?", 1)[0]
            if path == "/api/status":
                return self._json(200, {"tv": remote.host, "connected": remote.connected})
            if path == "/":
                path = "/index.html"
            file = (STATIC_DIR / path.lstrip("/")).resolve()
            if STATIC_DIR not in file.parents or not file.is_file():
                return self._json(404, {"error": "not found"})
            data = file.read_bytes()
            ctype = mimetypes.guess_type(file.name)[0] or "application/octet-stream"
            if file.suffix == ".webmanifest":
                ctype = "application/manifest+json"
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_POST(self):
            if self.path != "/api/key":
                return self._json(404, {"error": "not found"})
            try:
                length = int(self.headers.get("Content-Length", 0))
                body = json.loads(self.rfile.read(length) or b"{}")
                keys = body.get("keys") or [body.get("key")]
                keys = [k for k in keys if isinstance(k, str) and k]
                if not keys:
                    raise ValueError
            except (ValueError, json.JSONDecodeError):
                return self._json(400, {"error": '{"key": "KEY_VOLUP"} 형식으로 보내주세요'})
            try:
                for k in keys:
                    remote.send_key(k)
                log.info("전송: %s", ", ".join(keys))
                return self._json(200, {"ok": True, "sent": keys})
            except AccessDenied as e:
                return self._json(HTTPStatus.FORBIDDEN, {"error": str(e)})
            except AuthTimeout as e:
                return self._json(HTTPStatus.GATEWAY_TIMEOUT, {"error": str(e)})
            except SamsungError as e:
                return self._json(HTTPStatus.BAD_GATEWAY, {"error": str(e)})

    return Handler


def lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def cmd_discover(args) -> int:
    print("삼성 TV를 찾는 중... (TV가 켜져 있어야 합니다)")
    tvs = discover(args.timeout)
    if not tvs:
        print("찾지 못했습니다. TV의 IP를 직접 확인하세요: "
              "TV 메뉴 > 네트워크 > 네트워크 상태")
        return 1
    for tv in tvs:
        print(f"  {tv['ip']:15}  {tv['server']}")
    return 0


def cmd_key(args) -> int:
    remote = SamsungLegacyRemote(args.tv, args.port, name=args.name)
    try:
        for k in args.keys:
            remote.send_key(k)
            print("전송:", k)
    except SamsungError as e:
        print("오류:", e, file=sys.stderr)
        return 1
    finally:
        remote.close()
    return 0


def cmd_serve(args) -> int:
    remote = SamsungLegacyRemote(args.tv, args.port, name=args.name)
    httpd = ThreadingHTTPServer((args.bind, args.http_port), make_handler(remote))
    print(f"TV: {args.tv}:{args.port}")
    print(f"휴대폰 브라우저에서 열기:  http://{lan_ip()}:{args.http_port}")
    print("처음 버튼을 누르면 TV 화면에 허용 팝업이 뜹니다 → '허용' 선택. 종료: Ctrl+C")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        remote.close()
        httpd.server_close()
    return 0


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="삼성 ES 시리즈 TV 리모컨")
    p.add_argument("-v", "--verbose", action="store_true")
    sub = p.add_subparsers(dest="cmd", required=True)

    d = sub.add_parser("discover", help="네트워크에서 삼성 TV 찾기")
    d.add_argument("--timeout", type=float, default=3.0)
    d.set_defaults(func=cmd_discover)

    def tv_args(sp):
        sp.add_argument("--tv", required=True, help="TV IP 주소")
        sp.add_argument("--port", type=int, default=55000, help="TV 포트 (기본 55000)")
        sp.add_argument("--name", default="Python Remote", help="TV에 표시될 리모컨 이름")

    s = sub.add_parser("serve", help="웹 리모컨 서버 실행")
    tv_args(s)
    s.add_argument("--bind", default="0.0.0.0")
    s.add_argument("--http-port", type=int, default=8080)
    s.set_defaults(func=cmd_serve)

    k = sub.add_parser("key", help="키 코드 전송 (예: VOLUP, KEY_MUTE)")
    tv_args(k)
    k.add_argument("keys", nargs="+")
    k.set_defaults(func=cmd_key)

    args = p.parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s")
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
