# attendance-android (통합본)

[gachon-attendance-app/attendance-android](https://github.com/gachon-attendance-app/attendance-android) (Compose UI + 로그인/시간표/출석 조회) 위에 BLE + UWB + Foreground Service + Node.js 백엔드를 통합한 출석 앱.

**BLE + UWB 2단계 검증**으로 부정 출석 방지하는 것이 핵심.

---

## 🚀 Quick Start (Android만, 5분)

팀원이 막 합류한 경우 — 화면 흐름까지 80% 검증 가능. 출석 풀 흐름은 [아래 추가 setup](#-출석-풀-흐름-setup-admin-키-필요) 참고.

```bash
git clone https://github.com/lsh5605/attendance-android.git
```

1. Android Studio에서 `Attendance/` 폴더 열기
2. Gradle sync 자동 진행 (1~2분)
3. USB로 폰 연결 → **Run** ▶️

### 작동 확인 시나리오

- Loading 화면 (1.5초) → Login 화면
- **`test` / `1234`** 로 로그인 → 학생 메인 화면
- **`professor` / `1234`** 로 로그인 → 교수 메인 화면
- drawer 메뉴 / 시간표 / 출석 조회 / 캘린더 등 화면 진입 확인

이 단계에서 가능한 것:
- ✅ 모든 화면 진입 / 로그인 / 시간표 / 통계
- ✅ 시간표 12시간 주기 동기화
- ✅ 수업 시작 5분 전 푸시 알림
- ❌ 실제 출석 처리 (서버 + admin 키 필요)

---

## 📁 디렉토리 구조

```
attendance-android/
├── Attendance/                  ← Android 앱
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── google-services.json (Firebase client config — git에 포함)
│   │   ├── java/com/example/myapplication/
│   │   │   ├── (그쪽 base: LoadingActivity, LoginActivity, MainActivity ...)
│   │   │   ├── attendance/      ← Controller (학생/교수 출석 로직)
│   │   │   ├── ble/             ← BLE 광고/스캔 Manager
│   │   │   ├── uwb/             ← UWB ranging (Kotlin)
│   │   │   ├── service/         ← Foreground Service
│   │   │   ├── network/         ← Retrofit + socket.io 클라이언트
│   │   │   ├── schedule/        ← 시간표 동기화 + 5분 전 알람
│   │   │   └── launcher/        ← AttendanceServiceLauncher (통합 어댑터)
│   │   └── res/
│   └── build.gradle.kts
└── attendance-server/           ← Node.js + socket.io 서버
    ├── server.js
    ├── controllers/, services/, routes/
    ├── firebase/firebaseAdmin.js  ← admin SDK 키 require (git에 없음)
    └── socket/
```

---

## 🔧 출석 풀 흐름 setup (admin 키 필요)

서버까지 띄우고 실제 BLE 스캔 + UWB ranging 검증하려면:

### 1. Firebase Admin SDK 키 입수

**옵션 A — 본인이 직접 발급 (권장)**:
```
https://console.firebase.google.com → attendanceapp-cbf00 프로젝트
  → 프로젝트 설정 (⚙️) → 서비스 계정 탭
  → "새 비공개 키 생성" → JSON 다운로드
```

**옵션 B — 팀원에게 받기**: Slack DM / 1Password로 안전하게 전달 (절대 채팅 평문 X)

### 2. admin 키 파일 배치

```
attendance-server/firebase/<받은 키 파일 이름>.json
```

### 3. `firebaseAdmin.js`의 require 경로 수정

```js
// attendance-server/firebase/firebaseAdmin.js 라인 2
const serviceAccount = require("./<본인이 받은 키 파일 이름>.json");
```

> 💡 **팁**: 각자 다른 키 파일 이름이라 매번 수정하기 귀찮으면 동적 로드 패턴으로 변경 가능. issue로 트래킹 중.

### 4. `NetworkConfig.HOST` 본인 PC IP로 변경

```java
// Attendance/app/src/main/java/com/example/myapplication/network/NetworkConfig.java
private static final String HOST = "http://<본인 PC IP>:3000";
```

본인 PC IP 확인:
- Windows: `ipconfig` → 무선 LAN 어댑터의 IPv4 주소
- 폰과 PC가 **같은 Wi-Fi**에 연결돼있어야 함

### 5. 서버 실행

```bash
cd attendance-server
npm install        # 첫 실행만
node server.js
# → "Server running on port 3000 (HTTP + WebSocket)"
```

### 6. Android 앱 재빌드 → 출석 흐름 검증

- 교수로 로그인 → "출석 시작" → 서버 콘솔에 `[socket] connect ... role=PROFESSOR` 로그 + 폰에 PIN 4자리 표시
- 학생으로 로그인 → "출석" → BLE 스캔 (다른 폰의 광고 또는 PIN 입력 필요)

---

## 🎯 통합 결정사항

| 항목 | 결정 |
|---|---|
| **Base** | gachon-attendance-app/attendance-android (그쪽 Compose UI + 로그인) 위에 BLE/UWB/Service 통합 |
| **namespace** | `com.example.myapplication` (R 클래스 경로) |
| **applicationId** | `com.example.gachonattendance` (Firebase 프로젝트 매칭) |
| **Firebase 프로젝트** | `attendanceapp-cbf00` (RTDB + Firestore) |
| **백엔드** | **하이브리드** — RTDB는 화면용, Node.js attendance-server는 출석 처리 |
| **통합 패턴** | `AttendanceServiceLauncher` (Activity 책임 캡슐화) |

### 데이터 흐름

```
[로그인/시간표/조회 화면]  → RTDB attendanceapp-cbf00 (그쪽 11곳 사용)
[출석 처리]                → Node.js 서버 → Firestore + RTDB Enrollment 검증
[시간표 5분 전 알람]       → Room (로컬) + AlarmManager
```

---

## 🛠 트러블슈팅

### 빌드 이슈

| 에러 | 원인 / 해결 |
|---|---|
| `Cannot add extension with name 'kotlin'` | AGP 9 + `kotlin-android` plugin 충돌. plugin 제거 (이미 적용) |
| `Unresolved reference 'kotlinOptions'` | kotlin-android 없으면 사용 불가. block 제거 (이미 적용) |
| `Using kotlin.sourceSets DSL is not allowed` | gradle.properties에 `android.disallowKotlinSourceSets=false` (이미 적용) |
| `unexpected jvm signature V` (KSP) | Room 2.6.1 + KSP 호환 버그. Room 2.7.0 사용 (이미 적용) |
| `No matching client found for package name` | `google-services.json`의 package_name과 applicationId 매칭 안 됨. `applicationId = "com.example.gachonattendance"` 확인 |

### 런타임 이슈

| 증상 | 원인 / 해결 |
|---|---|
| 학생 출석 버튼 누르고 서버 콘솔 무반응 | BLE 광고 없어서 PIN 못 잡음. 교수 폰 출석 시작 필요 |
| 서버 시작 시 `Cannot find module ./<admin-key>.json` | admin 키 파일 위치 + firebaseAdmin.js의 require 경로 확인 |
| 출석 시도 시 `403 수강 중인 수업이 아닙니다` | RTDB `Enrollment/{학번}/{classId}` 매핑 확인 (FirebaseSeedData가 자동 시드) |
| 폰이 서버 못 찾음 | NetworkConfig.HOST가 PC IP와 일치하는지, 같은 Wi-Fi인지 |
| UWB ranging 안 됨 | UWB 지원 폰 필요 (Galaxy S23 Ultra, Pixel 6 Pro+ 등). 에뮬레이터는 UWB 미지원 |

### 권한 이슈 (Samsung)

UWB_RANGING은 공식적으론 normal permission인데 Samsung은 runtime처럼 처리. 앱이 권한 다이얼로그 자동으로 띄움 — 승인하면 됨. 거부 시 출석 불가.

---

## 📚 추가 참고

상세 아키텍처 / 흐름 / 시드 데이터는 `CLAUDE.md` 참고.

## 🔐 시크릿 관리

| 파일 | git에 포함? | 어떻게 입수 |
|---|---|---|
| `google-services.json` | ✅ 포함 | clone만 하면 자동 (Firebase client config는 commit OK) |
| `firebase-adminsdk-*.json` | ❌ 절대 X | Firebase Console에서 본인 발급 또는 안전한 채널로 받기 |
| `local.properties` | ❌ | Android Studio가 자동 생성 |

⚠️ **admin SDK 키는 절대 GitHub에 commit하지 마세요.** `.gitignore`로 차단돼있지만, 의도적으로 제거하지 않도록 주의.
