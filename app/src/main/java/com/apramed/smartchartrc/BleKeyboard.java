package com.apramed.smartchartrc;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Teclado Bluetooth LE (HID over GATT — o mesmo tipo dos controles remotos de Android TV).
 * O celular anuncia o serviço HID; a TV encontra em "Parear acessório", pareia e conecta.
 */
@SuppressLint("MissingPermission")
public class BleKeyboard {

    public interface Listener {
        void onBleConnected(BluetoothDevice host);
        void onBleDisconnected(BluetoothDevice host);
        void onBleAdvertising(boolean on);
        void onLog(String line);
    }

    private static final String TAG = "BleKeyboard";

    private static UUID u16(int v) {
        return UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v));
    }

    static final UUID SVC_HID = u16(0x1812);
    static final UUID SVC_DIS = u16(0x180A);
    static final UUID SVC_BAS = u16(0x180F);
    static final UUID CH_HID_INFO = u16(0x2A4A);
    static final UUID CH_REPORT_MAP = u16(0x2A4B);
    static final UUID CH_HID_CTRL = u16(0x2A4C);
    static final UUID CH_REPORT = u16(0x2A4D);
    static final UUID CH_PROTO_MODE = u16(0x2A4E);
    static final UUID CH_PNP_ID = u16(0x2A50);
    static final UUID CH_MANUF = u16(0x2A29);
    static final UUID CH_BATTERY = u16(0x2A19);
    static final UUID DS_CCCD = u16(0x2902);
    static final UUID DS_REPORT_REF = u16(0x2908);

    /** Teclado: Report ID 1, 8 bytes (modificadores, reservado, 6 teclas). */
    private static final byte[] REPORT_MAP = {
            0x05, 0x01, 0x09, 0x06, (byte) 0xA1, 0x01,
            (byte) 0x85, 0x01,
            0x05, 0x07, 0x19, (byte) 0xE0, 0x29, (byte) 0xE7, 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, (byte) 0x95, 0x08, (byte) 0x81, 0x02,
            (byte) 0x95, 0x01, 0x75, 0x08, (byte) 0x81, 0x01,
            (byte) 0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, (byte) 0x81, 0x00,
            (byte) 0xC0
    };

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BluetoothManager manager;
    private final BluetoothAdapter adapter;

    private BluetoothGattServer server;
    private BluetoothGattCharacteristic inputReport;
    private BluetoothLeAdvertiser advertiser;
    private boolean advertising;
    private boolean servicesReady;
    private boolean wantAdvertise;
    private boolean nameInScanResponse = true;

    private final List<BluetoothGattService> pendingServices = new ArrayList<>();
    private final Map<String, byte[]> values = new HashMap<>();
    private final Map<String, byte[]> cccd = new HashMap<>();

    private BluetoothDevice host;            // TV inscrita nas teclas
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private boolean sending;
    private final List<Integer> held = new ArrayList<>();

    public BleKeyboard(Context ctx, Listener l) {
        context = ctx.getApplicationContext();
        listener = l;
        manager = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager != null ? manager.getAdapter() : null;
    }

    public boolean isConnected() { return host != null; }
    public BluetoothDevice getHost() { return host; }
    public boolean isAdvertising() { return advertising; }

    private void log(String s) {
        Log.i(TAG, s);
        main.post(() -> listener.onLog("[BLE] " + s));
    }

    // ------------------------------------------------------------ Início

    public void start() {
        if (adapter == null || !adapter.isEnabled()) return;
        if (server == null) {
            if (!adapter.isMultipleAdvertisementSupported()) {
                log("Este celular não suporta anunciar BLE");
                return;
            }
            server = manager.openGattServer(context, gattCallback);
            if (server == null) { log("openGattServer falhou"); return; }
            buildServices();
            addNextService();
        }
        wantAdvertise = true;
        if (servicesReady && host == null) startAdvertising();
    }

    private void buildServices() {
        int RE = BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED;
        int WE = BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED;
        int R = BluetoothGattCharacteristic.PERMISSION_READ;

        // --- HID
        BluetoothGattService hid = new BluetoothGattService(SVC_HID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        hid.addCharacteristic(ch(CH_HID_INFO, BluetoothGattCharacteristic.PROPERTY_READ, RE,
                new byte[]{0x11, 0x01, 0x00, 0x02}));
        hid.addCharacteristic(ch(CH_REPORT_MAP, BluetoothGattCharacteristic.PROPERTY_READ, RE, REPORT_MAP));
        hid.addCharacteristic(ch(CH_HID_CTRL, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, WE,
                new byte[]{0x00}));
        hid.addCharacteristic(ch(CH_PROTO_MODE,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                RE | WE, new byte[]{0x01}));
        inputReport = ch(CH_REPORT,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                RE, new byte[8]);
        BluetoothGattDescriptor cc = new BluetoothGattDescriptor(DS_CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        inputReport.addDescriptor(cc);
        BluetoothGattDescriptor ref = new BluetoothGattDescriptor(DS_REPORT_REF,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED);
        values.put(key(CH_REPORT, DS_REPORT_REF), new byte[]{0x01, 0x01}); // ID 1, Input
        inputReport.addDescriptor(ref);
        hid.addCharacteristic(inputReport);

        // --- Device Information
        BluetoothGattService dis = new BluetoothGattService(SVC_DIS, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        dis.addCharacteristic(ch(CH_MANUF, BluetoothGattCharacteristic.PROPERTY_READ, R,
                "Apramed".getBytes(StandardCharsets.UTF_8)));
        dis.addCharacteristic(ch(CH_PNP_ID, BluetoothGattCharacteristic.PROPERTY_READ, R,
                new byte[]{0x02, 0x41, 0x50, 0x01, 0x00, 0x01, 0x00}));

        // --- Bateria (as TVs costumam ler)
        BluetoothGattService bas = new BluetoothGattService(SVC_BAS, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic bat = ch(CH_BATTERY,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                R, new byte[]{100});
        bat.addDescriptor(new BluetoothGattDescriptor(DS_CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        bas.addCharacteristic(bat);

        pendingServices.add(dis);
        pendingServices.add(bas);
        pendingServices.add(hid);
    }

    private BluetoothGattCharacteristic ch(UUID uuid, int props, int perms, byte[] value) {
        values.put(uuid.toString(), value);
        return new BluetoothGattCharacteristic(uuid, props, perms);
    }

    private static String key(UUID ch, UUID ds) { return ch + "/" + ds; }

    private void addNextService() {
        if (pendingServices.isEmpty()) {
            servicesReady = true;
            log("Serviços GATT prontos");
            if (wantAdvertise && host == null) main.post(this::startAdvertising);
            return;
        }
        BluetoothGattService s = pendingServices.remove(0);
        if (!server.addService(s)) log("addService falhou: " + s.getUuid());
    }

    // ------------------------------------------------------------ Anúncio

    private void startAdvertising() {
        if (advertising || adapter == null || !adapter.isEnabled()) return;
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) { log("Sem anunciante BLE"); return; }
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(new ParcelUuid(SVC_HID))
                .addServiceUuid(new ParcelUuid(SVC_BAS))
                .build();
        AdvertiseData scanResp = new AdvertiseData.Builder()
                .setIncludeDeviceName(nameInScanResponse)
                .build();
        advertiser.startAdvertising(settings, data, scanResp, advCallback);
    }

    private final AdvertiseCallback advCallback = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings s) {
            advertising = true;
            log("Anunciando como teclado BLE (nome: " + adapter.getName() + ")");
            main.post(() -> listener.onBleAdvertising(true));
        }
        @Override public void onStartFailure(int errorCode) {
            advertising = false;
            log("Falha ao anunciar: código " + errorCode);
            if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && nameInScanResponse) {
                nameInScanResponse = false;
                main.post(BleKeyboard.this::startAdvertising);
            } else if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) {
                advertising = true;
            }
            main.post(() -> listener.onBleAdvertising(advertising));
        }
    };

    private void stopAdvertising() {
        if (advertiser != null && advertising) {
            try { advertiser.stopAdvertising(advCallback); } catch (Exception ignored) { }
        }
        advertising = false;
        main.post(() -> listener.onBleAdvertising(false));
    }

    // ------------------------------------------------------------ GATT

    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
        @Override public void onServiceAdded(int status, BluetoothGattService service) {
            if (status != BluetoothGatt.GATT_SUCCESS) log("Serviço " + service.getUuid() + " status " + status);
            addNextService();
        }

        @Override public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            log("Link LE " + device.getAddress() + ": "
                    + (newState == BluetoothProfile.STATE_CONNECTED ? "conectado" : "desconectado")
                    + " (status " + status + ", pareado=" + (device.getBondState() == BluetoothDevice.BOND_BONDED) + ")");
            if (newState == BluetoothProfile.STATE_CONNECTED && host == null && isKnownHost(device)
                    && device.getBondState() == BluetoothDevice.BOND_BONDED) {
                // TV já conhecida reconectou (nem sempre ela reescreve o CCCD)
                host = device;
                stopAdvertising();
                main.post(() -> listener.onBleConnected(device));
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED && device.equals(host)) {
                host = null;
                synchronized (queue) { queue.clear(); sending = false; }
                main.post(() -> listener.onBleDisconnected(device));
                if (wantAdvertise) main.postDelayed(BleKeyboard.this::startAdvertising, 500);
            }
        }

        @Override public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                          BluetoothGattCharacteristic c) {
            byte[] v = values.get(c.getUuid().toString());
            if (CH_REPORT_MAP.equals(c.getUuid())) log("TV leu o mapa do teclado (offset " + offset + ")");
            respond(device, requestId, offset, v);
        }

        @Override public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
                                                      BluetoothGattDescriptor d) {
            byte[] v;
            if (DS_CCCD.equals(d.getUuid())) {
                v = cccd.get(device.getAddress() + d.getCharacteristic().getUuid());
                if (v == null) v = new byte[]{0, 0};
            } else {
                v = values.get(key(d.getCharacteristic().getUuid(), d.getUuid()));
            }
            respond(device, requestId, offset, v);
        }

        @Override public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                           BluetoothGattCharacteristic c, boolean prepared,
                                                           boolean responseNeeded, int offset, byte[] value) {
            if (value != null) values.put(c.getUuid().toString(), value);
            if (responseNeeded) server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
        }

        @Override public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                                       BluetoothGattDescriptor d, boolean prepared,
                                                       boolean responseNeeded, int offset, byte[] value) {
            if (DS_CCCD.equals(d.getUuid())) {
                UUID chUuid = d.getCharacteristic().getUuid();
                cccd.put(device.getAddress() + chUuid, value);
                boolean on = value != null && value.length > 0 && (value[0] & 0x01) != 0;
                if (CH_REPORT.equals(chUuid)) {
                    log("TV " + (on ? "ativou" : "desativou") + " as teclas");
                    if (on) {
                        rememberHost(device);
                        host = device;
                        stopAdvertising();
                        main.post(() -> listener.onBleConnected(device));
                    } else if (device.equals(host)) {
                        host = null;
                        main.post(() -> listener.onBleDisconnected(device));
                    }
                }
            }
            if (responseNeeded) server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
        }

        @Override public void onNotificationSent(BluetoothDevice device, int status) {
            synchronized (queue) { sending = false; }
            pump();
        }
    };

    private void respond(BluetoothDevice device, int requestId, int offset, byte[] v) {
        if (v == null) v = new byte[0];
        if (offset > v.length) {
            server.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
            return;
        }
        server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                Arrays.copyOfRange(v, offset, v.length));
    }

    private android.content.SharedPreferences prefs() {
        return context.getSharedPreferences("ble_hosts", Context.MODE_PRIVATE);
    }

    private void rememberHost(BluetoothDevice d) {
        prefs().edit().putBoolean(d.getAddress(), true).apply();
    }

    private boolean isKnownHost(BluetoothDevice d) {
        return prefs().getBoolean(d.getAddress(), false);
    }

    // ------------------------------------------------------------ Teclas

    public synchronized void keyDown(int usage) {
        if (!held.contains(usage)) {
            if (held.size() >= 6) held.remove(0);
            held.add(usage);
        }
        enqueue();
    }

    public synchronized void keyUp(int usage) {
        held.remove(Integer.valueOf(usage));
        enqueue();
    }

    public synchronized void releaseAll() {
        if (held.isEmpty()) return;
        held.clear();
        enqueue();
    }

    private void enqueue() {
        if (host == null) return;
        byte[] r = new byte[8];
        for (int i = 0; i < held.size(); i++) r[2 + i] = (byte) (int) held.get(i);
        synchronized (queue) { queue.add(r); }
        pump();
    }

    @SuppressWarnings("deprecation")
    private void pump() {
        byte[] r;
        BluetoothDevice dev = host;
        synchronized (queue) {
            if (sending || queue.isEmpty() || dev == null || server == null) return;
            r = queue.poll();
            sending = true;
        }
        boolean ok;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ok = server.notifyCharacteristicChanged(dev, inputReport, false, r) == BluetoothGatt.GATT_SUCCESS;
            } else {
                inputReport.setValue(r);
                ok = server.notifyCharacteristicChanged(dev, inputReport, false);
            }
        } catch (Exception e) {
            ok = false;
        }
        values.put(CH_REPORT.toString(), r);
        if (!ok) {
            synchronized (queue) { sending = false; queue.addFirst(r); }
            main.postDelayed(this::pump, 20);
        }
    }

    public void stop() {
        wantAdvertise = false;
        stopAdvertising();
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        server = null;
        host = null;
        servicesReady = false;
    }
}
