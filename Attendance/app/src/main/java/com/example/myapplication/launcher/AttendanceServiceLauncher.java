package com.example.myapplication.launcher;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import android.app.Activity;

import androidx.core.content.ContextCompat;

import com.example.myapplication.service.AttendanceEvents;
import com.example.myapplication.service.ProfessorAttendanceService;
import com.example.myapplication.service.StudentAttendanceService;

/**
 * 출석 Service 시작/종료 + 권한 흐름 + Service→Activity broadcast 수신을 캡슐화한 헬퍼.
 *
 * Activity가 책임지던 다음을 한 클래스로 묶음:
 *   - BLE / UWB_RANGING / POST_NOTIFICATIONS 권한 체크·요청
 *   - UWB feature 미지원 기기 거름
 *   - Service ACTION_START / STOP / SUBMIT_PIN intent 발사
 *   - AttendanceEvents broadcast 수신 → Listener로 위임
 *   - 권한 요청 도중 입력된 상태(pending) 보관 후 권한 승인 시 재개
 *
 * 사용 패턴 (Activity 측):
 *   onCreate: launcher = new AttendanceServiceLauncher(this);
 *   onResume: launcher.registerReceiver(); + setListener
 *   onPause:  launcher.unregisterReceiver();
 *   onRequestPermissionsResult: launcher.handlePermissionResult(...);
 *   onClick:  launcher.startStudent(id) / startProfessor(course, prof) / submitPin / stop...
 */
public class AttendanceServiceLauncher {

    private static final String TAG = "AttLauncher";

    // 권한 request code. Activity의 다른 권한 코드와 충돌 피하기 위해 10xx 대역.
    public static final int REQ_BLE_PERMS          = 1001;
    public static final int REQ_NOTIFICATION_PERMS = 1002;
    public static final int REQ_UWB_PERMS          = 1003;

    /** Service → Activity broadcast 수신용 콜백. */
    public interface SessionEventsListener {
        default void onSessionStarted(String sessionCode, String lectureSessionId) {}
        default void onSessionFailed(String reason) {}
        default void onSessionExpired() {}
        default void onAttendanceConfirmed(String sessionCode) {}
        default void onAttendanceFailed(String reason) {}
        default void onAttendanceAbsent(String attendanceId) {}
    }

    /** 권한 요청 후 재개할 동작 종류. */
    private enum PendingAction { START_PROFESSOR, START_STUDENT, SUBMIT_PIN }

    private final Activity activity;
    private SessionEventsListener listener;

    // 권한 요청 중에 결정된 상태를 보관 → 권한 결과 도착 후 재개에 사용
    private PendingAction pendingAction;
    private String pendingStudentId;
    private String pendingPin;
    private String pendingCourseId;
    private String pendingProfessorId;
    private long pendingClassStartAt;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            if (listener == null) return;
            String action = i.getAction();
            if (action == null) return;
            switch (action) {
                case AttendanceEvents.ACTION_SESSION_STARTED:
                    listener.onSessionStarted(
                            i.getStringExtra(AttendanceEvents.EXTRA_SESSION_CODE),
                            i.getStringExtra(AttendanceEvents.EXTRA_LECTURE_SESSION_ID));
                    break;
                case AttendanceEvents.ACTION_SESSION_FAILED:
                    listener.onSessionFailed(i.getStringExtra(AttendanceEvents.EXTRA_REASON));
                    break;
                case AttendanceEvents.ACTION_SESSION_EXPIRED:
                    listener.onSessionExpired();
                    break;
                case AttendanceEvents.ACTION_ATTENDANCE_CONFIRMED:
                    listener.onAttendanceConfirmed(
                            i.getStringExtra(AttendanceEvents.EXTRA_SESSION_CODE));
                    break;
                case AttendanceEvents.ACTION_ATTENDANCE_FAILED:
                    listener.onAttendanceFailed(i.getStringExtra(AttendanceEvents.EXTRA_REASON));
                    break;
                case AttendanceEvents.ACTION_ATTENDANCE_ABSENT:
                    listener.onAttendanceAbsent(
                            i.getStringExtra(AttendanceEvents.EXTRA_ATTENDANCE_ID));
                    break;
            }
        }
    };

    public AttendanceServiceLauncher(Activity activity) {
        this.activity = activity;
        requestNotificationPermissionIfNeeded();
    }

    public void setListener(SessionEventsListener listener) {
        this.listener = listener;
    }

    // ── Activity 생명주기 위임 ────────────────────────────────

    /** onResume에서 호출. broadcast 수신 시작. */
    public void registerReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(AttendanceEvents.ACTION_SESSION_STARTED);
        filter.addAction(AttendanceEvents.ACTION_SESSION_FAILED);
        filter.addAction(AttendanceEvents.ACTION_SESSION_EXPIRED);
        filter.addAction(AttendanceEvents.ACTION_ATTENDANCE_CONFIRMED);
        filter.addAction(AttendanceEvents.ACTION_ATTENDANCE_FAILED);
        filter.addAction(AttendanceEvents.ACTION_ATTENDANCE_ABSENT);
        ContextCompat.registerReceiver(activity, receiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    /** onPause에서 호출. 안전하게 unregister. */
    public void unregisterReceiver() {
        try {
            activity.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // 등록 안 된 상태에서 호출되면 무시
        }
    }

    /** Activity의 onRequestPermissionsResult에서 위임 호출. */
    public void handlePermissionResult(int requestCode, int[] grantResults) {
        if (requestCode == REQ_BLE_PERMS) {
            if (allGranted(grantResults)) {
                Log.d(TAG, "BLE 권한 승인 → 재개");
                resumePending();
            } else {
                Log.e(TAG, "BLE 권한 거부");
                clearPending();
                Toast.makeText(activity, "BLE 권한이 필요합니다", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_UWB_PERMS) {
            if (allGranted(grantResults)) {
                Log.d(TAG, "UWB 권한 승인 → 재개");
                resumePending();
            } else {
                Log.e(TAG, "UWB 권한 거부");
                clearPending();
                Toast.makeText(activity, "UWB 권한이 필요합니다", Toast.LENGTH_SHORT).show();
            }
        }
        // REQ_NOTIFICATION_PERMS: 결과 무시 (거부돼도 Service 동작은 가능)
    }

    // ── 공개 API: 학생/교수/PIN/STOP ──────────────────────────

    public void startProfessor(String courseId, String professorId) {
        startProfessor(courseId, professorId, 0L);
    }

    /**
     * classStartAt 포함 버전. 그쪽 RTDB Attendance_Session의 bluetoothEndAt/pinEndAt 계산에 사용.
     * @param classStartAt 수업 시작 epoch millis. 0이면 서버가 now로 fallback.
     */
    public void startProfessor(String courseId, String professorId, long classStartAt) {
        if (!hasUwbFeature()) return;
        if (!ensureBlePerms(PendingAction.START_PROFESSOR, courseId, professorId, null, null, classStartAt)) return;
        if (!ensureUwbPerm(PendingAction.START_PROFESSOR, courseId, professorId, null, null, classStartAt)) return;

        Intent i = new Intent(activity, ProfessorAttendanceService.class)
                .setAction(ProfessorAttendanceService.ACTION_START)
                .putExtra(ProfessorAttendanceService.EXTRA_COURSE_ID, courseId)
                .putExtra(ProfessorAttendanceService.EXTRA_PROFESSOR_ID, professorId)
                .putExtra(ProfessorAttendanceService.EXTRA_CLASS_START_AT, classStartAt);
        ContextCompat.startForegroundService(activity, i);
        Log.d(TAG, "ProfessorService START 전송: course=" + courseId + " prof=" + professorId
                + " classStartAt=" + classStartAt);
    }

    public void startStudent(String studentId) {
        if (!hasUwbFeature()) return;
        if (!ensureBlePerms(PendingAction.START_STUDENT, null, null, studentId, null, 0L)) return;
        if (!ensureUwbPerm(PendingAction.START_STUDENT, null, null, studentId, null, 0L)) return;

        Intent i = new Intent(activity, StudentAttendanceService.class)
                .setAction(StudentAttendanceService.ACTION_START)
                .putExtra(StudentAttendanceService.EXTRA_STUDENT_ID, studentId);
        ContextCompat.startForegroundService(activity, i);
        Log.d(TAG, "StudentService START 전송: student=" + studentId);
    }

    /**
     * 수동 PIN 입력 출석. BLE 스캔 없이 곧장 check-in.
     * UWB 권한만 필요 (BLE 권한은 스킵).
     */
    public void submitPin(String studentId, String pin) {
        if (!hasUwbFeature()) return;
        if (!ensureUwbPerm(PendingAction.SUBMIT_PIN, null, null, studentId, pin, 0L)) return;

        Intent i = new Intent(activity, StudentAttendanceService.class)
                .setAction(StudentAttendanceService.ACTION_SUBMIT_PIN)
                .putExtra(StudentAttendanceService.EXTRA_STUDENT_ID, studentId)
                .putExtra(StudentAttendanceService.EXTRA_PIN, pin);
        ContextCompat.startForegroundService(activity, i);
        Log.d(TAG, "StudentService SUBMIT_PIN 전송: student=" + studentId);
    }

    public void stopProfessor() {
        Intent i = new Intent(activity, ProfessorAttendanceService.class)
                .setAction(ProfessorAttendanceService.ACTION_STOP);
        activity.startService(i);
    }

    public void stopStudent() {
        Intent i = new Intent(activity, StudentAttendanceService.class)
                .setAction(StudentAttendanceService.ACTION_STOP);
        activity.startService(i);
    }

    // ── 권한·feature 체크 + 보류 처리 ─────────────────────────

    private boolean hasUwbFeature() {
        if (!activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_UWB)) {
            Toast.makeText(activity, "이 기기는 UWB를 지원하지 않습니다", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    /** BLE 권한 보장. 부족하면 요청 후 false 반환 (호출자는 즉시 리턴). */
    private boolean ensureBlePerms(PendingAction action, String courseId, String professorId,
                                   String studentId, String pin, long classStartAt) {
        if (hasBlePerms()) return true;
        savePending(action, courseId, professorId, studentId, pin, classStartAt);
        requestBlePerms();
        return false;
    }

    /** UWB 권한 보장. Samsung은 normal permission이지만 runtime처럼 처리해야 함. */
    private boolean ensureUwbPerm(PendingAction action, String courseId, String professorId,
                                  String studentId, String pin, long classStartAt) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean granted = activity.checkSelfPermission(Manifest.permission.UWB_RANGING)
                    == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                savePending(action, courseId, professorId, studentId, pin, classStartAt);
                activity.requestPermissions(
                        new String[]{Manifest.permission.UWB_RANGING}, REQ_UWB_PERMS);
                return false;
            }
        }
        return true;
    }

    private boolean hasBlePerms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return granted(Manifest.permission.BLUETOOTH_SCAN)
                    && granted(Manifest.permission.BLUETOOTH_CONNECT)
                    && granted(Manifest.permission.BLUETOOTH_ADVERTISE);
        } else {
            return granted(Manifest.permission.ACCESS_FINE_LOCATION)
                    && granted(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
    }

    private void requestBlePerms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
            }, REQ_BLE_PERMS);
        } else {
            activity.requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQ_BLE_PERMS);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            activity.requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATION_PERMS);
        }
    }

    private boolean granted(String permission) {
        return activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean allGranted(int[] results) {
        if (results.length == 0) return false;
        for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    // ── 보류 상태 관리 ───────────────────────────────────────

    private void savePending(PendingAction action, String courseId, String professorId,
                             String studentId, String pin, long classStartAt) {
        this.pendingAction        = action;
        this.pendingCourseId      = courseId;
        this.pendingProfessorId   = professorId;
        this.pendingStudentId     = studentId;
        this.pendingPin           = pin;
        this.pendingClassStartAt  = classStartAt;
    }

    private void clearPending() {
        pendingAction = null;
        pendingCourseId = pendingProfessorId = pendingStudentId = pendingPin = null;
        pendingClassStartAt = 0L;
    }

    private void resumePending() {
        if (pendingAction == null) return;
        PendingAction action = pendingAction;
        String courseId = pendingCourseId, professorId = pendingProfessorId,
               studentId = pendingStudentId, pin = pendingPin;
        long classStartAt = pendingClassStartAt;
        clearPending();

        switch (action) {
            case START_PROFESSOR: startProfessor(courseId, professorId, classStartAt); break;
            case START_STUDENT:   startStudent(studentId); break;
            case SUBMIT_PIN:      submitPin(studentId, pin); break;
        }
    }
}
