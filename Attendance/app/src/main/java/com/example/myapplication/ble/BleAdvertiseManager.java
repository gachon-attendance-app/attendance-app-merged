package com.example.myapplication.ble;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.nio.charset.StandardCharsets;

/**
 * BLE 광고 관리자.
 *
 * Service-ready 하드닝 적용:
 *   - 내부 권한/BT 상태 체크 (Service에서 호출돼도 안전)
 *   - SecurityException 방어 (권한 회수 race)
 *   - volatile 상태 (다른 스레드 가시성)
 *   - 콜백 main thread Handler dispatch
 *   - reinit() — BT 재시작 시 advertiser 재획득 (Service의 BT BroadcastReceiver가 호출)
 */
public class BleAdvertiseManager {

    private static final String TAG = "BleAdvertise";
    private static final String UUID_STR = "12345678-1234-1234-1234-1234567890ab";
    private static final long DEFAULT_DURATION_MS = 1 * 30_000; // 5분

    private final Context context;
    private BluetoothAdapter adapter;
    private BluetoothLeAdvertiser advertiser;
    private OnAdvertiseListener listener;
    private volatile boolean isAdvertising = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable stopRunnable;

    public BleAdvertiseManager(Context context) {
        this.context = context.getApplicationContext();
        init();
    }

    private void init() {
        BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);

        if (manager == null) {
            Log.e(TAG, "BluetoothManager null");
            return;
        }

        adapter = manager.getAdapter();
        if (adapter == null) {
            Log.e(TAG, "BluetoothAdapter null - 블루투스 미지원 기기");
            return;
        }

        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            Log.e(TAG, "BluetoothLeAdvertiser null - BLE Advertise 미지원 기기");
        }
    }

    public void startAdvertising(String sessionCode) {
        startAdvertising(sessionCode, DEFAULT_DURATION_MS);
    }

    public void startAdvertising(String sessionCode, long durationMs) {
        Log.d(TAG, "startAdvertising 진입: " + sessionCode + " (" + durationMs + "ms)");

        // 권한 체크 (Service 컨텍스트에서도 안전하도록 내부 방어)
        if (!hasAdvertisePermission()) {
            notifyFailure("BLUETOOTH_ADVERTISE 권한 없음");
            return;
        }

        if (advertiser == null) {
            notifyFailure("advertiser null (미지원 기기 또는 BT off)");
            return;
        }

        if (adapter == null || !adapter.isEnabled()) {
            notifyFailure("Bluetooth 꺼져있음");
            return;
        }

        if (isAdvertising) {
            Log.w(TAG, "이미 광고 중, 무시");
            return;
        }

        ParcelUuid pUuid = ParcelUuid.fromString(UUID_STR);

        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceData(pUuid, sessionCode.getBytes(StandardCharsets.UTF_8))
                .build();

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setConnectable(false)
                .build();

        try {
            advertiser.startAdvertising(settings, data, callback);
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException", e);
            notifyFailure("권한 예외: " + e.getMessage());
            return;
        }

        // 자동 중단 예약
        stopRunnable = () -> {
            Log.d(TAG, "광고 시간 만료 (" + durationMs + "ms)");
            stopAdvertising();
            notifyExpired();
        };
        handler.postDelayed(stopRunnable, durationMs);
    }

    public void stopAdvertising() {
        if (advertiser != null && isAdvertising) {
            if (hasAdvertisePermission()) {
                try {
                    advertiser.stopAdvertising(callback);
                } catch (SecurityException e) {
                    Log.e(TAG, "stopAdvertising SecurityException", e);
                }
            }
            isAdvertising = false;
            Log.d(TAG, "광고 중단");
        }
        if (stopRunnable != null) {
            handler.removeCallbacks(stopRunnable);
            stopRunnable = null;
        }
    }

    /**
     * BT 재시작 후 advertiser 인스턴스 재획득. STATE_ON 수신 시 호출.
     * adapter/advertiser 참조가 stale일 수 있어 init() 재실행.
     * @return advertiser 사용 가능하면 true
     */
    public boolean reinit() {
        init();
        return advertiser != null;
    }

    public void setListener(OnAdvertiseListener listener) {
        this.listener = listener;
    }

    private boolean hasAdvertisePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        // Android 11 이하: Manifest BLUETOOTH/BLUETOOTH_ADMIN은 일반권한이라 자동 승인
        return true;
    }

    private final AdvertiseCallback callback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.d(TAG, "광고 시작 성공");
            isAdvertising = true;
            notifySuccess();
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.e(TAG, "광고 실패: " + errorCode);
            isAdvertising = false;
            // 시작 실패했으니 예약된 자동 중단도 취소
            if (stopRunnable != null) {
                handler.removeCallbacks(stopRunnable);
                stopRunnable = null;
            }
            notifyFailure("errorCode: " + errorCode);
        }
    };

    private void notifySuccess() {
        final OnAdvertiseListener l = listener;
        if (l != null) handler.post(l::onAdvertiseStarted);
    }

    private void notifyFailure(String reason) {
        final OnAdvertiseListener l = listener;
        if (l != null) handler.post(() -> l.onAdvertiseFailed(reason));
    }

    private void notifyExpired() {
        final OnAdvertiseListener l = listener;
        if (l != null) handler.post(l::onAdvertiseExpired);
    }

    public interface OnAdvertiseListener {
        void onAdvertiseStarted();
        void onAdvertiseFailed(String reason);
        void onAdvertiseExpired();
    }
}
