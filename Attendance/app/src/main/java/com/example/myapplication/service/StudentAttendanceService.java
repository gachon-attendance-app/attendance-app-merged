package com.example.myapplication.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.example.myapplication.R;
import com.example.myapplication.attendance.AttendanceController;
import com.example.myapplication.network.CheckInData;
import com.example.myapplication.schedule.notification.AttendanceWarningNotifier;

/**
 * 학생 BLE 스캔 + 출석 등록을 호스팅하는 Foreground Service.
 *
 * 역할:
 *   - AttendanceController 소유 + 생명주기 관리
 *   - Activity가 보낸 Intent(ACTION_START / ACTION_STOP)를 명령으로 받음
 *   - Controller의 Listener 콜백을 broadcast로 변환해 Activity에 전달
 *
 * 종료 시점:
 *   - 출석 확정 (onAttendanceConfirmed) → stopSelf 하지 않음. PREPARE/DONE 대기 위해 유지.
 *   - 스캔 타임아웃/실패 (onAttendanceFailed) → stopSelf
 *   - Activity가 ACTION_STOP 보냄 → controller.stop() + stopSelf
 */
public class StudentAttendanceService extends Service {

    private static final String TAG = "StuService";
    private static final String CHANNEL_ID = "attendance_student";
    private static final int NOTIFICATION_ID = 1002;

    public static final String ACTION_START      = "com.example.myapplication.STU_START";
    public static final String ACTION_STOP       = "com.example.myapplication.STU_STOP";
    public static final String ACTION_SUBMIT_PIN = "com.example.myapplication.STU_SUBMIT_PIN";
    public static final String EXTRA_STUDENT_ID = "studentId";
    public static final String EXTRA_PIN        = "pin";

    private AttendanceController controller;
    private String studentId;

    /** BT on/off 감지 → Controller에 reinit/log 위임. */
    private final BroadcastReceiver btStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_ON) {
                Log.d(TAG, "BT_STATE_ON 수신");
                if (controller != null) controller.onBluetoothStateChanged(true);
            } else if (state == BluetoothAdapter.STATE_OFF) {
                Log.d(TAG, "BT_STATE_OFF 수신");
                if (controller != null) controller.onBluetoothStateChanged(false);
            }
            // STATE_TURNING_ON/OFF는 무시
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        createChannel();
        registerBtStateReceiver();
        // controller는 studentId를 onStartCommand에서 받은 뒤 생성
        // (생성자에 studentId가 필요하므로)
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand: action=" + (intent != null ? intent.getAction() : "null"));

        // Foreground 진입은 매 onStartCommand마다 보장
        startForegroundCompat();

        if (intent == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            ensureController(intent.getStringExtra(EXTRA_STUDENT_ID));
            controller.start();
        } else if (ACTION_SUBMIT_PIN.equals(action)) {
            ensureController(intent.getStringExtra(EXTRA_STUDENT_ID));
            controller.checkInWithCode(intent.getStringExtra(EXTRA_PIN));
        } else if (ACTION_STOP.equals(action)) {
            if (controller != null) controller.stop();
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    /**
     * controller가 없으면 생성. studentId는 최초 1회만 확정.
     * (생성자에 studentId가 필요해 onCreate가 아닌 첫 명령 수신 시 생성)
     */
    private void ensureController(String incomingStudentId) {
        if (controller == null) {
            studentId = incomingStudentId;
            controller = new AttendanceController(getApplicationContext(), studentId);
            controller.setListener(buildListener());
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        try {
            unregisterReceiver(btStateReceiver);
        } catch (IllegalArgumentException ignored) {
            // 등록 안 된 상태에서 onDestroy 호출 시
        }
        if (controller != null) controller.stop();
        super.onDestroy();
    }

    private void registerBtStateReceiver() {
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(btStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(btStateReceiver, filter);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private AttendanceController.OnAttendanceListener buildListener() {
        return new AttendanceController.OnAttendanceListener() {
            @Override
            public void onAttendanceConfirmed(String sessionCode, CheckInData data) {
                Intent i = new Intent(AttendanceEvents.ACTION_ATTENDANCE_CONFIRMED)
                        .setPackage(getPackageName())
                        .putExtra(AttendanceEvents.EXTRA_SESSION_CODE, sessionCode)
                        .putExtra(AttendanceEvents.EXTRA_ATTENDANCE_ID, data.getAttendanceId())
                        .putExtra(AttendanceEvents.EXTRA_STUDENT_ID, data.getStudentId());
                sendBroadcast(i);
                // 출석 확정 후에도 Service 유지 — PREPARE/DONE 대기.
                // 종료는 STOP 버튼 또는 ABSENT 전이(4.5)에서.
            }

            @Override
            public void onAttendanceFailed(String reason) {
                Intent i = new Intent(AttendanceEvents.ACTION_ATTENDANCE_FAILED)
                        .setPackage(getPackageName())
                        .putExtra(AttendanceEvents.EXTRA_REASON, reason);
                sendBroadcast(i);
                stopSelf();
            }

            @Override
            public void onAbsent(String attendanceId) {
                Log.w(TAG, "ABSENT 전이 — Service 종료");
                // 1) MainActivity2(foreground)용 broadcast — Toast 표시
                Intent i = new Intent(AttendanceEvents.ACTION_ATTENDANCE_ABSENT)
                        .setPackage(getPackageName())
                        .putExtra(AttendanceEvents.EXTRA_ATTENDANCE_ID, attendanceId);
                sendBroadcast(i);
                // 2) heads-up 푸시 알림 — 앱이 background여도 사용자에게 강하게 통보
                AttendanceWarningNotifier.notifyAbsent(getApplicationContext(), attendanceId);
                // 3) Service 정리 (정책: ABSENT는 비가역 종료)
                if (controller != null) controller.stop();
                stopSelf();
            }
        };
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "출석 (학생)", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void startForegroundCompat() {
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("출석 진행 중")
                .setContentText("출석 진행 중")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }
}
