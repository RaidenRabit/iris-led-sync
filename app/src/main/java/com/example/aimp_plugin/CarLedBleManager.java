package com.example.aimp_plugin;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Manages BLE connection to the car LED controller and sends color packets.
 * Thread-safe: color updates may be posted from any thread; writes are
 * serialized on the main looper to satisfy GATT's single-operation constraint.
 */
@SuppressLint("MissingPermission")
public class CarLedBleManager {

    private static final String TAG = "CarLedBle";

    private static final int RECONNECT_DELAY = 5_000; // ms between reconnect attempts

    // Lerp animation: 30 steps over ~1 second (33 ms each)
    private static final int LERP_STEPS    = 30;
    private static final int LERP_INTERVAL = 33; // ms

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private boolean ready = false; // services discovered and characteristic found
    private Runnable onReadyCallback;

    // Queued outgoing writes (serialized via GATT callback)
    private final LinkedBlockingQueue<byte[]> writeQueue = new LinkedBlockingQueue<>();
    private boolean writePending = false;

    // Bumped on every disconnect; postDelayed retries capture this at schedule
    // time and bail out if it changed, preventing stale callbacks from firing
    // after a reconnect.
    private volatile int drainGeneration = 0;

    // Non-null while auto-reconnect is armed (cleared by explicit disconnect()).
    private String reconnectAddress;

    // Current displayed color (for lerp start)
    private int currentColor = Color.BLACK;

    // Runnable managing the active lerp animation
    private Runnable lerpRunnable;

    // -------------------------------------------------------------------------

    public CarLedBleManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Connect to a device by address (e.g. "AA:BB:CC:DD:EE:FF"). */
    public void connect(String address) {
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) return;
        BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "Bluetooth not enabled");
            return;
        }
        reconnectAddress = address;
        BluetoothDevice device = adapter.getRemoteDevice(address);
        disconnect();
        reconnectAddress = address; // restore after disconnect() clears it
        Log.i(TAG, "Connecting to " + address);
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    public void disconnect() {
        reconnectAddress = null;
        drainGeneration++; // invalidate any pending drainQueue retries
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        ready = false;
        writeChar = null;
        writeQueue.clear();
        writePending = false;
    }

    public boolean isReady() { return ready; }

    public void setOnReadyCallback(Runnable cb) { onReadyCallback = cb; }

    /**
     * Animate from the current displayed color to {@code targetArgb} over ~1 s.
     * Safe to call from any thread; cancels any in-progress animation.
     */
    public void animateToColor(int targetArgb) {
        handler.post(() -> startLerp(targetArgb));
    }

    // -------------------------------------------------------------------------
    // Lerp animation (runs on main looper)

    private void startLerp(int target) {
        if (lerpRunnable != null) handler.removeCallbacks(lerpRunnable);

        final int from = currentColor;
        final int to   = target;
        final int[] step = {0};

        lerpRunnable = new Runnable() {
            @Override
            public void run() {
                step[0]++;
                float t = (float) step[0] / LERP_STEPS;
                int color = lerpColor(from, to, Math.min(t, 1f));
                currentColor = color;
                sendColor(color);
                if (step[0] < LERP_STEPS) {
                    handler.postDelayed(this, LERP_INTERVAL);
                } else {
                    lerpRunnable = null;
                }
            }
        };
        handler.post(lerpRunnable);
    }

    private static int lerpColor(int a, int b, float t) {
        int r = (int) (Color.red(a)   + t * (Color.red(b)   - Color.red(a)));
        int g = (int) (Color.green(a) + t * (Color.green(b) - Color.green(a)));
        int bl= (int) (Color.blue(a)  + t * (Color.blue(b)  - Color.blue(a)));
        return Color.rgb(r, g, bl);
    }

    private void sendColor(int argb) {
        if (!ready) return;
        int r = android.graphics.Color.red(argb), g = android.graphics.Color.green(argb),
            b = android.graphics.Color.blue(argb);
        enqueueWrite(CarLedProtocol.buildColorPacket(r, g, b, r, g, b));
    }

    /** Public entry point for ZoneManager and other callers. */
    public void enqueuePacket(byte[] packet) {
        if (!ready) return;
        enqueueWrite(packet);
    }

    // -------------------------------------------------------------------------
    // GATT write queue

    private void enqueueWrite(byte[] packet) {
        writeQueue.offer(packet);
        if (!writePending) drainQueue();
    }

    private void drainQueue() {
        if (writeChar == null || gatt == null) { writePending = false; return; }
        byte[] next = writeQueue.poll();
        if (next == null) { writePending = false; return; }
        writePending = true;
        writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        writeChar.setValue(next);
        boolean ok = gatt.writeCharacteristic(writeChar);
        if (!ok) {
            Log.w(TAG, "writeCharacteristic returned false; retrying");
            final int gen = drainGeneration;
            handler.postDelayed(() -> { if (drainGeneration == gen) drainQueue(); }, 100);
        }
    }

    // -------------------------------------------------------------------------
    // GATT callbacks

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected, discovering services…");
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected");
                ready = false;
                writeChar = null;
                drainGeneration++; // cancel any pending retries
                writeQueue.clear();
                writePending = false;
                String addr = reconnectAddress;
                if (addr != null) {
                    Log.i(TAG, "Scheduling reconnect in " + RECONNECT_DELAY + " ms");
                    handler.postDelayed(() -> connect(addr), RECONNECT_DELAY);
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed: " + status);
                return;
            }
            BluetoothGattService svc = g.getService(UUID.fromString(CarLedProtocol.SERVICE_UUID));
            if (svc == null) {
                Log.w(TAG, "LED service not found");
                return;
            }
            writeChar = svc.getCharacteristic(UUID.fromString(CarLedProtocol.WRITE_CHAR_UUID));
            if (writeChar == null) {
                Log.w(TAG, "Write characteristic not found");
                return;
            }

            // Enable notifications on the notify characteristic, then mark ready
            // in onDescriptorWrite so the GATT is free before we start writing.
            BluetoothGattCharacteristic notifyChar =
                svc.getCharacteristic(UUID.fromString(CarLedProtocol.NOTIFY_CHAR_UUID));
            if (notifyChar != null) {
                g.setCharacteristicNotification(notifyChar, true);
                BluetoothGattDescriptor desc =
                    notifyChar.getDescriptor(UUID.fromString(CarLedProtocol.CCCD_UUID));
                if (desc != null) {
                    desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(desc);
                    return; // markReady() called from onDescriptorWrite
                }
            }

            // No descriptor to write — safe to start sending immediately.
            markReady();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            // Previous write complete; send next in queue
            writePending = false;
            drainQueue();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            // Descriptor write finished; GATT is now free for characteristic writes.
            markReady();
        }
    };

    private void markReady() {
        ready = true;
        Log.i(TAG, "Ready to send color packets");
        if (onReadyCallback != null) handler.post(onReadyCallback);
    }
}
