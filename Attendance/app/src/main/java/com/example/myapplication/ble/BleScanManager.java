package com.example.myapplication.ble;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * BLE 스캔 관리자.
 *
 * Service-ready 하드닝 적용:
 *   - 내부 권한/BT 상태 체크
 *   - SecurityException 방어
 *   - volatile 상태
 *   - 콜백 main thread Handler dispatch
 *   - reinit() — BT 재시작 시 scanner 재획득 (Service의 BT BroadcastReceiver가 호출)
 */
public class BleScanManager {

    private static final String TAG = "BleScan";
    private static final String UUID_STR = "12345678-1234-1234-1234-1234567890ab";
    private static final long DEFAULT_TIMEOUT_MS = 30_000;

    private final Context context;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private OnScanListener listener;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;
    private volatile boolean isScanning = false;

    // 이미 Activity/Service에 보고한 sessionCode 집합 (중복 보고 방지).
    // 같은 코드는 여러 번 안 보내지만, 다른 코드는 계속 보고함.
    private final Set<String> reportedCodes = new HashSet<>();

    public BleScanManager(Context context) {
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

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            Log.e(TAG, "BluetoothLeScanner null");
        }
    }

    public void startScan() {
        startScan(DEFAULT_TIMEOUT_MS);
    }

    public void startScan(long timeoutMs) {
        Log.d(TAG, "startScan 진입");

        if (!hasScanPermission()) {
            notifyFailure("BLUETOOTH_SCAN 권한 없음");
            return;
        }

        if (scanner == null) {
            notifyFailure("scanner null (미지원 기기 또는 BT off)");
            return;
        }

        if (adapter == null || !adapter.isEnabled()) {
            notifyFailure("Bluetooth 꺼져있음");
            return;
        }

        if (isScanning) {
            Log.w(TAG, "이미 스캔 중, 무시");
            return;
        }

        reportedCodes.clear();

        // ScanFilter 제거: 모든 BLE 광고를 받아서 onScanResult에서 ServiceData로 직접 필터링.
        // 이전 setServiceData(uuid, byte[0], byte[0]) 필터는 일부 기기에서 매칭 실패.
        // setServiceUuid 필터도 광고 측이 addServiceUuid를 안 해서 매칭 안 됨.
        // Foreground Service라 ScanFilter 없어도 OK.
        // (TODO: 안정화되면 광고에 addServiceUuid 추가하고 필터 복원해 배터리 최적화)
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        try {
            scanner.startScan(null, settings, callback);
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException", e);
            notifyFailure("권한 예외: " + e.getMessage());
            return;
        }

        isScanning = true;
        Log.d(TAG, "스캔 시작");

        timeoutRunnable = () -> {
            Log.w(TAG, "스캔 타임아웃 (" + timeoutMs + "ms)");
            stopScan();
            notifyTimeout();
        };
        handler.postDelayed(timeoutRunnable, timeoutMs);
    }

    public void stopScan() {
        if (scanner != null && isScanning) {
            if (hasScanPermission()) {
                try {
                    scanner.stopScan(callback);
                } catch (SecurityException e) {
                    Log.e(TAG, "stopScan SecurityException", e);
                }
            }
            isScanning = false;
            Log.d(TAG, "스캔 중단");
        }
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    /**
     * BT 재시작 후 scanner 인스턴스 재획득. STATE_ON 수신 시 호출.
     * adapter/scanner 참조가 stale일 수 있어 init() 재실행.
     * @return scanner 사용 가능하면 true
     */
    public boolean reinit() {
        init();
        return scanner != null;
    }

    public void setListener(OnScanListener listener) {
        this.listener = listener;
    }

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        // Android 11 이하: 위치 권한이 BLE 스캔에 필요
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private final ScanCallback callback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (!isScanning) return;
            if (result == null || result.getScanRecord() == null) return;

            ScanRecord record = result.getScanRecord();
            ParcelUuid targetUuid = ParcelUuid.fromString(UUID_STR);
            byte[] serviceData = record.getServiceData(targetUuid);
            if (serviceData == null) return;

            String sessionCode = new String(serviceData, StandardCharsets.UTF_8);
            if (sessionCode.isEmpty()) {
                Log.e(TAG, "sessionCode 비어있음");
                return;
            }

            // 같은 코드 중복 보고 방지
            if (reportedCodes.contains(sessionCode)) return;
            reportedCodes.add(sessionCode);

            Log.d(TAG, "sessionCode 감지 (서버 확인 필요): " + sessionCode);
            // 자동 stopScan 제거. 서버 확인 후 Controller가 stopScan 호출.
            notifyFound(sessionCode, result);
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "스캔 실패: " + errorCode);
            isScanning = false;
            notifyFailure("errorCode: " + errorCode);
        }
    };

    private void notifyFound(String sessionCode, ScanResult result) {
        final OnScanListener l = listener;
        if (l != null) handler.post(() -> l.onSessionCodeFound(sessionCode, result));
    }

    private void notifyFailure(String reason) {
        final OnScanListener l = listener;
        if (l != null) handler.post(() -> l.onScanFailed(reason));
    }

    private void notifyTimeout() {
        final OnScanListener l = listener;
        if (l != null) handler.post(l::onScanTimeout);
    }

    public interface OnScanListener {
        /**
         * BLE 광고에서 sessionCode를 추출했을 때 호출.
         * 같은 sessionCode는 한 번만 보고됨 (다른 값은 계속 보고).
         * 스캔은 자동 중단되지 않음. 서버 확인 후 stopScan()을 수동 호출할 것.
         */
        void onSessionCodeFound(String sessionCode, ScanResult result);
        void onScanFailed(String reason);
        void onScanTimeout();
    }
}
