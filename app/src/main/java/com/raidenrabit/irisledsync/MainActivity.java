package com.raidenrabit.irisledsync;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.TimePickerDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressLint("MissingPermission")
public class MainActivity extends AppCompatActivity
        implements DayNightManager.Listener, ZoneManager.Listener {

    private static final int PERM_REQUEST = 1;
    private static final long SCAN_TIMEOUT = 10_000;

    private TextView    statusText;
    private TextView    tvModeActive;
    private View        cardNotifPermission;
    private View        layoutAutoSchedule;
    private MaterialButton btnDayStartTime;
    private MaterialButton btnNightStartTime;
    private ZoneAdapter zoneAdapter;

    private MaterialButtonToggleGroup toggleModeGroup;

    private boolean notifDialogShown = false;

    private final List<String>          deviceNames   = new ArrayList<>();
    private final List<BluetoothDevice> devices       = new ArrayList<>();
    private final Set<String>           seenAddresses = new HashSet<>();
    private ArrayAdapter<String>        deviceListAdapter;

    private BluetoothLeScanner scanner;
    private boolean scanning = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        statusText          = findViewById(R.id.status_text);
        tvModeActive        = findViewById(R.id.tv_mode_active);
        cardNotifPermission = findViewById(R.id.card_notif_permission);
        findViewById(R.id.btn_grant_notif).setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        // Mode toggle
        toggleModeGroup = findViewById(R.id.toggle_mode_group);
        layoutAutoSchedule = findViewById(R.id.layout_auto_schedule);
        btnDayStartTime    = findViewById(R.id.btn_day_start_time);
        btnNightStartTime  = findViewById(R.id.btn_night_start_time);

        btnDayStartTime.setOnClickListener(v -> {
            DayNightManager dnm = DayNightManager.getInstance(this);
            int cur = dnm.getDayStartMinute();
            new TimePickerDialog(this, (tp, h, m) -> {
                dnm.setDayStartMinute(h * 60 + m);
                updateAutoScheduleButtons();
                updateModeActiveLabel();
            }, cur / 60, cur % 60, true).show();
        });
        btnNightStartTime.setOnClickListener(v -> {
            DayNightManager dnm = DayNightManager.getInstance(this);
            int cur = dnm.getNightStartMinute();
            new TimePickerDialog(this, (tp, h, m) -> {
                dnm.setNightStartMinute(h * 60 + m);
                updateAutoScheduleButtons();
                updateModeActiveLabel();
            }, cur / 60, cur % 60, true).show();
        });

        syncModeToggle();
        toggleModeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            DayNightManager dnm = DayNightManager.getInstance(this);
            DayNightManager.Mode newMode;
            if      (checkedId == R.id.btn_mode_day)   newMode = DayNightManager.Mode.DAY;
            else if (checkedId == R.id.btn_mode_night) newMode = DayNightManager.Mode.NIGHT;
            else                                       newMode = DayNightManager.Mode.AUTO;
            dnm.setMode(newMode);
            updateScheduleVisibility(newMode);
            updateModeActiveLabel();
        });

        // Zone cards
        RecyclerView rvZones = findViewById(R.id.rv_zones);
        rvZones.setLayoutManager(new LinearLayoutManager(this));
        rvZones.setNestedScrollingEnabled(false);
        zoneAdapter = new ZoneAdapter(this);
        rvZones.setAdapter(zoneAdapter);

        // Service start button
        MaterialButton startButton = findViewById(R.id.start_button);
        startButton.setOnClickListener(v -> toggleService());

        // BLE scan
        MaterialButton scanButton = findViewById(R.id.scan_button);
        scanButton.setOnClickListener(v -> {
            if (checkBluetoothPermissions()) startScan(scanButton);
        });

        // BLE device list
        ListView deviceList = findViewById(R.id.device_list);
        deviceListAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_list_item_1, deviceNames);
        deviceList.setAdapter(deviceListAdapter);
        deviceList.setOnItemClickListener(this::onDeviceSelected);

        // Pad the scroll view clear of both the ActionBar and the status bar (edge-to-edge).
        // origTopPad = actionBarSize from XML; sysBars.top adds the status bar height on top.
        NestedScrollView scrollView = findViewById(R.id.scroll_view);
        final int origTopPad = scrollView.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(scrollView, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), origTopPad + sysBars.top,
                         v.getPaddingRight(), sysBars.bottom);
            return insets;
        });

        // Request Bluetooth permissions upfront — required before the foreground
        // service can use foregroundServiceType="connectedDevice" on Android 12+.
        checkBluetoothPermissions();

        updateStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        DayNightManager.getInstance(this).addListener(this);
        ZoneManager.getInstance(this).addListener(this);
        zoneAdapter.register();
        syncModeToggle();
        updateModeActiveLabel();
        updateAutoScheduleButtons();
        updateStatus();
        updateNotifPermissionState();
    }

    @Override protected void onPause() {
        super.onPause();
        DayNightManager.getInstance(this).removeListener(this);
        ZoneManager.getInstance(this).removeListener(this);
        zoneAdapter.unregister();
    }

    // ---- DayNightManager.Listener -------------------------------------------

    @Override
    public void onModeChanged(boolean isNight) {
        updateModeActiveLabel();
    }

    // ---- ZoneManager.Listener -----------------------------------------------

    @Override
    public void onZonesChanged() {
        updateStatus();
    }

    // ---- mode UI helpers -----------------------------------------------------

    private void syncModeToggle() {
        DayNightManager.Mode mode = DayNightManager.getInstance(this).getMode();
        int checkId;
        switch (mode) {
            case DAY:   checkId = R.id.btn_mode_day;   break;
            case NIGHT: checkId = R.id.btn_mode_night; break;
            default:    checkId = R.id.btn_mode_auto;  break;
        }
        toggleModeGroup.check(checkId);
        updateScheduleVisibility(mode);
    }

    private void updateScheduleVisibility(DayNightManager.Mode mode) {
        layoutAutoSchedule.setVisibility(
            mode == DayNightManager.Mode.AUTO ? View.VISIBLE : View.GONE);
    }

    private void updateModeActiveLabel() {
        DayNightManager dnm = DayNightManager.getInstance(this);
        boolean isNight = dnm.isNightActive();
        String label;
        if (dnm.getMode() == DayNightManager.Mode.AUTO) {
            int nextMin = isNight ? dnm.getDayStartMinute() : dnm.getNightStartMinute();
            label = String.format(Locale.ROOT, "Active: %s · %s at %02d:%02d",
                isNight ? "Night" : "Day",
                isNight ? "Day"   : "Night",
                nextMin / 60, nextMin % 60);
        } else {
            label = "Active: " + (isNight ? "Night" : "Day");
        }
        tvModeActive.setText(label);
    }

    private void updateAutoScheduleButtons() {
        DayNightManager dnm = DayNightManager.getInstance(this);
        btnDayStartTime.setText(String.format(Locale.ROOT, "%02d:%02d",
            dnm.getDayStartMinute()   / 60, dnm.getDayStartMinute()   % 60));
        btnNightStartTime.setText(String.format(Locale.ROOT, "%02d:%02d",
            dnm.getNightStartMinute() / 60, dnm.getNightStartMinute() % 60));
    }

    // -------------------------------------------------------------------------

    private void toggleService() {
        Intent intent = new Intent(this, CarLedService.class);
        intent.setAction(CarLedService.ACTION_START);
        startForegroundService(intent);
        Toast.makeText(this, "Service started", Toast.LENGTH_SHORT).show();
        updateStatus();
    }

    private void updateStatus() {
        SharedPreferences prefs = getSharedPreferences("aimp_led", MODE_PRIVATE);
        String addr = prefs.getString(CarLedService.PREF_BLE_ADDR, null);
        if (addr == null) {
            statusText.setText("BLE: not paired");
        } else if (ZoneManager.getInstance(this).isBleConnected()) {
            statusText.setText("BLE: ● Connected  " + addr);
        } else {
            statusText.setText("BLE: ○ Disconnected  " + addr);
        }
    }

    private void updateNotifPermissionState() {
        boolean hasAccess = hasNotificationAccess();
        cardNotifPermission.setVisibility(hasAccess ? View.GONE : View.VISIBLE);

        if (!hasAccess && !notifDialogShown) {
            notifDialogShown = true;
            new AlertDialog.Builder(this)
                .setTitle("Notification Access Required")
                .setMessage("Iris LED Sync needs notification access to read AIMP album art "
                    + "and sync LED colors to your music.\n\n"
                    + "Without it, colors will not change when tracks change.")
                .setPositiveButton("Open Settings", (d, w) ->
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
                .setNegativeButton("Later", null)
                .show();
        }
    }

    private boolean hasNotificationAccess() {
        String flat = Settings.Secure.getString(
            getContentResolver(), "enabled_notification_listeners");
        return !TextUtils.isEmpty(flat) && flat.contains(getPackageName());
    }

    // -------------------------------------------------------------------------
    // BLE scan

    private boolean checkBluetoothPermissions() {
        List<String> needed = List.of(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION);
        List<String> toRequest = new ArrayList<>();
        for (String p : needed)
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) toRequest.add(p);
        if (toRequest.isEmpty()) return true;
        ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), PERM_REQUEST);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == PERM_REQUEST) {
            for (int r : results) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Bluetooth permission required to scan",
                        Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            MaterialButton btn = findViewById(R.id.scan_button);
            startScan(btn);
        }
    }

    private void startScan(MaterialButton scanButton) {
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (bm == null) return;
        BluetoothAdapter ba = bm.getAdapter();
        if (ba == null || !ba.isEnabled()) {
            Toast.makeText(this, "Enable Bluetooth first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (scanning) return;

        deviceNames.clear(); devices.clear(); seenAddresses.clear();
        deviceListAdapter.notifyDataSetChanged();

        scanner = ba.getBluetoothLeScanner();
        if (scanner == null) return;

        scanning = true;
        scanButton.setEnabled(false);
        scanButton.setText("Scanning…");
        scanner.startScan(scanCallback);
        handler.postDelayed(() -> {
            if (scanning) stopScan(scanButton);
        }, SCAN_TIMEOUT);
    }

    private void stopScan(MaterialButton scanButton) {
        if (!scanning) return;
        scanning = false;
        scanButton.setEnabled(true);
        scanButton.setText("Scan for LED controller");
        if (scanner != null) scanner.stopScan(scanCallback);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String addr = device.getAddress();
            if (seenAddresses.contains(addr)) return;
            seenAddresses.add(addr);
            String name = device.getName();
            devices.add(device);
            deviceNames.add((name != null ? name : "Unknown") + "\n" + addr);
            deviceListAdapter.notifyDataSetChanged();
        }
    };

    private void onDeviceSelected(AdapterView<?> parent, View view, int pos, long id) {
        MaterialButton scanButton = findViewById(R.id.scan_button);
        stopScan(scanButton);
        BluetoothDevice device = devices.get(pos);
        new AlertDialog.Builder(this)
            .setTitle("Use this device?")
            .setMessage(deviceNames.get(pos))
            .setPositiveButton("Yes", (d, w) -> saveDevice(device.getAddress()))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void saveDevice(String address) {
        getSharedPreferences("aimp_led", MODE_PRIVATE)
            .edit().putString(CarLedService.PREF_BLE_ADDR, address).apply();
        Toast.makeText(this, "Device saved: " + address, Toast.LENGTH_SHORT).show();
        updateStatus();
    }
}
