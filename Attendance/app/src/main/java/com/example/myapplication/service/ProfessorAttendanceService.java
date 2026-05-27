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
import com.example.myapplication.attendance.ProfessorAttendanceController;
import com.example.myapplication.network.StartAttendanceData;

/**
 * 교수 출석 세션을 호스팅하는 Foreground Service.
 *
 * 역할:
 *   - ProfessorAttendanceController를 소유하고 생명주기를 관리
 *   - Activity가 보낸 Intent(ACTION_START / ACTION_STOP)를 명령으로 받음
 *   - Controller의 Listener 콜백을 broadcast로 변환해 Activity에 전달
 *
 * Foreground 모드:
 *   - 알림(Notification)을 띄워 시스템에게 "안 죽이고 살려둬라" 요청
 *   - Android 14+에선 foregroundServiceType=connectedDevice 명시 필수
 *
 * 종료 시점:
 *   - 5분 BLE 만료 (onSessionExpired) → stopSelf 하지 않음. ranging 단계로 전환.
 *   - 실패 (onSessionFailed) → stopSelf
 *   - Activity가 ACTION_STOP 보냄 → controller.stopSession() + stopSelf
 *
 * 휴식 시간:
 *   - Controller가 시계 시간 기반(매 시간 XX:50~XX:00)으로 자동 pause/resume.
 *   - Service/Activity 추가 작업 없음.
 */
public class ProfessorAttendanceService extends Service {

    private static final String TAG = "ProfService";
    private static final String CHANNEL_ID = "attendance_professor";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_START = "com.example.myapplication.PROF_START";
    public static final String ACTION_STOP  = "com.example.myapplication.PROF_STOP";
    public static final String EXTRA_COURSE_ID            = "courseId";
    public static final String EXTRA_PROFESSOR_ID         = "professorId";
    /** 수업 시작 epoch millis. dual-write 화면 시간 계산용. 0이면 서버가 now로 fallback. */
    public static final String EXTRA_CLASS_START_AT       = "classStartAt";

    private ProfessorAttendanceController controller;

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
            // STATE_TURNING_ON/OFF는 중간 상태라 무시
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        createChannel();
        registerBtStateReceiver();

        controller = new ProfessorAttendanceController(getApplicationContext());
        controller.setListener(new ProfessorAttendanceController.OnSessionListener() {
            @Override
            public void onSessionStarted(StartAttendanceData data) {
                Intent i = new Intent(AttendanceEvents.ACTION_SESSION_STARTED)
                        .setPackage(getPackageName())
                        .putExtra(AttendanceEvents.EXTRA_SESSION_CODE, data.getSessionCode())
                        .putExtra(AttendanceEvents.EXTRA_LECTURE_SESSION_ID, data.getLectureSessionId());
                sendBroadcast(i);
            }

            @Override
            public void onSessionFailed(String reason) {
                Intent i = new Intent(AttendanceEvents.ACTION_SESSION_FAILED)
                        .setPackage(getPackageName())
                        .putExtra(AttendanceEvents.EXTRA_REASON, reason);
                sendBroadcast(i);
                stopSelf();
            }

            @Override
            public void onSessionExpired() {
                Intent i = new Intent(AttendanceEvents.ACTION_SESSION_EXPIRED)
                        .setPackage(getPackageName());
                sendBroadcast(i);
                // BLE 광고만 만료된 것 — Service는 계속 살아있어야 함.
                // Controller가 ranging 루프로 자동 전환하고, STOP 버튼 누를 때까지 유지.
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand: action=" + (intent != null ? intent.getAction() : "null"));

        // Foreground 진입은 매 onStartCommand마다 보장.
        // (Android 14+: startForegroundService 후 5초 안에 startForeground 호출 안 하면 강제 종료)
        startForegroundCompat();

        if (intent == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            String courseId    = intent.getStringExtra(EXTRA_COURSE_ID);
            String professorId = intent.getStringExtra(EXTRA_PROFESSOR_ID);
            long classStartAt  = intent.getLongExtra(EXTRA_CLASS_START_AT, 0L);
            controller.startSession(courseId, professorId, classStartAt);
        } else if (ACTION_STOP.equals(action)) {
            controller.stopSession();
            stopSelf();
        }

        // START_NOT_STICKY: 시스템이 메모리 부족으로 죽인 경우 자동 재시작 안 함.
        // (재시작해도 sessionCode가 사라져 의미 없음. 사용자가 다시 시작하는 게 깔끔.)
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        try {
            unregisterReceiver(btStateReceiver);
        } catch (IllegalArgumentException ignored) {
            // 등록 안 된 상태에서 onDestroy 호출 시
        }
        if (controller != null) controller.stopSession();
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
        // Started Service만 사용. Bound 안 받음.
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "출석 세션 (교수)", NotificationManager.IMPORTANCE_LOW);
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
