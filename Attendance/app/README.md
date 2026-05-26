# Gachon Attendance App Frontend

## 프로젝트 개요

가천대학교 출석 체크 앱 프론트엔드 프로젝트.

기존 전자출결 문제 보완 목적.

```text
1. 블루투스 범위 문제 보완
2. PIN 기반 보조 출석
3. UWB 기반 중간 출석 체크
4. 학생/교수 화면 분리
5. 시간표 기반 출석 관리
6. 출결 통계 시각화
```

---

## 개발 환경

```text
Android Studio
Kotlin
XML Layout
Retrofit
Gson
OkHttp Logging Interceptor
```

---

## 프론트 진행 상태

```text
XML 화면 제작 진행
세부 디자인 수정 진행
Kotlin 화면 이동 구현 진행
Retrofit API 연결 구조 추가
백엔드 연동용 model 파일 추가
API_DOCUMENT.md 작성
```

---

## 프로젝트 구조

```text
app/src/main/java/com/example/myapplication/
- ApiClient.kt
- ApiService.kt
- LoadingActivity.kt
- LoginActivity.kt
- MainActivity.kt
- RegisterScheduleActivity.kt

app/src/main/java/com/example/myapplication/model/
- LoginRequest.kt
- LoginResponse.kt
- ScheduleResponse.kt
- AttendanceResponse.kt
- UserResponse.kt

app/src/main/res/layout/
- login.xml
- main1.xml
- main2.xml
- main_p_1.xml
- register_schedule.xml
- schedule_1.xml
- week_1.xml
- week_2.xml
- all_attendance.xml
- all_attendance_rate.xml
- mypage.xml
- pin.xml
- notice_1.xml
- notice_2.xml
- cancel_1.xml
- cancel_2.xml
- confirm_1.xml
- confirm_2.xml
```

---

## 주요 화면

### 로그인

```text
login.xml
LoginActivity.kt
```

```text
기능
- 로그인
- 자동 로그인
- 학생/교수 role 분기
- 회원가입 이동
```

```text
주요 ID
- etId
- etPw
- cbAutoLogin
- btnLogin
```

---

### 학생 메인

```text
main1.xml
MainActivity.kt
```

```text
기능
- 현재 수업 조회
- 출석 상태 표시
- 학생 출석 버튼
- 블루투스 출석 요청
```

```text
주요 ID
- tvCurrentClassName
- tvDate
- tvPeriod
- tvAttendanceStatus
- btnAttendance
```

---

### 교수 메인

```text
main_p_1.xml
MainActivity.kt
```

```text
기능
- 교수 출석 시작
- PIN 표시
- 학생별 출석 현황 표시
- 출석률/지각률/결석률 표시
```

```text
주요 ID
- tvClassName
- tvClassTime
- btnProfessorAttendanceCheck
- tvPinDigit1
- tvPinDigit2
- tvPinDigit3
- tvPinDigit4
- tvAttendanceRate
- tvLateRate
- tvAbsentRate
- tvUwbCheckCount
- layoutStudentAttendanceRows
```

---

### 시간표 등록

```text
register_schedule.xml
RegisterScheduleActivity.kt
```

```text
기능
- 강의 코드 입력
- 강의 조회
- 시간표 블록 표시
- 시간표 저장
```

```text
주요 ID
- etCourseCode
- btnAddClass
- btnConfirmSchedule
- classBlockLayer
```

---

### 시간표 조회

```text
schedule_1.xml
MainActivity.kt
```

```text
기능
- 저장된 시간표 조회
- 현재 수업 상세 정보 표시
```

```text
주요 ID
- classBlockLayer
- tvCurrentClassName
- tvDetailProfessor
- tvDetailTime
- tvDetailRoom
- tvDetailCourseCode
```

---

### 주간 출결

```text
week_1.xml
week_2.xml
MainActivity.kt
```

```text
기능
- 날짜별 출석/지각/결석 표시
```

---

### 마이페이지

```text
mypage.xml
MainActivity.kt
```

```text
기능
- 사용자 정보 조회
- 이름, 학과, 학번/교번 표시
- 내 시간표 표시
```

```text
주요 ID
- tvUserName
- tvUserRole
- tvDepartment
- tvStudentNumber
```

---

### 전체 출결

```text
all_attendance.xml
all_attendance_rate.xml
MainActivity.kt
```

```text
기능
- 과목별 출석률
- 과목별 지각률
- 과목별 결석률
```

---

## API 연결 파일

### ApiClient.kt

```text
위치
app/src/main/java/com/example/myapplication/ApiClient.kt
```

```text
역할
- Retrofit 설정
- BASE_URL 관리
- ApiService 객체 생성
```

### ApiService.kt

```text
위치
app/src/main/java/com/example/myapplication/ApiService.kt
```

```text
역할
- 백엔드 API 목록 관리
- 로그인 API
- 시간표 API
- 출석 API
- 사용자 정보 API
```

---

## model 파일

```text
위치
app/src/main/java/com/example/myapplication/model/
```

```text
파일
- LoginRequest.kt
- LoginResponse.kt
- ScheduleResponse.kt
- AttendanceResponse.kt
- UserResponse.kt
```

```text
역할
- 서버 요청 데이터
- 서버 응답 데이터
- 화면별 API 데이터 구조
```

---

## 백엔드 연동 API

자세한 요청/응답 형식은 `API_DOCUMENT.md` 참고.

```text
POST /auth/login
GET /courses/{courseCode}
POST /students/{studentId}/schedule
GET /students/{studentId}/schedule
GET /students/{studentId}/current-class
GET /users/{userId}/me
POST /professors/classes/{classId}/attendance/start
POST /attendance/bluetooth-check
POST /attendance/pin-check
POST /attendance/uwb-check
GET /professors/classes/{classId}/attendance/status
GET /students/{studentId}/attendance/summary
GET /students/{studentId}/attendance/calendar
```

---

## AndroidManifest 설정

```text
위치
app/src/main/AndroidManifest.xml
```

API 통신 권한.

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

HTTP 로컬 서버 테스트용.

```xml
android:usesCleartextTraffic="true"
```

---

## Retrofit 의존성

```text
위치
app/build.gradle.kts
```

```kotlin
implementation("com.squareup.retrofit2:retrofit:2.11.0")
implementation("com.squareup.retrofit2:converter-gson:2.11.0")
implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
```

---

## 로컬 백엔드 주소

```text
위치
ApiClient.kt
```

```kotlin
private const val BASE_URL = "http://10.0.2.2:8080/"
```

```text
10.0.2.2
- Android Emulator에서 PC 로컬 서버 접근용 주소

실제 서버 배포 후 BASE_URL 변경 필요
```

---

## 테스트 계정

백엔드 서버 연결 전 임시 테스트용.


``

## Git 작업

```bash
git add .
git commit -m "Add API integration files"
git push origin main
```

---

## 역할 분리

### 프론트

```text
XML 화면
Kotlin 화면 이동
버튼 이벤트
Retrofit API 호출
백엔드 응답값 화면 표시
블루투스/UWB 감지 결과 전송
```

### 백엔드

```text
로그인 검증
사용자 role 반환
시간표 저장/조회
출석 세션 생성
PIN 발급/검증
블루투스/UWB 결과 처리
출석/지각/결석 최종 판단
출결 통계 계산
```

---

## 개발 우선순위

```text
1. 로그인/로그아웃
2. 학생/교수 role 분기
3. 시간표 조회/저장
4. 현재 수업 조회
5. 교수 출석 시작
6. 학생 출석 체크
7. PIN 출석
8. 블루투스 감지 결과 연동
9. UWB 중간 출석 체크
10. 출결 통계/캘린더 연동
```

---

## 상태값

```text
PRESENT: 출석
LATE: 지각
ABSENT: 결석
NOT_STARTED: 출석 전
FAILED: 실패
```

---

## 참고

```text
백엔드 서버 완성 전에는 임시 데이터로 화면 이동 테스트 가능.
서버 주소 확정 후 ApiClient.kt의 BASE_URL만 변경.
API 요청/응답 형식은 API_DOCUMENT.md 기준.
```