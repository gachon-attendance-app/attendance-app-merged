# 출석 앱 통합 프로젝트 (attendance-app-merged)

`gachon-attendance-app/attendance-android` (Compose UI + 로그인/스케줄) 위에 기존 `attendance-app` (BLE/UWB/socket/Service 풀구현)을 얹는 통합 저장소.

## 📁 디렉토리 구조

```
attendance-app-merged/
├── Attendance/                  ← Android 앱 (base = gachon repo)
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml          ← 권한·service·feature 머지 (Phase 3)
│   │   ├── java/com/example/myapplication/
│   │   │   ├── (gachon base: LoadingActivity, LoginActivity, MainActivity, ...)
│   │   │   ├── attendance/              ← 내 Controller (Phase 4 이식)
│   │   │   ├── service/                 ← 내 Foreground Service (Phase 4 이식)
│   │   │   ├── ble/                     ← 내 BLE Manager (Phase 4 이식)
│   │   │   ├── uwb/                     ← 내 UWB Manager (Phase 4 이식)
│   │   │   ├── network/                 ← Retrofit + socket (Phase 4 이식)
│   │   │   └── launcher/                ← AttendanceServiceLauncher (Phase 5 신규)
│   │   └── res/layout/                  ← 내 XML 머지 (Phase 4)
│   ├── build.gradle.kts
│   └── ...
└── attendance-server/           ← Node.js + socket.io (내 서버 그대로)
```

## 🎯 통합 결정사항

| 항목 | 결정 |
|---|---|
| Base 저장소 | gachon-attendance-app/attendance-android |
| 패키지명 | `com.example.myapplication` 유지 (내 코드만 리네이밍) |
| 언어 | Java 유지 + Kotlin/Compose 공존 |
| UI | XML 레이아웃 + Compose 화면 공존 |
| 백엔드 | **하이브리드** — RTDB(로그인/수강/스케줄) + 내 attendance-server(출석 처리) |
| 통합 패턴 | `AttendanceServiceLauncher` 헬퍼 (Activity 책임 캡슐화) |

## 🔗 통합 패턴: AttendanceServiceLauncher

내 MainActivity/MainActivity2가 책임지던 권한·UWB체크·Service trigger·BroadcastReceiver를 한 헬퍼 클래스로 캡슐화.

그쪽 `MainActivity.kt`는 onClick 두 줄 + 위임 4개(`onResume`/`onPause`/`onRequestPermissionsResult`)만 추가하면 됨.

| 그쪽 버튼 | 현재 (가짜) | 통합 후 |
|---|---|---|
| `btnAttendance` (학생) | Firebase에 PRESENT 직접 PUT | `launcher.startStudent(userId)` → BLE/PIN → `/check-in` |
| `btnProfessorAttendanceCheck` (교수) | Firebase에서 PIN 읽어 표시 | `launcher.startProfessor(courseId, profId)` → `/start` → 받은 PIN을 기존 `tvPinDigit1~4` UI에 표시 + BLE 광고 |

## 📊 진행 상태

- [x] **Phase 0~2**: 디렉토리 셋업 + gachon base clone + 내 server 복사
- [ ] **Phase 3**: 의존성·AndroidManifest 머지
- [ ] **Phase 4**: 내 Java/Kotlin 소스 이식 + 패키지명 일괄 치환
- [ ] **Phase 5**: `AttendanceServiceLauncher` 작성 + 통합 지점 연결 + ApiService 재작성
- [ ] **Phase 6**: 빌드 + 에뮬레이터 smoke test

## 📌 참고

원본 프로젝트 (BLE/UWB/Firestore 스키마/API 명세 상세 문서):
`C:\Users\miran\attendance-app\AGENTS.md` — Phase 6 통합 완료 후 핵심 내용 이쪽으로 흡수 예정.
