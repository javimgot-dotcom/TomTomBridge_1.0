package com.tomtombridge.diagnostic;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.text.InputType;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.LinkedHashMap;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

/**
 * TomTom Bridge Personal v1.0: monitored, CRC-verified read-only file transfer.
 * Only PIN, notification descriptors, LIST (03) and READ (01) can be written.
 * NEVER issue DELETE (04) or WRITE FILE (00) protocol commands.
 * TTBIN stays private on device; only derived TCX is sent to Strava after linking.
 */
public final class MainActivity extends Activity {
    private static final int PERMISSIONS_REQUEST = 101;
    private static final int EXPORT_REQUEST = 102;
    private static final int EXPORT_ACTIVITY_REQUEST = 104;
    private static final int IMPORT_TTBIN_REQUEST = 105;
    private static final int EXPORT_TCX_REQUEST = 106;
    private static final int ENABLE_BT_REQUEST = 103;
    private static final UUID AUTH_PASS = UUID.fromString("b993bf92-81e1-11e4-b4a9-0800200c9a66");
    private static final UUID AUTH_MAGIC = UUID.fromString("b993bf93-81e1-11e4-b4a9-0800200c9a66");
    private static final UUID CMD = UUID.fromString("170d0d31-4213-11e3-aa6e-0800200c9a66");
    private static final UUID LENGTH = UUID.fromString("170d0d32-4213-11e3-aa6e-0800200c9a66");
    private static final UUID TRANSFER = UUID.fromString("170d0d33-4213-11e3-aa6e-0800200c9a66");
    private static final UUID CHECK = UUID.fromString("170d0d34-4213-11e3-aa6e-0800200c9a66");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final byte[] MAGIC = {1, 0x13, 0, 0, 1, 0x1f, 0, 0};
    private final Queue<Runnable> gattOps = new ArrayDeque<>();
    private boolean opBusy = false;
    private BluetoothGattCharacteristic authPass, authMagic, command, lengthChar, transferChar, checkChar;
    private EditText pinField;
    private Button authButton, listButton, downloadButton;
    private Button stravaButton;
    private StravaSync stravaSync;
    private final Queue<Integer> downloadQueue = new ArrayDeque<>();
    private boolean batchActive = false;
    private int totalQueued = 0, finishedQueued = 0;
    private boolean listForBatch = false;
    private boolean authOk = false, listActive = false, listAccepted = false, downloadActive = false;
    private final ByteArrayOutputStream listBytes = new ByteArrayOutputStream();
    private final Map<Integer, String> activityFiles = new LinkedHashMap<>();
    private int selectedFileId = -1, expectedLength = -1, acknowledgedBlocks = 0;
    private final ByteArrayOutputStream downloadBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream blockBytes = new ByteArrayOutputStream();
    private boolean waitingForReadFinish = false;
    private boolean readAccepted = false;
    private int transferPacketCount = 0;
    private long downloadStartMs = 0;
    private String lastSavedName = null;
    private Button exportActivityButton;
    private TextView transferProgress;
    private LinearLayout historyContainer;
    private String selectedTcxName = null;
    private String sessionPin = null;
    private boolean authRequested = false;
    private long lastOperation = 0;
    private static final int MAX_DOWNLOAD = 20 * 1024 * 1024;
    private String selectedAddress = "";

    private static final UUID DEVICE_INFO = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb");
    private static final UUID TOMTOM_FILE = UUID.fromString("b993bf90-81e1-11e4-b4a9-0800200c9a66");
    private static final UUID TOMTOM_AUTH = UUID.fromString("b993bf91-81e1-11e4-b4a9-0800200c9a66");
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder report = new StringBuilder();
    private final Set<String> discoveredAddresses = new HashSet<>();
    private final Queue<BluetoothGattCharacteristic> pendingReads = new ArrayDeque<>();
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private boolean scanning = false;
    private TextView status, logText;
    private LinearLayout devices;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        stravaSync = new StravaSync(this, this::addLog, this::refreshHistoryOnUi);
        buildUi();
        if(getIntent()!=null && getIntent().getData()!=null)stravaSync.handleCallback(getIntent().getData());
        addLog("Versión 1.0 Personal: sincronización múltiple, UTC corregido, historial y Strava directo sin servidores.");
        addLog("Modelo esperado: TomTom Multi-Sport Cardio (por verificar mediante BLE).");
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        root.setBackgroundColor(bgColor());
        scroll.addView(root);
        TextView title = new TextView(this);
        title.setText("TomTom Bridge"); title.setTextSize(31); title.setTextColor(fgColor());
        title.setTypeface(null, 1); root.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("Edición personal  ·  Tu reloj, tu historial, tu Strava.");
        subtitle.setTextSize(14); subtitle.setTextColor(mutedColor()); root.addView(subtitle);
        status = new TextView(this); status.setText("●  Reloj sin conectar"); status.setTextSize(16); status.setTextColor(fgColor());
        status.setPadding(0, dp(16), 0, dp(12)); root.addView(status);
        Button scan = button("Buscar y conectar reloj"); root.addView(scan);
        scan.setOnClickListener(v -> checkAndScan());
        TextView hint = new TextView(this);
        hint.setText("En el reloj, abre PHONE → PAIR NEW o PHONE → SYNC. Activa Bluetooth en el móvil. Al encontrar el reloj, pulsa su nombre.");
        hint.setTextColor(mutedColor()); hint.setTextSize(13); hint.setPadding(0, dp(8), 0, dp(16)); root.addView(hint);
        devices = new LinearLayout(this); devices.setOrientation(LinearLayout.VERTICAL); root.addView(devices);
        TextView stage = new TextView(this);
        stage.setText("SINCRONIZACIÓN DEL RELOJ"); stage.setTextColor(fgColor()); stage.setTypeface(null, 1);
        stage.setPadding(0, dp(20), 0, dp(6)); root.addView(stage);
        TextView safety = new TextView(this);
        safety.setText("Solo se enviarán mensajes BLE de autenticación y comandos de lectura. No se borra ni se actualiza firmware/QuickGPS. Recomendamos probar primero con una actividad que ya tengas guardada.");
        safety.setTextColor(mutedColor()); root.addView(safety);
        pinField = new EditText(this); pinField.setHint("PIN de 6 cifras que muestra el reloj");
        pinField.setInputType(InputType.TYPE_CLASS_NUMBER);
        pinField.setSingleLine(true); pinField.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
        root.addView(pinField);
        authButton = button("Autorizar reloj (solo si es necesario)"); root.addView(authButton);
        authButton.setEnabled(false); authButton.setOnClickListener(v -> authenticate());
        listButton = button("Consultar actividades del reloj"); root.addView(listButton);
        listButton.setEnabled(false); listButton.setOnClickListener(v -> listActivities());
        downloadButton = button("Sincronizar todas las actividades nuevas"); root.addView(downloadButton);
        downloadButton.setEnabled(false); downloadButton.setOnClickListener(v -> syncAll());
        downloadButton.setTextColor(Color.WHITE);
        GradientDrawable syncBackground=new GradientDrawable();syncBackground.setColor(Color.rgb(25,113,197));syncBackground.setCornerRadius(dp(15));
        downloadButton.setBackground(syncBackground);downloadButton.setTextSize(16);
        transferProgress = new TextView(this); transferProgress.setText("Transferencia: sin iniciar");
        transferProgress.setPadding(0, dp(8), 0, dp(8)); root.addView(transferProgress);
        exportActivityButton = button("Exportar último TTBIN original...");
        exportActivityButton.setEnabled(false); root.addView(exportActivityButton);
        exportActivityButton.setOnClickListener(v -> exportActivity());
        TextView historyTitle = new TextView(this);
        historyTitle.setText("MI HISTORIAL"); historyTitle.setTextColor(fgColor());
        historyTitle.setTextSize(17); historyTitle.setTypeface(null, 1);
        historyTitle.setPadding(0, dp(24), 0, dp(8)); root.addView(historyTitle);
        TextView historyHelp = new TextView(this);
        historyHelp.setText("Tus actividades se conservan en este móvil. Los archivos del reloj no se borran."); historyHelp.setTextColor(mutedColor());
        historyHelp.setTextSize(13); root.addView(historyHelp);
        Button importBtn = button("Importar entrenamiento TTBIN..."); root.addView(importBtn);
        importBtn.setOnClickListener(v -> importTtbin());
        Button refreshBtn = button("Actualizar historial"); root.addView(refreshBtn);
        refreshBtn.setOnClickListener(v -> refreshHistory());
        historyContainer = new LinearLayout(this);
        historyContainer.setOrientation(LinearLayout.VERTICAL); root.addView(historyContainer);
        TextView stravaTitle = new TextView(this); stravaTitle.setText("STRAVA"); stravaTitle.setTypeface(null,1); stravaTitle.setTextColor(fgColor()); stravaTitle.setTextSize(18); stravaTitle.setPadding(0,dp(20),0,dp(8)); root.addView(stravaTitle);
        TextView stravaHelp = new TextView(this); stravaHelp.setText("Uso personal: configura Client ID y Client Secret en el móvil, autoriza una vez y las actividades válidas se subirán al pulsar Sincronizar. Sin servidor intermedio."); stravaHelp.setTextColor(mutedColor()); root.addView(stravaHelp);
        stravaButton = button("Vincular Strava"); root.addView(stravaButton); stravaButton.setOnClickListener(v -> stravaSync.connectOrDisconnect());
        Button retryUploads = button("Reintentar subidas pendientes"); root.addView(retryUploads); retryUploads.setOnClickListener(v -> stravaSync.enqueueAll(activityFolder()));
        stravaSync.updateButton(stravaButton);
        Button disconnect = button("Desconectar reloj"); root.addView(disconnect);
        disconnect.setOnClickListener(v -> { stopScan(); closeGatt(); setStatus("Desconectado"); addLog("Desconexión solicitada."); });
        Button export = button("Exportar informe completo (.txt)"); root.addView(export);
        export.setOnClickListener(v -> exportReport());
        TextView warning = new TextView(this);
        warning.setText("No incluye firmware ni QuickGPS. La subida automática a Strava solo se activará después de autorizar la cuenta en el móvil. Los datos de cinta son estimaciones del reloj.");
        warning.setTextColor(Color.rgb(115, 70, 10)); warning.setPadding(0, dp(12), 0, dp(12)); root.addView(warning);
        Button debugToggle = button("Mostrar diagnóstico avanzado");root.addView(debugToggle);
        logText = new TextView(this); logText.setTextSize(12); logText.setTextColor(mutedColor());
        logText.setTextIsSelectable(true); logText.setVisibility(View.GONE); root.addView(logText);
        debugToggle.setOnClickListener(v -> { boolean show=logText.getVisibility()!=View.VISIBLE;
            logText.setVisibility(show?View.VISIBLE:View.GONE);
            debugToggle.setText(show?"Ocultar diagnóstico avanzado":"Mostrar diagnóstico avanzado"); });
        setContentView(scroll);
        refreshHistory();
    }

    private Button button(String text) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextSize(14);
        b.setTextColor(fgColor()); b.setBackground(makeCard());
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(7); b.setLayoutParams(lp);
        b.setPadding(dp(10),dp(12),dp(10),dp(12));return b;
    }
    private int dp(int x) { return (int) (x * getResources().getDisplayMetrics().density + 0.5f); }
    private void setStatus(String message) { ui.post(() -> status.setText(message)); }
    private void addLog(String message) {
        ui.post(() -> {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String line = "[" + time + "] " + message + "\n";
            report.append(line);
            logText.setText(report.toString());
        });
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    private void checkAndScan() {
        if (adapter == null) { addLog("ERROR: este móvil no tiene adaptador Bluetooth."); return; }
        if (!hasPermissions()) {
            if (Build.VERSION.SDK_INT >= 31) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, PERMISSIONS_REQUEST);
            else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSIONS_REQUEST);
            return;
        }
        try {
            if (!adapter.isEnabled()) {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BT_REQUEST);
                return;
            }
        } catch (SecurityException e) { addLog("Sin permisos para consultar Bluetooth: " + e.getMessage()); return; }
        startScan();
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == PERMISSIONS_REQUEST) {
            if (hasPermissions()) checkAndScan();
            else addLog("Permisos Bluetooth denegados. Habilítalos en ajustes de Android.");
        }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ENABLE_BT_REQUEST && resultCode == RESULT_OK) checkAndScan();
        if (requestCode == IMPORT_TTBIN_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null)
            importSelectedFile(data.getData());
        if (requestCode == EXPORT_TCX_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null)
            writeTcxSelected(data.getData());
        if (requestCode == EXPORT_ACTIVITY_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            exportSavedFile(data.getData());
        }
        if (requestCode == EXPORT_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try (java.io.OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out != null) { out.write(report.toString().getBytes(StandardCharsets.UTF_8)); addLog("Informe exportado correctamente."); }
            } catch (Exception e) { addLog("ERROR exportando informe: " + e.getMessage()); }
        }
    }
    private void startScan() {
        if (!hasPermissions()) return;
        stopScan();
        discoveredAddresses.clear(); devices.removeAllViews();
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) { addLog("Escáner BLE no disponible."); return; }
            scanner.startScan(scanCallback); scanning = true;
            setStatus("Buscando durante 15 segundos..."); addLog("Iniciando escaneo BLE (15 s).");
            ui.postDelayed(this::stopScan, 15000);
        } catch (SecurityException | IllegalStateException e) { addLog("ERROR iniciando escaneo: " + e.getMessage()); }
    }
    private void stopScan() {
        if (!scanning) return;
        try { if (scanner != null && hasPermissions()) scanner.stopScan(scanCallback); }
        catch (Exception e) { addLog("Error al detener escaneo: " + e.getMessage()); }
        scanning = false; addLog("Escaneo finalizado.");
        if (gatt == null) setStatus("Búsqueda finalizada. Selecciona un dispositivo.");
    }
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) { processScan(result); }
        @Override public void onBatchScanResults(List<ScanResult> results) { for (ScanResult r : results) processScan(r); }
        @Override public void onScanFailed(int errorCode) { addLog("Error BLE scan code=" + errorCode); setStatus("Error escaneando"); }
    };
    private void processScan(ScanResult r) {
        BluetoothDevice d = r.getDevice(); if (d == null) return;
        String address = d.getAddress();
        if (!discoveredAddresses.add(address)) return;
        String name = null;
        try { if (hasPermissions()) name = d.getName(); } catch (SecurityException ignored) { }
        if ((name == null || name.isEmpty()) && r.getScanRecord() != null) name = r.getScanRecord().getDeviceName();
        final String label = (name == null || name.isEmpty()) ? "Dispositivo sin nombre" : name;
        if (!label.toLowerCase(Locale.ROOT).contains("tomtom") && !address.toUpperCase(Locale.ROOT).startsWith("E4:04:39")) return;
        addLog("Detectado: " + label + " / " + address + " / RSSI " + r.getRssi() + " dBm");
        ui.post(() -> {
            Button b = button(label + "  [" + address + "]");
            b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            devices.addView(b);
            b.setOnClickListener(v -> connect(d, label));
        });
    }
    private void connect(BluetoothDevice device, String label) {
        if (!hasPermissions()) { addLog("No hay permisos de conexión."); return; }
        stopScan(); closeGatt(); selectedAddress = device.getAddress();
        try {
            addLog("Intentando conectar con " + label + ".");
            setStatus("Conectando con " + label + "...");
            gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException | IllegalStateException e) { addLog("ERROR conectando: " + e.getMessage()); }
    }
    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt current, int statusCode, int newState) {
            addLog("GATT estado=" + newState + ", código=" + statusCode);
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                setStatus("Fallo de conexión (" + statusCode + ")");
                if (downloadActive) failDownload("Conexión interrumpida, código GATT=" + statusCode);
                resetProtocol(); return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setStatus("Conectado. Descubriendo servicios...");
                try { if (hasPermissions()) current.discoverServices(); }
                catch (SecurityException e) { addLog("Permiso GATT denegado."); }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (downloadActive) failDownload("Desconexión BLE durante la transferencia");
                resetProtocol(); setStatus("Reloj desconectado");
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt current, int statusCode) {
            addLog("Servicios GATT descubiertos, código=" + statusCode);
            if (statusCode != BluetoothGatt.GATT_SUCCESS) return;
            boolean fileFound = false, authFound = false;
            pendingReads.clear();
            for (BluetoothGattService service : current.getServices()) {
                UUID id = service.getUuid();
                addLog("SERVICIO " + id);
                if (id.equals(TOMTOM_FILE)) fileFound = true;
                if (id.equals(TOMTOM_AUTH)) authFound = true;
                for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                    addLog("   CARACTERÍSTICA " + c.getUuid() + " propiedades=0x" + Integer.toHexString(c.getProperties()));
                    if (id.equals(DEVICE_INFO) && (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0) pendingReads.add(c);
                }
            }
            BluetoothGattService fs = current.getService(TOMTOM_FILE);
            BluetoothGattService as = current.getService(TOMTOM_AUTH);
            command = fs == null ? null : fs.getCharacteristic(CMD);
            lengthChar = fs == null ? null : fs.getCharacteristic(LENGTH);
            transferChar = fs == null ? null : fs.getCharacteristic(TRANSFER);
            checkChar = fs == null ? null : fs.getCharacteristic(CHECK);
            authPass = as == null ? null : as.getCharacteristic(AUTH_PASS);
            authMagic = as == null ? null : as.getCharacteristic(AUTH_MAGIC);
            addLog("PROTOCOLO TOMTOM: transferencia=" + fileFound + ", autenticación=" + authFound);
            final boolean ready = fileFound && authFound;
            ui.post(() -> authButton.setEnabled(ready));
            if (fileFound && authFound) enableNotifications(current);
            setStatus(fileFound ? "Protocolo TomTom detectado ✓" : "Conexión BLE establecida; protocolo TomTom no detectado");
            readNext(current);
        }
        @Override public void onDescriptorWrite(BluetoothGatt current, BluetoothGattDescriptor descriptor, int statusCode) {
            addLog("CCCD " + descriptor.getCharacteristic().getUuid() + " status=" + statusCode);
            nextGattOp();
        }
        @Override public void onCharacteristicWrite(BluetoothGatt current, BluetoothGattCharacteristic characteristic, int statusCode) {
            addLog("ESCRITURA CONTROL " + characteristic.getUuid() + " status=" + statusCode);
            if (statusCode != 0) { resetProtocol(); setStatus("Fallo en escritura de control: " + statusCode); }
            nextGattOp();
        }
        @Override public void onCharacteristicChanged(BluetoothGatt current, BluetoothGattCharacteristic characteristic) {
            handleNotify(characteristic.getUuid(), characteristic.getValue());
        }
        @Override public void onCharacteristicChanged(BluetoothGatt current, BluetoothGattCharacteristic characteristic, byte[] value) {
            handleNotify(characteristic.getUuid(), value);
        }
        @Override public void onCharacteristicRead(BluetoothGatt current, BluetoothGattCharacteristic characteristic, int statusCode) {
            handleRead(current, characteristic, characteristic.getValue(), statusCode);
        }
        @Override public void onCharacteristicRead(BluetoothGatt current, BluetoothGattCharacteristic characteristic, byte[] value, int statusCode) {
            handleRead(current, characteristic, value, statusCode);
        }
    };
    private void handleRead(BluetoothGatt current, BluetoothGattCharacteristic c, byte[] bytes, int statusCode) {
        String key = c.getUuid().toString().substring(4, 8);
        String raw = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8).replace("\u0000", "").trim();
        if (key.equals("2a25")) raw = "[número de serie oculto en pantalla]";
        addLog("LECTURA " + key + " status=" + statusCode + " valor=" + raw);
        nextGattOp();
        readNext(current);
    }
    private void readNext(BluetoothGatt current) {
        BluetoothGattCharacteristic c = pendingReads.poll();
        if (c == null) { addLog("Diagnóstico completado. Exporta el informe."); return; }
        queueGattOp(() -> {
            try {
                if (!hasPermissions() || !current.readCharacteristic(c)) {
                    addLog("No se pudo iniciar lectura " + c.getUuid()); nextGattOp(); readNext(current);
                }
            } catch (SecurityException | IllegalStateException e) {
                addLog("Error leyendo característica: " + e.getMessage()); nextGattOp(); readNext(current);
            }
        });
    }
    private void exportReport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain"); intent.putExtra(Intent.EXTRA_TITLE, "tomtom_diagnostico.txt");
        startActivityForResult(intent, EXPORT_REQUEST);
    }
    private void closeGatt() {
        pendingReads.clear(); resetProtocol();
        if (gatt != null) {
            try { if (hasPermissions()) gatt.disconnect(); } catch (Exception ignored) { }
            gatt.close(); gatt = null;
        }
    }
    // ---- Phase 2 protocol engine. Commands are explicitly limited to LIST and READ. ----
    private void resetProtocol() {
        ui.post(() -> { authButton.setEnabled(false); listButton.setEnabled(false); downloadButton.setEnabled(false); });
        synchronized (gattOps) { gattOps.clear(); opBusy = false; }
        authOk = false; listActive = false; downloadActive = false; waitingForReadFinish = false;
        batchActive=false;listForBatch=false;downloadQueue.clear();
        readAccepted = false; authRequested = false;
    }
    private void queueGattOp(Runnable op) {
        boolean start;
        synchronized (gattOps) { gattOps.add(op); start = !opBusy; }
        if (start) nextGattOp();
    }
    private void nextGattOp() {
        Runnable next;
        synchronized (gattOps) {
            opBusy = false;
            next = gattOps.poll();
            if (next == null) return;
            opBusy = true;
        }
        ui.post(next);
    }
    private void enableNotifications(BluetoothGatt g) {
        for (BluetoothGattCharacteristic c : new BluetoothGattCharacteristic[]{authPass, command, lengthChar, transferChar, checkChar}) {
            if (c == null) continue;
            BluetoothGattDescriptor desc = c.getDescriptor(CCCD);
            if (desc == null) { addLog("Aviso: CCCD ausente en " + c.getUuid()); continue; }
            queueGattOp(() -> {
                try {
                    if (!hasPermissions() || !g.setCharacteristicNotification(c, true)) { addLog("Falló setNotification " + c.getUuid()); nextGattOp(); return; }
                    desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    if (!g.writeDescriptor(desc)) { addLog("Falló writeDescriptor " + c.getUuid()); nextGattOp(); }
                } catch (Exception e) { addLog("Error CCCD " + e.getMessage()); nextGattOp(); }
            });
        }
    }
    private void send(BluetoothGattCharacteristic c, byte[] bytes) {
        if (c == null || gatt == null) { addLog("Característica no disponible"); return; }
        // This guard prevents accidental protocol delete/write-to-file commands.
        if (c.getUuid().equals(CMD) && (bytes.length != 4 || (bytes[0] != 3 && bytes[0] != 1))) {
            addLog("BLOQUEADO comando no permitido (solo LIST/READ)"); return;
        }
        queueGattOp(() -> {
            try {
                if (!hasPermissions() || gatt == null) { nextGattOp(); return; }
                c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                c.setValue(bytes);
                if (!gatt.writeCharacteristic(c)) { addLog("Error al iniciar escritura " + c.getUuid()); nextGattOp(); }
            } catch (Exception e) { addLog("Error envío: " + e.getMessage()); nextGattOp(); }
        });
    }
    private void authenticate() {
        if (gatt == null || authPass == null || authMagic == null) { addLog("Primero conecta el reloj"); return; }
        String value = pinField.getText().toString().trim();
        if (!value.matches("[0-9]{6}")) { Toast.makeText(this, "Introduce exactamente 6 cifras", Toast.LENGTH_SHORT).show(); return; }
        authOk = false; authButton.setEnabled(false);
        authRequested = true;
        int code = Integer.parseInt(value);
        byte[] pinLE = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(code).array();
        addLog("Autenticación de sesión: el PIN no se incluye en los informes.");
        send(authMagic, MAGIC);
        send(authPass, pinLE);
        addLog("Esperando confirmación del reloj (1=aceptado). Si no llega, puede requerir handles ocultos.");
        ui.postDelayed(() -> {
            if (!authOk && gatt != null) {
                authButton.setEnabled(true);
                addLog("Sin confirmación de PIN. No se realizará lectura de archivos.");
            }
        }, 12000);
    }
    private void listActivities() {
        if (!authOk || command == null || listActive || downloadActive) { addLog("No autorizado o transferencia ocupada."); return; }
        listActive = true; listAccepted = false; listBytes.reset(); activityFiles.clear();
        ui.post(() -> { listButton.setEnabled(false); downloadButton.setEnabled(false); });
        addLog("Consultando archivos de actividad (grupo 0x91), solo lectura.");
        send(command, new byte[]{3, (byte)0x91, 0, 0});
        ui.postDelayed(() -> { if (listActive) { listActive = false; listForBatch=false;batchActive=false;addLog("Timeout al listar actividades."); ui.post(() -> {listButton.setEnabled(authOk);downloadButton.setEnabled(authOk);}); } }, 30000);
    }
    private void syncAll() {
        if(!authOk || downloadActive || listActive || batchActive) {addLog("Sincronización ocupada o reloj no autorizado.");return;}
        batchActive=true; finishedQueued=0; totalQueued=0;
        listForBatch=true;listActivities();
    }
    private void startBatchFromList() {
        downloadQueue.clear();
        for(int id:activityFiles.keySet()) {
            File local=new File(activityFolder(),String.format(Locale.US,"%08X.ttbin",id));
            boolean valid=false;
            if(local.isFile())try{
                byte[] stored=java.nio.file.Files.readAllBytes(local.toPath());
                File proof=new File(activityFolder(),local.getName()+".sha256");
                if(proof.isFile()){
                    String expected=new String(java.nio.file.Files.readAllBytes(proof.toPath()),StandardCharsets.UTF_8).trim();
                    valid=expected.equalsIgnoreCase(sha256(stored));
                }else{TtbinParser.parse(stored);valid=true;}
            }catch(Exception e){addLog("Copia local sin prueba de integridad de "+local.getName()+", se repetirá descarga.");}
            if(!valid)downloadQueue.add(id);
        }
        totalQueued=downloadQueue.size();
        addLog("Sincronizar: "+activityFiles.size()+" en reloj, "+totalQueued+" nuevas pendientes.");
        nextDownload();
    }
    private void nextDownload() {
        if(!batchActive)return;
        if(downloadActive)return;
        Integer next=downloadQueue.poll();
        if(next==null){batchActive=false;setProgress("Sincronización finalizada: "+finishedQueued+" descargas nuevas.");addLog("SINCRONIZACIÓN FINALIZADA, sin borrar actividades.");
            stravaSync.enqueueAll(activityFolder());
            ui.post(()->{listButton.setEnabled(authOk);downloadButton.setEnabled(authOk && !activityFiles.isEmpty());});return;}
        setProgress("Actividad "+(finishedQueued+1)+" de "+totalQueued+" · iniciando...");
        downloadOne(next);
    }
    private void downloadOne(int fileId) {
        if (!authOk || downloadActive || listActive) return;
        selectedFileId = fileId;
        downloadActive = true; expectedLength = -1; waitingForReadFinish = false;
        downloadBytes.reset(); blockBytes.reset(); acknowledgedBlocks = 0;
        transferPacketCount = 0; readAccepted = false; downloadStartMs = android.os.SystemClock.elapsedRealtime();
        ui.post(() -> transferProgress.setText("Descarga solicitada, esperando primer paquete..."));
        int index = selectedFileId & 0xffff;
        byte[] request = new byte[]{1, (byte) 0x91, (byte)((index >> 8) & 255), (byte)(index & 255)};
        // File number encoding per ttblue: 0x00910001 -> 91 01 00.
        request[2] = (byte) (index & 255); request[3] = (byte)((index >> 8) & 255);
        addLog(String.format(Locale.US, "Solicitando lectura 0x%08X (sin borrar).", selectedFileId));
        ui.post(() -> { downloadButton.setEnabled(false); listButton.setEnabled(false); });
        send(command, request);
        ui.postDelayed(() -> {
            if (downloadActive && android.os.SystemClock.elapsedRealtime() - downloadStartMs >= 180000)
                failDownload("Timeout global; paquetes=" + transferPacketCount
                        + ", bytes verificados=" + downloadBytes.size() + ", bloque bruto=" + blockBytes.size());
        }, 180000);
    }
    private void handleNotify(UUID uuid, byte[] data) {
        if (data == null) return;
        if (downloadActive && (uuid.equals(CMD) || uuid.equals(LENGTH) || uuid.equals(CHECK)))
            addLog("NOTIFY " + uuid.toString().substring(0, 8) + " len=" + data.length + " hex=" + hex(data));
        if (uuid.equals(AUTH_PASS)) {
            addLog("Respuesta autorización: " + hex(data));
            if (data.length > 0 && data[0] == 1) {
                authOk = true; setStatus("PIN aceptado (sesión experimental)");
                ui.post(() -> { authButton.setEnabled(true); listButton.setEnabled(true); downloadButton.setEnabled(true); });
                if (authRequested) {
                    sessionPin = pinField.getText().toString().trim();
                    authRequested = false;
                }
                addLog("Autenticación confirmada por el reloj. Puedes consultar actividades.");
            } else { authOk = false; ui.post(() -> authButton.setEnabled(true)); addLog("PIN no aceptado."); }
        } else if (uuid.equals(CMD)) {
            addLog("Estado comando: " + hex(data));
            boolean accepted = data.length > 0 && data[0] == 1;
            boolean ended = data.length >= 4 && allZero(data);
            if (listActive) {
                if (!listAccepted) { listAccepted = accepted; if (!accepted) finishList(false); }
                else if (ended) finishList(true);
            } else if (downloadActive) {
                if (accepted && !readAccepted) { readAccepted = true; addLog("READ aceptado, esperando LENGTH y TRANSFER."); }
                else if (ended) {
                    waitingForReadFinish = true;
                    if (expectedLength >= 0 && downloadBytes.size() == expectedLength && blockBytes.size() == 0) saveDownload();
                    else failDownload("Fin inesperado: verificados=" + downloadBytes.size()
                            + " / " + expectedLength + ", bloque bruto=" + blockBytes.size());
                } else if (!accepted && !readAccepted) failDownload("Reloj rechazó READ: " + hex(data));
            }
        } else if (uuid.equals(TRANSFER)) {
            if (listActive) { listBytes.write(data, 0, data.length); }
            else if (downloadActive) {
                transferPacketCount++;
                if (transferPacketCount <= 4 || transferPacketCount % 64 == 0)
                    addLog("TRANSFER paquete=" + transferPacketCount + " len=" + data.length
                            + " datos=" + hex(Arrays.copyOf(data, Math.min(data.length, 20))));
                if (expectedLength >= 0) onDownloadPacket(data);
                else addLog("AVISO: TRANSFER llegó antes que LENGTH (" + data.length + " bytes)");
            }
        } else if (uuid.equals(LENGTH) && downloadActive) {
            if (data.length < 4) { failDownload("Longitud inválida"); return; }
            expectedLength = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt();
            addLog("Longitud de archivo anunciada=" + expectedLength + " bytes. Esperando paquetes TRANSFER.");
            if (expectedLength <= 0 || expectedLength > MAX_DOWNLOAD) failDownload("Tamaño fuera de límite seguro");
        } else if (uuid.equals(CHECK)) { addLog("CHECK remoto " + hex(data)); }
    }
    private void finishList(boolean success) {
        listActive = false;
        if (!success) { listForBatch=false;batchActive=false;addLog("Listado rechazado o incompleto"); ui.post(() -> {listButton.setEnabled(authOk);downloadButton.setEnabled(authOk);}); return; }
        byte[] b = listBytes.toByteArray();
        if (b.length < 2 || (b.length & 1) != 0) { batchActive=false;listForBatch=false;addLog("Listado no interpretable: " + hex(b)); ui.post(() -> {listButton.setEnabled(authOk);downloadButton.setEnabled(authOk);}); return; }
        int count = le16(b,0);
        if (count > 1000 || b.length < 2 + count * 2) { batchActive=false;listForBatch=false;addLog("Listado incompleto/inválido: " + hex(b)); ui.post(() -> {listButton.setEnabled(authOk);downloadButton.setEnabled(authOk);}); return; }
        for (int i=0; i<count; i++) {
            int suffix = le16(b, 2+i*2);
            int id = 0x00910000 | suffix;
            activityFiles.put(id, String.format(Locale.US,"%08X.ttbin",id));
        }
        addLog("Actividades encontradas: " + activityFiles.size());
        for (int id : activityFiles.keySet()) addLog(String.format(Locale.US,"   0x%08X",id));
        setStatus("Actividades en reloj: " + activityFiles.size());
        ui.post(() -> { listButton.setEnabled(authOk); downloadButton.setEnabled(!activityFiles.isEmpty()); });
        if (listForBatch) { listForBatch=false; startBatchFromList(); }
    }
    /** TomTom v1: 5,118 data bytes + 2 CRC bytes per block (or shorter EOF block).
     * CRC is over data and trailing CRC, yielding zero with modbus polynomial.
     * Packet boundaries are irrelevant: accumulate raw notify bytes until block boundary.
     * Source: dlenski/ttblue ttops.c tt_read_file; confirms acknowledgement sequence starts at 1.
     */
    private void onDownloadPacket(byte[] packet) {
        if (!downloadActive || expectedLength <= 0) return;
        int dataGoal = Math.min(5118, expectedLength - downloadBytes.size());
        if (dataGoal <= 0) { failDownload("Llegó TRANSFER tras completar todos los bytes"); return; }
        int rawGoal = dataGoal + 2;
        if (blockBytes.size() + packet.length > rawGoal) {
            failDownload("Bloque excedido: acumulados=" + blockBytes.size() + ", paquete=" + packet.length + ", esperado=" + rawGoal);
            return;
        }
        blockBytes.write(packet, 0, packet.length);
        if (blockBytes.size() != rawGoal) {
            if (transferPacketCount == 1 || transferPacketCount % 64 == 0)
                setProgress("Recibiendo bloque " + (acknowledgedBlocks + 1) + ": " + blockBytes.size() + "/" + rawGoal + " bytes");
            return;
        }
        byte[] blockWithCrc = blockBytes.toByteArray();
        int remainingCrc = crc16Modbus(blockWithCrc);
        if (remainingCrc != 0) {
            failDownload(String.format(Locale.US, "CRC16 inválido en bloque %d (resto=%04X)", acknowledgedBlocks + 1, remainingCrc));
            return;
        }
        downloadBytes.write(blockWithCrc, 0, dataGoal);
        blockBytes.reset();
        acknowledgedBlocks++;
        addLog("Bloque " + acknowledgedBlocks + " CRC correcto. " + downloadBytes.size() + "/" + expectedLength + " bytes.");
        setProgress("Verificados " + downloadBytes.size() + " / " + expectedLength + " bytes");
        // GATT writes queued behind notifications, as mandated by the BLE API.
        // ttblue uses ++counter: first acknowledgement is 01 00 00 00.
        send(checkChar, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(acknowledgedBlocks).array());
        if (downloadBytes.size() == expectedLength) {
            waitingForReadFinish = true;
            addLog("Datos y CRC completos; esperando estado final CMD antes de guardar.");
            ui.postDelayed(() -> {
                if (downloadActive && waitingForReadFinish && downloadBytes.size() == expectedLength)
                    addLog("Aviso: CMD final aún no recibido, no se guarda prematuramente.");
            }, 12000);
        }
    }
    private void setProgress(String value) { ui.post(() -> transferProgress.setText(value)); }
    private void saveDownload() {
        if (!downloadActive) return;
        downloadActive=false;
        File folder = new File(getFilesDir(), "activities");
        if (!folder.exists() && !folder.mkdirs()) { addLog("Error creando directorio interno"); return; }
        String name=String.format(Locale.US,"%08X.ttbin",selectedFileId);
        File file = new File(folder,name);
        byte[] complete = downloadBytes.toByteArray();
        if(file.exists()) {
            try {
                byte[] old=java.nio.file.Files.readAllBytes(file.toPath());
                if(!Arrays.equals(old,complete)) {
                    name = String.format(Locale.US,"%08X_%s.ttbin",selectedFileId,sha256(complete).substring(0,12));
                    file=new File(folder,name);
                }
            } catch(Exception e){addLog("No se pudo comparar archivo previo: " + e.getMessage());return;}
        }
        boolean newlyCreated = !file.exists();
        try (FileOutputStream out=new FileOutputStream(file)) {
            out.write(complete);
            // Proof of a completed CRC16-validated BLE transfer, even if the parser
            // cannot yet interpret this sport/TTBIN version (e.g. an unseen swim file).
            java.nio.file.Files.write(new File(folder,name+".sha256").toPath(),
                sha256(complete).getBytes(StandardCharsets.UTF_8));
            lastSavedName = name;
            ui.post(() -> { exportActivityButton.setEnabled(true); refreshHistory(); });
            setProgress("Descarga completa: " + file.length() + " bytes. Ya puedes exportar el TTBIN.");
            addLog("ARCHIVO GUARDADO: " + name + " (" + file.length() + " bytes). SHA-256=" + sha256(downloadBytes.toByteArray()));
            setStatus("Descarga completada: " + name);
            if(newlyCreated)stravaSync.enqueueFile(file);
        } catch (Exception e) { addLog("Error al guardar: " + e.getMessage()); }
        if(batchActive){finishedQueued++;ui.postDelayed(this::nextDownload,400);}
        else ui.post(() -> { listButton.setEnabled(authOk); downloadButton.setEnabled(!activityFiles.isEmpty()); });
    }
    private void failDownload(String error) {
        addLog("DESCARGA INTERRUMPIDA: " + error + ". Nada se ha borrado del reloj.");
        downloadActive=false; blockBytes.reset(); downloadBytes.reset();
        setProgress("Error: " + error);
        batchActive=false; downloadQueue.clear();
        ui.post(() -> {listButton.setEnabled(authOk);downloadButton.setEnabled(authOk && !activityFiles.isEmpty());});
    }
    private void exportActivity() {
        if (lastSavedName == null) { addLog("No existe un archivo TTBIN validado que exportar."); return; }
        File file = new File(new File(getFilesDir(), "activities"), lastSavedName);
        if (!file.isFile()) { addLog("Archivo interno no encontrado: " + lastSavedName); return; }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, lastSavedName);
        startActivityForResult(intent, EXPORT_ACTIVITY_REQUEST);
    }
    private void exportSavedFile(android.net.Uri location) {
        if (lastSavedName == null) return;
        File file = new File(new File(getFilesDir(), "activities"), lastSavedName);
        try (java.io.InputStream in = new java.io.FileInputStream(file);
             java.io.OutputStream out = getContentResolver().openOutputStream(location)) {
            if (out == null) throw new java.io.IOException("Destino de exportación no disponible");
            byte[] chunk = new byte[4096]; int n;
            while ((n = in.read(chunk)) >= 0) if (n > 0) out.write(chunk, 0, n);
            addLog("TTBIN exportado con el selector de documentos: " + lastSavedName + " (" + file.length() + " bytes)");
        } catch (Exception e) { addLog("Error exportando actividad: " + e.getMessage()); }
    }

    private File activityFolder() {
        File folder = new File(getFilesDir(), "activities");
        if (!folder.exists()) folder.mkdirs();
        return folder;
    }
    private void refreshHistory() {
        if (historyContainer == null) return;
        historyContainer.removeAllViews();
        File[] files=activityFolder().listFiles((dir,name) -> name.toLowerCase(Locale.ROOT).endsWith(".ttbin"));
        if(files==null||files.length==0) {
            TextView empty=new TextView(this);
            empty.setText("Todavía no hay actividades guardadas. Puedes descargar o importar un TTBIN.");
            empty.setPadding(0,dp(12),0,dp(12));historyContainer.addView(empty);return;
        }
        Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
        for(File file:files) {
            final TtbinParser.Activity a;
            try { a=TtbinParser.parse(java.nio.file.Files.readAllBytes(file.toPath())); }
            catch(Exception e) {
                TextView err=new TextView(this);
                err.setText(file.getName()+" · No interpretable: "+e.getMessage());
                err.setPadding(0,dp(8),0,dp(8));historyContainer.addView(err);
                continue;
            }
            LinearLayout item=new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setBackground(makeCard());
            item.setPadding(dp(12),dp(12),dp(12),dp(12));
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
            params.bottomMargin=dp(10);
            historyContainer.addView(item,params);
            TextView heading=new TextView(this);
            heading.setText(a.sport+" · "+TtbinParser.localDate(a));
            heading.setTextColor(fgColor());heading.setTypeface(null,1);heading.setTextSize(16);
            item.addView(heading);
            TextView detail=new TextView(this);
            detail.setText(String.format(Locale.getDefault(),
                "Duración: %d:%02d · Distancia: %.1f m (estimada) · Calorías: %d\nRegistros: %d · FC válidas: %d · GPS: %s",
                a.durationSeconds/60,a.durationSeconds%60,a.distanceMeters,a.calories,a.recordCount,a.validHeartSamples,a.hasGps?"presente":"no"));
            detail.setTextColor(mutedColor()); detail.setTextSize(13);detail.setPadding(0,dp(5),0,dp(6));item.addView(detail);
            if(a.validHeartSamples>=2) {
                TextView chartTitle=new TextView(this);chartTitle.setText("FRECUENCIA CARDÍACA · muestras disponibles");chartTitle.setTextColor(mutedColor());chartTitle.setTextSize(11);item.addView(chartTitle);
                item.addView(new HeartChartView(this,a.points,dark()));
            }
            Button tcx=button("Exportar TCX · "+file.getName());item.addView(tcx);
            tcx.setOnClickListener(v -> exportTcxFor(file));
            TextView uploadStatus=new TextView(this);uploadStatus.setText(stravaSync.statusFor(file));uploadStatus.setTextColor(mutedColor());item.addView(uploadStatus);
            Button raw=button("Exportar original TTBIN");item.addView(raw);
            raw.setOnClickListener(v -> {lastSavedName=file.getName();exportActivity();});
        }
        addLog("Historial cargado: " + files.length + " archivos TTBIN locales.");
    }
    private void importTtbin() {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent,IMPORT_TTBIN_REQUEST);
    }
    private void importSelectedFile(android.net.Uri uri) {
        try(java.io.InputStream in=getContentResolver().openInputStream(uri)) {
            if(in==null)throw new java.io.IOException("No se puede abrir el fichero");
            ByteArrayOutputStream mem=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;
            while((n=in.read(buf))!=-1){if(mem.size()+n>MAX_DOWNLOAD)throw new java.io.IOException("TTBIN demasiado grande");mem.write(buf,0,n);}
            byte[] raw=mem.toByteArray();
            TtbinParser.Activity parsed=TtbinParser.parse(raw);
            // Content hash determines import identity, never overwrites a different local workout.
            String name="import_"+sha256(raw).substring(0,16)+".ttbin";
            File dest=new File(activityFolder(),name);
            if(!dest.exists())try(FileOutputStream out=new FileOutputStream(dest)){out.write(raw);}
            addLog("Importado: "+name+" ("+raw.length+" bytes), fecha="+TtbinParser.localDate(parsed));
            // Imported legacy files are not auto-posted without the explicit retry action.
            setStatus("Actividad importada correctamente");refreshHistory();
        }catch(Exception e){addLog("ERROR al importar TTBIN: "+e.getMessage());Toast.makeText(this,"TTBIN inválido o incompleto",Toast.LENGTH_LONG).show();}
    }
    private void exportTcxFor(File f) {
        selectedTcxName=f.getName();
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/xml");
        i.putExtra(Intent.EXTRA_TITLE,selectedTcxName.replaceAll("(?i)\\.ttbin$",".tcx"));
        startActivityForResult(i,EXPORT_TCX_REQUEST);
    }
    private void writeTcxSelected(android.net.Uri uri) {
        if(selectedTcxName==null)return;
        try {
            File f=new File(activityFolder(),selectedTcxName);
            TtbinParser.Activity a=TtbinParser.parse(java.nio.file.Files.readAllBytes(f.toPath()));
            try(java.io.OutputStream out=getContentResolver().openOutputStream(uri)){
                if(out==null)throw new java.io.IOException("Destino no accesible");
                out.write(TtbinParser.toTcx(a));
            }
            addLog("TCX exportado correctamente desde "+f.getName()+" ("+a.points.size()+" puntos sin coordenadas inventadas).");
            Toast.makeText(this,"TCX guardado. Ya puedes importarlo en Strava.",Toast.LENGTH_LONG).show();
        }catch(Exception e){addLog("ERROR exportando TCX: "+e.getMessage());}
        finally{selectedTcxName=null;}
    }

    private void refreshHistoryOnUi(){ui.post(()->{refreshHistory(); if(stravaButton!=null)stravaSync.updateButton(stravaButton);});}
    private boolean dark(){return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;}
    private int fgColor(){return dark()?Color.rgb(235,240,248):Color.rgb(25,35,52);}
    private int mutedColor(){return dark()?Color.rgb(175,185,202):Color.rgb(83,100,120);}
    private int bgColor(){return dark()?Color.rgb(15,21,32):Color.rgb(246,249,253);}
    private android.graphics.drawable.Drawable makeCard(){GradientDrawable g=new GradientDrawable();g.setColor(dark()?Color.rgb(35,44,61):Color.WHITE);g.setCornerRadius(dp(15));g.setStroke(dp(1),dark()?Color.rgb(65,77,95):Color.rgb(223,230,241));return g;}
    private static boolean allZero(byte[] b) { for(byte x:b) if(x!=0)return false; return true; }
    private static int le16(byte[] b,int off) {return (b[off]&255)|((b[off+1]&255)<<8);}
    private static int crc16Modbus(byte[] bytes) { int crc=0xffff; for(byte b:bytes) {crc ^= b & 255; for(int i=0;i<8;i++) crc = (crc & 1) != 0 ? (crc >>> 1)^0xA001 : crc >>> 1;} return crc & 0xffff; }
    private static String hex(byte[] bytes) { StringBuilder b=new StringBuilder(); for(byte x:bytes) b.append(String.format(Locale.US,"%02X ",x&255)); return b.toString().trim(); }
    private static String sha256(byte[] bytes) {
        try { byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes); return hex(digest).replace(" ",""); }
        catch(Exception e) {return "no-disponible";}
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);stravaSync.handleCallback(intent.getData());}
    @Override protected void onDestroy() { stopScan(); closeGatt(); super.onDestroy(); }
}
