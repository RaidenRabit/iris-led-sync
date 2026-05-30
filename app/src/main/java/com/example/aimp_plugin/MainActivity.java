package com.example.aimp_plugin;

import android.Manifest;
import android.annotation.SuppressLint;
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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressLint("MissingPermission")
public class MainActivity extends AppCompatActivity {

    private static final int PERM_REQUEST = 1;
    private static final long SCAN_TIMEOUT = 10_000;

    private TextView    statusText;
    private ZoneAdapter zoneAdapter;

    private final List<String>        deviceNames = new ArrayList<>();
    private final List<BluetoothDevice> devices   = new ArrayList<>();
    private final Set<String>       seenAddresses = new HashSet<>();
    private ArrayAdapter<String>    deviceListAdapter;

    private BluetoothLeScanner scanner;
    private boolean scanning = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);

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

        updateStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        zoneAdapter.register();
        updateStatus();
    }

    @Override protected void onPause() {
        super.onPause();
        zoneAdapter.unregister();
    }

    // -------------------------------------------------------------------------

    private void toggleService() {
        SharedPreferences prefs = getSharedPreferences("aimp_led", MODE_PRIVATE);
        if (prefs.getString(CarLedService.PREF_BLE_ADDR, null) == null) {
            Toast.makeText(this, "Select a BLE device first", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, CarLedService.class);
        intent.setAction(CarLedService.ACTION_START);
        startForegroundService(intent);
        Toast.makeText(this, "Service started", Toast.LENGTH_SHORT).show();
        updateStatus();
    }

    private void updateStatus() {
        SharedPreferences prefs = getSharedPreferences("aimp_led", MODE_PRIVATE);
        String addr = prefs.getString(CarLedService.PREF_BLE_ADDR, null);
        boolean hasNotifAccess = hasNotificationAccess();

        String notifStatus = hasNotifAccess ? "✓ Notification access granted"
                                            : "✗ Notification access — tap to grant";
        String deviceStatus = addr != null ? "Device: " + addr : "Device: not paired";
        statusText.setText(notifStatus + "\n" + deviceStatus);

        if (!hasNotifAccess) {
            statusText.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        } else {
            statusText.setOnClickListener(null);
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
