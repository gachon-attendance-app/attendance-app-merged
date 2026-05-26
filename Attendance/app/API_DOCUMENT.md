# API_DOCUMENT

## 1. 로그인

### POST `/auth/login`

### 사용 화면

```text
login.xml
```

### 사용 ID

```text
etId: 사용자 아이디
etPw: 비밀번호
btnLogin: 로그인 버튼
cbAutoLogin: 자동 로그인 체크박스
```

### Request

```json
{
  "loginId": "202312345",
  "password": "1234"
}
```

### Response

```json
{
  "success": true,
  "accessToken": "access-token",
  "userId": 1,
  "loginId": "202312345",
  "name": "최은수",
  "role": "STUDENT",
  "message": "로그인 성공"
}
```

### role 값

```text
STUDENT: 학생
PROFESSOR: 교수
```

---

## 2. 강의 코드 조회

### GET `/courses/{courseCode}`

### 사용 화면

```text
register_schedule.xml
```

### 사용 ID

```text
etCourseCode: 강의 코드 입력
btnAddClass: 강의 추가 버튼
classBlockLayer: 시간표 블록 표시 영역
```

### Response

```json
{
  "success": true,
  "course": {
    "classId": 10,
    "courseCode": "MOB001",
    "courseName": "모바일프로그래밍",
    "professorName": "김가천",
    "dayOfWeek": "MON",
    "startTime": "09:00",
    "endTime": "10:30",
    "room": "AI관 301호",
    "semester": "2026-1"
  },
  "message": "강의 조회 성공"
}
```

### dayOfWeek 값

```text
MON: 월요일
TUE: 화요일
WED: 수요일
THU: 목요일
FRI: 금요일
```

---

## 3. 학생 시간표 저장

### POST `/students/{studentId}/schedule`

### 사용 화면

```text
register_schedule.xml
```

### 사용 ID

```text
btnConfirmSchedule: 시간표 확정 버튼
classBlockLayer: 시간표 블록 표시 영역
```

### Request

```json
{
  "classIds": [10, 11, 12]
}
```

### Response

```json
{
  "success": true,
  "studentId": 1,
  "classes": [
    {
      "classId": 10,
      "courseCode": "MOB001",
      "courseName": "모바일프로그래밍",
      "professorName": "김가천",
      "dayOfWeek": "MON",
      "startTime": "09:00",
      "endTime": "10:30",
      "room": "AI관 301호",
      "semester": "2026-1"
    }
  ],
  "message": "시간표 저장 성공"
}
```

---

## 4. 학생 시간표 조회

### GET `/students/{studentId}/schedule`

### 사용 화면

```text
schedule_1.xml
mypage.xml
```

### 사용 ID

```text
classBlockLayer: 시간표 블록 표시 영역
tvCurrentClassName: 현재 수업명
tvDetailProfessor: 교수명
tvDetailTime: 수업 시간
tvDetailRoom: 강의실
tvDetailCourseCode: 강의 코드
```

### Response

```json
{
  "success": true,
  "studentId": 1,
  "classes": [
    {
      "classId": 10,
      "courseCode": "MOB001",
      "courseName": "모바일프로그래밍",
      "professorName": "김가천",
      "dayOfWeek": "MON",
      "startTime": "09:00",
      "endTime": "10:30",
      "room": "AI관 301호",
      "semester": "2026-1"
    },
    {
      "classId": 11,
      "courseCode": "DATA001",
      "courseName": "자료구조",
      "professorName": "이가천",
      "dayOfWeek": "TUE",
      "startTime": "10:30",
      "endTime": "12:00",
      "room": "AI관 402호",
      "semester": "2026-1"
    }
  ],
  "message": "시간표 조회 성공"
}
```

---

## 5. 현재 수업 조회

### GET `/students/{studentId}/current-class`

### 사용 화면

```text
main1.xml
```

### 사용 ID

```text
tvCurrentClassName: 현재 수업명
tvDate: 오늘 날짜
tvPeriod: 수업 시간
tvAttendanceStatus: 출석 상태
btnAttendance: 출석 버튼
```

### Response

```json
{
  "success": true,
  "hasClass": true,
  "classId": 10,
  "courseCode": "MOB001",
  "courseName": "모바일프로그래밍",
  "professorName": "김가천",
  "room": "AI관 301호",
  "startTime": "09:00",
  "endTime": "10:30",
  "attendanceStatus": "NOT_STARTED",
  "attendanceMessage": "출석 전",
  "sessionId": 100,
  "message": "현재 수업 조회 성공"
}
```

### attendanceStatus 값

```text
NOT_STARTED: 출석 전
PRESENT: 출석
LATE: 지각
ABSENT: 결석
```

---

## 6. 내 정보 조회

### GET `/users/{userId}/me`

### 사용 화면

```text
mypage.xml
```

### 사용 ID

```text
tvUserName: 사용자 이름
tvUserRole: 학생/교수 구분
tvDepartment: 학과
tvStudentNumber: 학번/교번
```

### Response

```json
{
  "success": true,
  "userId": 1,
  "loginId": "202312345",
  "name": "최은수",
  "role": "STUDENT",
  "department": "소프트웨어학과",
  "studentNumber": "202312345",
  "professorNumber": null,
  "message": "내 정보 조회 성공"
}
```

---

## 7. 교수 출석 시작

### POST `/professors/classes/{classId}/attendance/start`

### 사용 화면

```text
main_p_1.xml
```

### 사용 ID

```text
btnProfessorAttendanceCheck: 교수 출석 시작 버튼
tvPinDigit1: PIN 첫 번째 숫자
tvPinDigit2: PIN 두 번째 숫자
tvPinDigit3: PIN 세 번째 숫자
tvPinDigit4: PIN 네 번째 숫자
```

### Response

```json
{
  "success": true,
  "sessionId": 100,
  "classId": 10,
  "pinCode": "4821",
  "bluetoothEnabled": true,
  "startedAt": "2026-05-19T09:00:00",
  "pinExpiresAt": "2026-05-19T09:15:00",
  "message": "출석 시작"
}
```

---

## 8. 블루투스 출석 체크

### POST `/attendance/bluetooth-check`

### 사용 화면

```text
main1.xml
```

### 사용 ID

```text
btnAttendance: 학생 출석 버튼
tvAttendanceStatus: 출석 결과 표시
```

### Request

```json
{
  "sessionId": 100,
  "studentId": 1,
  "classId": 10,
  "detectedDeviceId": "TEMP_BLUETOOTH_DEVICE",
  "rssi": -55,
  "checkedAt": "2026-05-19T09:03:00"
}
```

### Response

```json
{
  "success": true,
  "status": "PRESENT",
  "message": "출석 완료"
}
```

---

## 9. PIN 출석 체크

### POST `/attendance/pin-check`

### 사용 화면

```text
pin.xml
```

### Request

```json
{
  "sessionId": 100,
  "studentId": 1,
  "pinCode": "4821"
}
```

### Response

```json
{
  "success": true,
  "status": "LATE",
  "message": "지각 처리"
}
```

---

## 10. UWB 중간 출석 체크

### POST `/attendance/uwb-check`

### Request

```json
{
  "sessionId": 100,
  "studentId": 1,
  "classId": 10,
  "detected": true,
  "distance": 2.4,
  "checkedAt": "2026-05-19T09:25:00"
}
```

### Response

```json
{
  "success": true,
  "currentStatus": "PRESENT",
  "missedUwbCount": 1,
  "message": "UWB 체크 완료"
}
```

### detected 값

```text
true: UWB 감지 성공
false: UWB 감지 실패
```

---

## 11. 교수 출석 현황 조회

### GET `/professors/classes/{classId}/attendance/status`

### 사용 화면

```text
main_p_1.xml
```

### 사용 ID

```text
tvClassName: 수업명
tvClassTime: 수업 시간
tvAttendanceRate: 출석률
tvLateRate: 지각률
tvAbsentRate: 결석률
tvUwbCheckCount: UWB 체크 횟수
layoutStudentAttendanceRows: 학생별 출석 표
```

### Response

```json
{
  "success": true,
  "classId": 10,
  "courseName": "모바일프로그래밍",
  "classTime": "월 09:00 - 10:30",
  "attendanceRate": 80,
  "lateRate": 10,
  "absentRate": 10,
  "uwbCheckCount": 2,
  "students": [
    {
      "studentId": "202312345",
      "name": "최은수",
      "status": "PRESENT"
    },
    {
      "studentId": "202312346",
      "name": "홍길동",
      "status": "LATE"
    },
    {
      "studentId": "202312347",
      "name": "김가천",
      "status": "ABSENT"
    }
  ],
  "message": "출석 현황 조회 성공"
}
```

---

## 12. 학생 출결 통계 조회

### GET `/students/{studentId}/attendance/summary`

### 사용 화면

```text
all_attendance.xml
all_attendance_rate.xml
```

### Response

```json
{
  "success": true,
  "studentId": 1,
  "courses": [
    {
      "classId": 10,
      "courseName": "모바일프로그래밍",
      "presentCount": 8,
      "lateCount": 1,
      "absentCount": 1,
      "presentRate": 80,
      "lateRate": 10,
      "absentRate": 10
    }
  ],
  "message": "출결 통계 조회 성공"
}
```

---

## 13. 출결 캘린더 조회

### GET `/students/{studentId}/attendance/calendar?month=2026-05`

### 사용 화면

```text
week_1.xml
week_2.xml
```

### Response

```json
{
  "success": true,
  "month": "2026-05",
  "days": [
    {
      "date": "2026-05-03",
      "classId": 10,
      "courseName": "모바일프로그래밍",
      "status": "ABSENT"
    },
    {
      "date": "2026-05-10",
      "classId": 11,
      "courseName": "자료구조",
      "status": "LATE"
    }
  ],
  "message": "출결 캘린더 조회 성공"
}
```

---

## 공통 상태값

```text
PRESENT: 출석
LATE: 지각
ABSENT: 결석
NOT_STARTED: 출석 전
FAILED: 실패
```

---

## 프론트 기준 처리 방식

```text
로그인 성공
→ accessToken, userId, loginId, name, role 저장
→ role에 따라 학생/교수 메인 이동

시간표 등록
→ 강의 코드 조회
→ classId 저장
→ 시간표 확정 시 classIds 전송

출석 체크
→ 블루투스/PIN/UWB 결과 전송
→ 백엔드 응답 status를 화면에 표시

교수 화면
→ 출석 시작 API 호출
→ pinCode 표시
→ 출석 현황 조회 API로 학생 표 갱신
```