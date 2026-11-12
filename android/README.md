# 삼성 TV 리모컨 (안드로이드 앱)

삼성 **UN32ES6400F** 등 2010~2013년형(C/D/E/F 시리즈) 삼성 TV를 휴대폰으로 조작하는 안드로이드 앱입니다.
PC나 서버 없이 휴대폰이 TV와 직접 통신합니다 (Wi-Fi, TCP 55000 포트).

<img src="icon/icon-512.png" width="96" alt="앱 아이콘">

- Android 8.0 이상
- 외부 라이브러리 없음 (Kotlin + 안드로이드 기본 기능만 사용)

## APK 받기

GitHub에 푸시하면 **Actions** 가 자동으로 APK를 빌드합니다.

1. GitHub 저장소 → **Actions** 탭 → **Android APK** 실행 결과 선택
2. 아래쪽 **Artifacts** 의 `samsung-tv-remote-apk` 다운로드 (zip)
3. 압축을 풀고 `app-release.apk` 를 휴대폰에 옮겨 설치
   (처음이면 "출처를 알 수 없는 앱 설치" 허용 필요)

직접 빌드하려면 Android Studio에서 `android/` 폴더를 열거나:

```bash
cd android
./gradlew assembleRelease     # → app/build/outputs/apk/release/app-release.apk
```

## 사용법

1. 휴대폰과 TV를 **같은 Wi-Fi(공유기)** 에 연결하고 TV를 켭니다.
2. 앱 첫 실행 시 설정 창에서 **자동 검색** 을 누르거나 TV IP를 직접 입력합니다.
   (TV 메뉴 → 네트워크 → 네트워크 상태에서 확인)
3. 아무 버튼이나 누르면 TV 화면에 연결 요청이 뜹니다 → **허용**.

## 기능

- 전원 끄기, 외부입력, HDMI, TV, 스마트허브, 메뉴, 도구, 정보, 가이드
- 방향키 / 확인 / 이전 / 나가기
- 음량·채널 (누르고 있으면 반복), 음소거, 채널목록, 이전채널
- 컬러 버튼(A/B/C/D), 재생 제어, 숫자 패드, 화면 크기·모드·자막 등 기타 키, 키 코드 직접 입력
- **휴대폰 볼륨 버튼으로 TV 음량 조절** (설정에서 끄기 가능)
- 진동 피드백, 연결 끊김 시 자동 재연결, SSDP 자동 검색
- 적응형 아이콘 + Android 13 테마 아이콘 지원

## 문제 해결

| 증상 | 해결 |
|---|---|
| "접근을 거부했습니다" | 예전에 '거부'를 눌렀던 경우. TV **메뉴 → 네트워크 → AllShare 설정**에서 이 기기를 **허용**으로 변경 |
| "연결할 수 없습니다" | TV가 켜져 있는지, 같은 Wi-Fi인지 확인. TV IP가 바뀌었을 수 있음 (공유기에서 TV IP 고정 추천) |
| 자동 검색이 안 됨 | 일부 공유기는 멀티캐스트를 막습니다. IP를 직접 입력하세요 |
| TV를 **켤** 수 없음 | ES 시리즈는 꺼진 TV를 네트워크로 켜는 기능이 없습니다. 끄기만 가능합니다 |

## 구조

```
app/src/main/java/io/github/welshcat/tvremote/
  SamsungRemote.kt   삼성 레거시 프로토콜, 자동 재연결, SSDP 검색 (안드로이드 의존성 없음)
  MainActivity.kt    리모컨 화면, 설정, 볼륨 버튼 처리
app/src/main/res/layout/activity_main.xml   버튼 배치 (tag = 보낼 키 코드)
icon/                아이콘 원본 SVG, 512px PNG, 미리보기
```

버튼을 추가하려면 `activity_main.xml` 에 버튼을 넣고 `android:tag="KEY_..."` 만 지정하면 됩니다.
누르고 있을 때 반복하려면 `android:tag="repeat:KEY_..."`.
