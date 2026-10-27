package com.apramed.smartchartrc;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Faz o celular funcionar como um teclado Bluetooth (perfil HID Device, Android 9+).
 * Todos os métodos assumem que as permissões Bluetooth já foram concedidas.
 */
@SuppressLint("MissingPermission")
public class HidKeyboard {

    public interface Listener {
        void onRegistered(boolean registered);
        void onConnectionChanged(BluetoothDevice device, int state);
        void onError(String message);
        void onLog(String line);
    }

    private final List<Runnable> onRegisteredQueue = new ArrayList<>();

    /** Executa a ação assim que o app estiver registrado como teclado Bluetooth. */
    public void whenRegistered(Runnable r) {
        if (registered) { main.post(r); return; }
        synchronized (onRegisteredQueue) { onRegisteredQueue.add(r); }
        start();
    }

    public boolean isRegistered() { return registered; }

    private void log(String s) {
        Log.i(TAG, s);
        main.post(() -> listener.onLog(s));
    }

    private static String stateName(int s) {
        switch (s) {
            case BluetoothProfile.STATE_CONNECTED: return "CONECTADO";
            case BluetoothProfile.STATE_CONNECTING: return "CONECTANDO";
            case BluetoothProfile.STATE_DISCONNECTING: return "DESCONECTANDO";
            default: return "DESCONECTADO";
        }
    }

    private static final String TAG = "HidKeyboard";
    private static final int REPORT_ID = 1;

    /** Descritor HID de teclado padrão (8 bytes: modificadores, reservado, 6 teclas). */
    private static final byte[] DESCRIPTOR = {
            0x05, 0x01,             // Usage Page (Generic Desktop)
            0x09, 0x06,             // Usage (Keyboard)
            (byte) 0xA1, 0x01,      // Collection (Application)
            (byte) 0x85, REPORT_ID, //   Report ID
            0x05, 0x07,             //   Usage Page (Key Codes)
            0x19, (byte) 0xE0,      //   Usage Minimum (224)
            0x29, (byte) 0xE7,      //   Usage Maximum (231)
            0x15, 0x00,             //   Logical Minimum (0)
            0x25, 0x01,             //   Logical Maximum (1)
            0x75, 0x01,             //   Report Size (1)
            (byte) 0x95, 0x08,      //   Report Count (8)
            (byte) 0x81, 0x02,      //   Input (Data, Var, Abs) — modificadores
            (byte) 0x95, 0x01,      //   Report Count (1)
            0x75, 0x08,             //   Report Size (8)
            (byte) 0x81, 0x01,      //   Input (Const) — reservado
            (byte) 0x95, 0x05,      //   Report Count (5)
            0x75, 0x01,             //   Report Size (1)
            0x05, 0x08,             //   Usage Page (LEDs)
            0x19, 0x01,             //   Usage Minimum (1)
            0x29, 0x05,             //   Usage Maximum (5)
            (byte) 0x91, 0x02,      //   Output (Data, Var, Abs) — LEDs
            (byte) 0x95, 0x01,      //   Report Count (1)
            0x75, 0x03,             //   Report Size (3)
            (byte) 0x91, 0x01,      //   Output (Const) — padding
            (byte) 0x95, 0x06,      //   Report Count (6)
            0x75, 0x08,             //   Report Size (8)
            0x15, 0x00,             //   Logical Minimum (0)
            0x25, 0x65,             //   Logical Maximum (101)
            0x05, 0x07,             //   Usage Page (Key Codes)
            0x19, 0x00,             //   Usage Minimum (0)
            0x29, 0x65,             //   Usage Maximum (101)
            (byte) 0x81, 0x00,      //   Input (Data, Array) — teclas
            (byte) 0xC0             // End Collection
    };

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor executor = Executors.newSingleThreadExecutor();
    private final BluetoothAdapter adapter;

    private BluetoothHidDevice hid;
    private BluetoothDevice connected;
    private BluetoothDevice pendingConnect;
    private boolean registered;
    private final List<Integer> held = new ArrayList<>();

    public HidKeyboard(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm != null ? bm.getAdapter() : null;
    }

    public BluetoothAdapter getAdapter() { return adapter; }
    public boolean isConnected() { return connected != null; }
    public BluetoothDevice getConnected() { return connected; }

    public void start() {
        if (adapter == null) { postError("Este aparelho não tem Bluetooth."); return; }
        if (!adapter.isEnabled()) { postError("Ative o Bluetooth."); return; }
        if (hid != null) { register(); return; }
        boolean ok = adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile != BluetoothProfile.HID_DEVICE) return;
                hid = (BluetoothHidDevice) proxy;
                register();
            }
            @Override public void onServiceDisconnected(int profile) {
                if (profile != BluetoothProfile.HID_DEVICE) return;
                hid = null; registered = false; connected = null;
                main.post(() -> listener.onRegistered(false));
            }
        }, BluetoothProfile.HID_DEVICE);
        if (!ok) postError("Este aparelho não suporta o modo teclado Bluetooth (HID).");
    }

    private void register() {
        if (hid == null || registered) return;
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "SmartChart RC", "Controle remoto SmartChart", "Apramed",
                BluetoothHidDevice.SUBCLASS1_KEYBOARD, DESCRIPTOR);
        boolean ok = hid.registerApp(sdp, null, null, executor, callback);
        log("registerApp() = " + ok);
        if (!ok) postError("Falha ao registrar o teclado Bluetooth. Feche outros apps de teclado/controle e tente de novo.");
    }

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice plugged, boolean reg) {
            registered = reg;
            log("Teclado BT registrado: " + reg + (plugged != null ? " (host " + plugged.getAddress() + ")" : ""));
            main.post(() -> listener.onRegistered(reg));
            if (reg) {
                List<Runnable> q;
                synchronized (onRegisteredQueue) { q = new ArrayList<>(onRegisteredQueue); onRegisteredQueue.clear(); }
                for (Runnable r : q) main.post(r);
                BluetoothDevice target = pendingConnect != null ? pendingConnect : plugged;
                pendingConnect = null;
                if (target != null) hid.connect(target);
            }
        }
        @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
            log("Conexão " + device.getAddress() + ": " + stateName(state)
                    + " | pareado=" + (device.getBondState() == BluetoothDevice.BOND_BONDED));
            if (state == BluetoothProfile.STATE_CONNECTED) connected = device;
            else if (state == BluetoothProfile.STATE_DISCONNECTED && device.equals(connected)) connected = null;
            main.post(() -> listener.onConnectionChanged(device, state));
        }
        @Override public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            hid.replyReport(device, type, id, new byte[8]);
        }
        @Override public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            hid.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS);
        }
    };

    public void connect(BluetoothDevice device) {
        if (hid == null || !registered) { pendingConnect = device; start(); return; }
        if (connected != null && !connected.equals(device)) hid.disconnect(connected);
        boolean ok = hid.connect(device);
        log("connect(" + device.getAddress() + ") = " + ok);
        if (!ok) postError("Não foi possível iniciar a conexão.");
    }

    public void disconnect() {
        if (hid != null && connected != null) hid.disconnect(connected);
    }

    /** Tecla pressionada (o SmartChart vê "tecla segurada" até keyUp — permite repetição). */
    public synchronized void keyDown(int usage) {
        if (!held.contains(usage)) {
            if (held.size() >= 6) held.remove(0);
            held.add(usage);
        }
        sendHeld();
    }

    public synchronized void keyUp(int usage) {
        held.remove(Integer.valueOf(usage));
        sendHeld();
    }

    public synchronized void releaseAll() {
        held.clear();
        sendHeld();
    }

    private void sendHeld() {
        if (hid == null || connected == null) return;
        byte[] report = new byte[8];
        for (int i = 0; i < held.size(); i++) report[2 + i] = (byte) (int) held.get(i);
        final BluetoothDevice dev = connected;
        executor.execute(() -> {
            try {
                hid.sendReport(dev, REPORT_ID, report);
            } catch (Exception e) {
                Log.w(TAG, "sendReport", e);
            }
        });
    }

    public void stop() {
        try {
            if (hid != null) {
                if (connected != null) hid.disconnect(connected);
                hid.unregisterApp();
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
            }
        } catch (Exception e) {
            Log.w(TAG, "stop", e);
        }
        hid = null; registered = false; connected = null;
    }

    private void postError(String msg) {
        log("ERRO: " + msg);
        main.post(() -> listener.onError(msg));
    }
}
