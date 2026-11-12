# TV 리모컨 (크롬캐스트용 안드로이드 앱)

TV에 연결된 **크롬캐스트**를 통해 TV를 조작하는 안드로이드 앱입니다.
TV 자체가 네트워크에 연결되지 않아도, 크롬캐스트가 **HDMI-CEC**로 TV에 신호를 전달합니다.

<img src="icon/icon-512.png" width="96" alt="앱 아이콘">

- Android 8.0 이상, 외부 라이브러리 없음

## 지원 기기와 기능

| 기능 | 크롬캐스트 with Google TV (리모컨 있는 모델) | 크롬캐스트 (동글형) |
|---|---|---|
| TV 켜기 / 입력 전환 | ✅ | ✅ (대기 화면 앱 실행) |
| TV 끄기 | ✅ | ❌ |
| 방향키·확인·뒤로·홈·설정·입력 선택 | ✅ | ❌ |
| 음량 / 음소거 | ✅ TV 음량 (CEC 설정 필요) | ✅ 캐스트 음량 |
| 재생 / 일시정지 / 정지 / 탐색 | ✅ | ✅ 캐스트 중인 미디어 |
| 지금 재생 중 표시 | — | ✅ |

공통: 휴대폰 볼륨 버튼으로 음량 조절, 누르고 있으면 반복, 진동 피드백, 자동 재연결.
TV 자체 메뉴와 방송 채널은 크롬캐스트로 조작할 수 없습니다.

## 사용법

1. **TV 설정**: 메뉴 → 시스템 → **Anynet+ (HDMI-CEC) 켜기** (삼성 TV)
2. 휴대폰을 크롬캐스트와 **같은 Wi-Fi**에 연결합니다.
3. 앱을 열면 크롬캐스트를 자동으로 찾습니다. 목록에서 선택하세요.
   (안 보이면 "IP 주소 직접 입력" — Google Home 앱 → 기기 → 설정 → 기기 정보에서 확인)
4. **Google TV 모델**: 처음 한 번 TV 화면에 뜬 **6자리 코드**를 입력해 페어링합니다.

### Google TV에서 음량이 안 바뀔 때
크롬캐스트 설정 → 리모컨 및 액세서리 → TV 및 오디오 기기 제어 → 음량 제어를 **HDMI-CEC**로 설정하세요.
(적외선(IR)으로 설정돼 있으면 앱의 음량 버튼이 TV에 전달되지 않을 수 있습니다)

## APK 받기 / 빌드

GitHub에 푸시하면 **Actions** 가 자동으로 APK를 빌드합니다 (Actions → Android APK → Artifacts).

```bash
cd android
./gradlew assembleRelease     # → app/build/outputs/apk/release/app-release.apk
```

## 구조

```
app/src/main/java/io/github/welshcat/tvremote/
  GoogleTvRemote.kt   Android TV 리모컨 프로토콜 v2 (6467 페어링 / 6466 명령)
  CastRemote.kt       Google Cast 프로토콜 (8009) — TV 켜기, 음량, 미디어 제어
  DeviceDiscovery.kt  mDNS로 크롬캐스트 검색, IP 직접 입력 시 포트로 종류 판별
  Tls.kt              자체 서명 클라이언트 인증서 생성 (페어링용), TLS 연결
  Proto.kt            최소 protobuf 인코더/디코더
  MainActivity.kt     리모컨 화면, 페어링, 기기 선택
```

> 예전 버전(삼성 TV에 직접 연결, 55000 포트)은 git 기록과 `../tv-remote` 웹 버전에 남아 있습니다.
