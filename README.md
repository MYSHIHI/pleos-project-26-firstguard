# 퍼스트가드 (FIRST GUARD)

## 1. 앱 이름 및 한 줄 소개

**퍼스트가드 (FIRST GUARD)**: 차량 신호로 사고를 감지하면 탑승자에게 한 번만 묻고, 나머지 초동대응은 스스로 처리하는 Android Automotive 앱

프로젝트명은 **운전자와 동승자를 위한 사고 감지·상황 판단·통합 초동대응 서비스**입니다.

에어백 전개나 급정지를 감지하면 앱이 스스로 사건을 열고 괜찮은지 한 번만 묻습니다. 그 뒤 탑승자와 도로 상황을 추정하고, 기어가 P가 되면 문 잠금 해제·트렁크·창문을 자동으로 처리합니다. 기관별 신고 카드 전송(모의), 위치 첨부, 사건 기록도 앱이 합니다. 운전자에게는 대피, 비상등, 119 통화, 현장 촬영처럼 사람이 직접 해야 하는 일만 남깁니다.

| 항목 | 내용 |
| --- | --- |
| 앱 버전 | 0.2.0 (versionCode 2) |
| 패키지 | `io.github.myshihi.pleosproject26.n01crashresponse` |
| 시연 영상 | [demo-video/퍼스트가드-시연영상.mp4](demo-video/퍼스트가드-시연영상.mp4) (70초, 자막 `.srt` 포함) |

### 폴더 구성

| 위치 | 내용 |
| --- | --- |
| `apk/` | 바로 설치할 수 있는 release APK |
| `project/` | 앱 소스 전체 (Android Studio 프로젝트) |
| `demo-video/` | 시연 영상과 자막 |

## 2. 실행 방법

### 2.1 APK로 실행 (권장)

1. Pleos Connect 에뮬레이터를 켭니다. 준비 방법은 [3. 테스트 환경 및 필요한 설정](#3-테스트-환경-및-필요한-설정)에 있습니다.
2. 저장소 최상위 폴더에서 APK를 설치하고 실행합니다. `-g`를 붙이면 속도·위치 권한이 설치할 때 함께 허용됩니다.

```bash
adb install -r -g apk/설계3.5팀_FirstGuard_release.apk
adb shell am start -n io.github.myshihi.pleosproject26.n01crashresponse/.MainActivity
```

에뮬레이터 앱 목록에서 **퍼스트가드**를 눌러 실행해도 됩니다. `-g` 없이 설치했다면 첫 실행 때 나오는 권한 요청을 허용해 주세요. 속도 권한이 없으면 급정지 감지가 꺼집니다.

같은 패키지가 다른 서명(디버그 빌드 등)으로 이미 설치돼 있으면 설치가 거부됩니다. 이때는 먼저 지운 뒤 다시 설치합니다.

```bash
adb uninstall io.github.myshihi.pleosproject26.n01crashresponse
```

### 2.2 소스에서 빌드해 실행

1. Android Studio에서 **`project` 폴더**를 엽니다(Open).
2. Gradle JDK는 Android Studio에 들어 있는 JBR(JDK 17 이상)을 씁니다.
3. 실행 기기로 Pleos Connect 에뮬레이터를 고르고 `app`을 실행(Run)합니다.

명령줄로 빌드하려면 다음과 같이 합니다.

```bash
cd project
./gradlew :app:assembleDebug        # Windows: gradlew.bat :app:assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

서명 키는 저장소에 넣지 않았습니다. 그래서 소스에서 `assembleRelease`로 빌드하면 서명되지 않은 APK가 나옵니다. 소스 빌드는 debug로 실행하고, release 버전은 `apk/` 폴더의 APK로 확인해 주세요.

### 2.3 사용 순서

에뮬레이터 신호 없이 가장 빨리 보는 방법은 오른쪽 위 **시연 도구**에서 **샘플 · 급정지 징후**나 **샘플 · 에어백 전개**를 누르는 것입니다. 실제 차량 신호로 보려면 [3.3 Virtual Console로 사고 상황 재현](#33-virtual-console로-사고-상황-재현-선택)을 따라 합니다.

1. 사고가 감지되면 20초 동안 **괜찮아요 / 도움이 필요해요**를 묻습니다. 급정지일 때는 **사고 아니에요**도 고를 수 있습니다.
2. 응답이 없으면 긴급 대응으로 올리고, 차 안 탑승자를 '응답 없음'으로 표시합니다.
3. **대응 현황** 탭에서 직접 하실 일과 앱이 자동으로 처리한 일을 나눠 보여 줍니다.
4. **탑승자**, **신고 카드**, **사건 기록** 탭에서 탑승 추정, 기관별 신고 카드(모의), 판단과 실행 기록을 확인합니다.
5. 119·112 통화는 **전화 화면 열기** 버튼으로 전화 화면을 연 뒤 사람이 직접 겁니다.

## 3. 테스트 환경 및 필요한 설정

### 3.1 빌드 환경

| 항목 | 내용 |
| --- | --- |
| 언어 | Kotlin |
| 빌드 도구 | Android Gradle Plugin 9.4.1, Gradle 9.6.0 |
| SDK | compileSdk 37, minSdk 30, targetSdk 37 |
| 차량 SDK | Pleos Vehicle SDK 2.3.2 (`ai.pleos.playground:Vehicle:2.3.2`) |
| 실행 시 권한 | 속도 `android.car.permission.CAR_SPEED` (급정지 감지), 위치 `ACCESS_FINE_LOCATION` (신고 카드 위치) |

### 3.2 에뮬레이터 준비

1. Android Studio → SDK Manager → SDK Update Sites에 Pleos Connect 시스템 이미지 주소를 추가합니다.
   - Windows / Linux: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
   - macOS (Apple Silicon): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
2. SDK Platforms에서 **Show Package Details**를 켜고, API 34의 **Pleos Connect 2.1.0** 이미지를 설치합니다.
3. Device Manager → Create Device → Automotive → **Pleos Connect 2.1.0**으로 기기를 만들고 실행합니다.
4. 소스에서 빌드하려면 Android SDK Platform 37도 설치해야 합니다. Vehicle SDK는 빌드할 때 Pleos 공개 Maven 저장소에서 자동으로 받으므로 인터넷 연결이 필요합니다.

### 3.3 Virtual Console로 사고 상황 재현 (선택)

에뮬레이터의 Virtual Console(<http://127.0.0.1:8088/>)에서 차량 신호를 바꿔 실제 신호로 확인할 수 있습니다.

1. **Seat Occupancy**에서 운전석과 조수석을 탑승 상태로 바꿉니다.
2. 기어를 **D**로 두고 속도를 **90km/h → 0km/h**로 바꿉니다. 급정지 감지 → 질문 → (응답이 없으면) 긴급 대응·고속도로 추정·기관별 신고 카드 전송(모의)이 이어집니다.
3. 기어를 **P**로 바꾸면 문 잠금 해제, 트렁크, 창문이 자동으로 실행됩니다.
4. 운전석과 조수석 문을 연 뒤 좌석을 **Empty**로 바꾸면 하차가 감지되고 대피 할 일이 사라집니다.

에뮬레이터에서는 콘솔의 좌석 값이 앱에 반대로 들어와서, 앱이 에뮬레이터에서만 자동으로 보정합니다. 이 보정은 **시연 도구 → 좌석 신호 보정**에서 끌 수 있습니다.

위치는 아래 명령에 원하는 경도와 위도를 넣어 바꿀 수 있습니다. 위치 신호가 없을 때는 **시연 도구**에서 샘플 위치를 쓸 수 있습니다.

```bash
adb emu geo fix <경도> <위도>
```

### 3.4 지키는 원칙

- 기관 전송은 모의입니다. 실제로 기관에 보내거나 전화를 걸지 않습니다.
- 차량 제어(문·트렁크·창문)는 기어 P가 확인될 때만 실행하고, 조향과 가감속은 다루지 않습니다.
- 응답이 없다고 '괜찮음'으로 보지 않습니다. 신호가 없는 좌석은 '미확인'으로 둡니다.
- 자동 판단에는 출처('차량 신호', '앱 추정')를 붙이고, 사람이 고친 값은 자동화가 다시 덮어쓰지 않습니다.

### 3.5 확인한 범위

- Pleos Connect 2.1.0 에뮬레이터에서 디버그 빌드 v0.2.0으로 Virtual Console 신호를 바꿔 급정지 감지, 무응답 긴급 대응, 도로 추정과 기관별 모의 신고, 기어 P에서 문 잠금 해제·트렁크·창문 자동 실행, 하차 감지, 119 전화 화면 열기를 확인했습니다. (2026-11-12)
- release APK는 같은 소스로 빌드했고 서명과 패키지 정보를 `apksigner`로 확인했습니다.
- 음성 안내(TTS)는 에뮬레이터의 음성 엔진에 연결되지 않아 화면에 '음성 엔진 연결 안 됨'으로 표시됩니다.
- 실제 차량, 실제 에어백 전개 신호, 실제 기관 접수, 실제 차량의 음성 안내는 확인하지 않았습니다.

## 4. 개발에 사용한 Pleos Connect 에뮬레이터 버전과 화면 규격

| 항목 | 내용 |
| --- | --- |
| 에뮬레이터 | Pleos Connect 에뮬레이터 v2.1.0 (Android 14, API 34, x86_64) |
| 화면 규격 | 2560 × 1440 px, 160 dpi, 가로 화면 |

## 5. APK 파일 위치

**[apk/설계3.5팀_FirstGuard_release.apk](apk/설계3.5팀_FirstGuard_release.apk)**

- 디버그 모드를 끈 release 빌드입니다.
- APK Signature Scheme v2로 서명했고, `apksigner verify`로 서명을 확인했습니다.
- 외부 서버나 로그인 없이 실행됩니다.
