package com.apramed.smartchartrc;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.apramed.smartchartrc.atv.AtvRemote;
import com.apramed.smartchartrc.atv.ClientCert;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements AtvRemote.Listener, TvFinder.Listener {

    private static final String PREFS = "rc";
    private static final String PREF_HOST = "tv_host";
    private static final String PREF_NAME = "tv_name";
    private static final long HOLD_MS = 400;

    private static final int C_OK = Color.parseColor("#2EAD5B");
    private static final int C_WAIT = Color.parseColor("#F0A202");
    private static final int C_OFF = Color.parseColor("#9AA5B1");
    private static final int C_ERR = Color.parseColor("#D64545");

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView statusText;
    private View statusDot;

    private AtvRemote remote;
    private TvFinder finder;
    private boolean started;           // activity visível
    private boolean allowPairing;      // pareamento só quando o usuário escolheu a TV
    private AlertDialog codeDialog;

    /** Botões de testes: imagem, legenda, tecla. */
    private static final Object[][] TESTS = {
            {R.drawable.btn_e,         "E",         KeyMap.E_SNELLEN},
            {R.drawable.btn_numeros,   "Números",   KeyMap.NUMEROS},
            {R.drawable.btn_letras,    "Letras",    KeyMap.LETRAS},
            {R.drawable.btn_c,         "C",         KeyMap.C_LANDOLT},
            {R.drawable.btn_piktotea,  "Piktotea",  KeyMap.PIKTOTEA},
            {R.drawable.btn_lea,       "LEA",       KeyMap.LEA},
            {R.drawable.btn_etdrs,     "ETDRS",     KeyMap.ETDRS},
            {R.drawable.btn_ishihara,  "Ishihara",  KeyMap.ISHIHARA},
            {R.drawable.btn_rg,        "Red/Green", KeyMap.RED_GREEN},
            {R.drawable.btn_lancaster, "Lancaster", KeyMap.LANCASTER},
            {R.drawable.btn_ph,        "Fixação",   KeyMap.PONTO_FIX},
            {R.drawable.btn_galeria,   "Galeria",   KeyMap.GALERIA},
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // Android 15/16 desenha atrás das barras do sistema: afasta o conteúdo delas.
        View root = findViewById(android.R.id.content);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        statusText = findViewById(R.id.statusText);
        statusDot = findViewById(R.id.statusDot);
        findViewById(R.id.statusBar).setOnClickListener(v -> showTvPicker());
        findViewById(R.id.statusBar).setOnLongClickListener(v -> { showLog(); return true; });

        buildGrid(findViewById(R.id.grid));
        buildDpad(findViewById(R.id.dpadContainer));

        finder = new TvFinder(this, this);
        setStatus("Preparando…", C_WAIT);
        // Gerar a chave RSA leva ~1 s na primeira vez: fora da thread de interface.
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                ClientCert cert = ClientCert.loadOrCreate(getFilesDir());
                main.post(() -> {
                    remote = new AtvRemote(cert, this);
                    if (started) connectSaved();
                });
            } catch (Exception e) {
                main.post(() -> setStatus("Erro ao criar certificado: " + e.getMessage(), C_ERR));
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (remote != null) connectSaved();
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        main.removeCallbacks(retry);
        finder.stop();
        if (remote != null) remote.disconnect();
    }

    // ================================================================ Conexão

    private String savedHost() { return prefs.getString(PREF_HOST, null); }

    private String savedName() { return prefs.getString(PREF_NAME, "TV"); }

    private void connectSaved() {
        String host = savedHost();
        if (host == null) {
            setStatus("Toque aqui para escolher a TV", C_OFF);
            return;
        }
        if (remote.isReady()) return;
        setStatus("Conectando a " + savedName() + "…", C_WAIT);
        remote.connect(host);
    }

    private void chooseTv(String name, String host) {
        prefs.edit().putString(PREF_HOST, host).putString(PREF_NAME, name).apply();
        log("TV escolhida: " + name + " (" + host + ")");
        allowPairing = true;
        main.removeCallbacks(retry);
        setStatus("Conectando a " + name + "…", C_WAIT);
        remote.connect(host);
    }

    private final Runnable retry = () -> { if (started && remote != null) connectSaved(); };

    @Override
    public void onConnected(String tvName) {
        main.post(() -> {
            allowPairing = false;
            setStatus("Conectado: " + savedName(), C_OK);
        });
    }

    @Override
    public void onDisconnected(boolean needsPairing, String reason) {
        main.post(() -> {
            if (!started) return;
            if (needsPairing) {
                if (allowPairing) {
                    setStatus("Pareando — veja o código na TV", C_WAIT);
                    remote.startPairing(savedHost());
                } else {
                    setStatus("Precisa parear — toque aqui", C_ERR);
                }
                return;
            }
            setStatus("Sem conexão com " + savedName() + " — tentando de novo…", C_WAIT);
            main.removeCallbacks(retry);
            main.postDelayed(retry, 3000);
        });
    }

    // ================================================================ Pareamento

    @Override
    public void onPairingCodeShown() {
        main.post(this::showCodeDialog);
    }

    @Override
    public void onPairingResult(boolean ok, String message) {
        main.post(() -> {
            if (ok) {
                if (codeDialog != null) codeDialog.dismiss();
                Toast.makeText(this, "Pareado com " + savedName(), Toast.LENGTH_SHORT).show();
                setStatus("Conectando a " + savedName() + "…", C_WAIT);
                remote.connect(savedHost());
            } else if (message.startsWith("Código incorreto") || message.startsWith("O código")) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                showCodeDialog();          // continua o mesmo pareamento
            } else {
                if (codeDialog != null) codeDialog.dismiss();
                allowPairing = false;
                setStatus(message, C_ERR);
            }
        });
    }

    private void showCodeDialog() {
        if (codeDialog != null && codeDialog.isShowing()) codeDialog.dismiss();
        EditText input = new EditText(this);
        input.setHint("ex.: 4F2A9C");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(6)});
        input.setTextSize(28);
        input.setLetterSpacing(0.3f);
        input.setGravity(Gravity.CENTER);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(input);
        codeDialog = new AlertDialog.Builder(this)
                .setTitle("Código mostrado na TV")
                .setMessage("Digite os 6 caracteres que apareceram na tela da " + savedName() + ".")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("Parear", (d, w) -> remote.finishPairing(input.getText().toString()))
                .setNegativeButton("Cancelar", (d, w) -> {
                    remote.cancelPairing();
                    allowPairing = false;
                    setStatus("Pareamento cancelado — toque aqui", C_OFF);
                })
                .show();
        input.requestFocus();
        if (codeDialog.getWindow() != null)
            codeDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    // ================================================================ Escolha da TV

    private final List<String[]> found = new ArrayList<>();
    private ArrayAdapter<String> foundAdapter;
    private AlertDialog pickerDialog;

    private void showTvPicker() {
        if (remote == null) return;
        found.clear();
        foundAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        LinearLayout extra = new LinearLayout(this);
        extra.setOrientation(LinearLayout.VERTICAL);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Procurando TVs na rede Wi‑Fi…")
                .setAdapter(foundAdapter, (d, which) -> {
                    String[] tv = found.get(which);
                    chooseTv(tv[0], tv[1]);
                })
                .setNeutralButton("Digitar IP", (d, w) -> askIp())
                .setOnDismissListener(d -> finder.stop());
        if (savedHost() != null) {
            b.setNegativeButton("Mais…", (d, w) -> showMore());
        }
        pickerDialog = b.show();
        finder.start();
        main.postDelayed(() -> {
            if (pickerDialog != null && pickerDialog.isShowing() && found.isEmpty())
                pickerDialog.setTitle("Nenhuma TV encontrada ainda — celular e TV estão na mesma rede?");
        }, 6000);
    }

    @Override
    public void onTvFound(String name, String host) {
        for (String[] t : found) if (t[1].equals(host)) return;
        found.add(new String[]{name, host});
        if (foundAdapter != null) foundAdapter.add(name + "\n" + host);
        if (pickerDialog != null && pickerDialog.isShowing()) pickerDialog.setTitle("Escolha o SmartChart");
    }

    private void askIp() {
        EditText input = new EditText(this);
        input.setHint("ex.: 192.168.0.25");
        input.setInputType(InputType.TYPE_CLASS_PHONE);
        String h = savedHost();
        if (h != null) input.setText(h);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Endereço IP da TV")
                .setMessage("Na TV: Configurações → Rede e Internet → (sua rede) → Endereço IP.")
                .setView(box)
                .setPositiveButton("Conectar", (d, w) -> {
                    String ip = input.getText().toString().trim();
                    if (!ip.isEmpty()) chooseTv("SmartChart", ip);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void showMore() {
        String[] items = {"Parear de novo com " + savedName(), "Esquecer esta TV", "Diagnóstico"};
        new AlertDialog.Builder(this)
                .setTitle(savedName() + " (" + savedHost() + ")")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        allowPairing = true;
                        remote.disconnect();
                        setStatus("Pareando — veja o código na TV", C_WAIT);
                        remote.startPairing(savedHost());
                    } else if (which == 1) {
                        remote.disconnect();
                        main.removeCallbacks(retry);
                        prefs.edit().remove(PREF_HOST).remove(PREF_NAME).apply();
                        setStatus("Toque aqui para escolher a TV", C_OFF);
                    } else {
                        showLog();
                    }
                })
                .show();
    }

    // ================================================================ Botões

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void buildGrid(LinearLayout grid) {
        final int cols = 4;
        LinearLayout row = null;
        for (int i = 0; i < TESTS.length; i++) {
            if (i % cols == 0) {
                row = newRow();
                grid.addView(row);
            }
            row.addView(cell((int) TESTS[i][0], (String) TESTS[i][1], (int) TESTS[i][2]),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        // O SmartBox fica junto do D-pad (ver buildDpad).
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private View cell(int drawable, String label, int key) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(4), dp(4), dp(4), dp(6));

        SquareImage img = new SquareImage(this);
        img.setImageResource(drawable);
        img.setContentDescription(label);
        bindKey(img, key);
        c.addView(img, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(12);
        t.setTextColor(Color.parseColor("#4A5A6B"));
        t.setGravity(Gravity.CENTER);
        //c.addView(t);
        return c;
    }

    private void buildDpad(FrameLayout container) {
        int screenW = getResources().getDisplayMetrics().widthPixels - dp(32);
        int size = Math.min(screenW, dp(300));
        int btn = Math.round(size * 0.34f);

        FrameLayout pad = new FrameLayout(this);
        pad.setBackgroundResource(R.drawable.dpad_bg);
        container.addView(pad, new FrameLayout.LayoutParams(size, size, Gravity.CENTER_HORIZONTAL));

        // SmartBox (esquerda) e Voltar (direita), menores, nos cantos de baixo ao lado do D-pad.
        // Ficam no espaço vazio fora do círculo; a área só cresce se a tela for estreita demais.
        int small = Math.round(size * 0.21f);
        float r = size / 2f, gap = dp(4);
        float dx = screenW / 2f - small;                       // distância horizontal centro→canto do botão
        float need = (r + gap) * (r + gap) - dx * dx;
        int height = Math.max(size, Math.round(r + small + (need > 0 ? (float) Math.sqrt(need) : 0)));
        container.setMinimumHeight(height);
        addCornerButton(container, R.drawable.btn_smartbox, "SmartBox", KeyMap.SMARTBOX, small, Gravity.BOTTOM | Gravity.START);
        addCornerButton(container, R.drawable.btn_back,     "Voltar",   KeyMap.BACK,     small, Gravity.BOTTOM | Gravity.END);

        addPadButton(pad, R.drawable.btn_up,    "Cima",     KeyMap.UP,    btn, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        addPadButton(pad, R.drawable.btn_down,  "Baixo",    KeyMap.DOWN,  btn, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        addPadButton(pad, R.drawable.btn_left,  "Esquerda", KeyMap.LEFT,  btn, Gravity.START | Gravity.CENTER_VERTICAL);
        addPadButton(pad, R.drawable.btn_right, "Direita",  KeyMap.RIGHT, btn, Gravity.END | Gravity.CENTER_VERTICAL);
        addPadButton(pad, R.drawable.btn_ok,    "OK",       KeyMap.OK,    btn, Gravity.CENTER);
    }

    private void addCornerButton(FrameLayout parent, int drawable, String desc, int key, int size, int gravity) {
        ImageView v = new ImageView(this);
        v.setImageResource(drawable);
        v.setContentDescription(desc);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        bindKey(v, key);
        parent.addView(v, new FrameLayout.LayoutParams(size, size, gravity));
    }

    private void addPadButton(FrameLayout pad, int drawable, String desc, int key, int size, int gravity) {
        ImageView v = new ImageView(this);
        v.setImageResource(drawable);
        v.setContentDescription(desc);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        bindKey(v, key);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, gravity);
        int m = dp(4);
        lp.setMargins(m, m, m, m);
        pad.addView(v, lp);
    }

    /**
     * Toque curto → tecla normal (SHORT).
     * Segurar → START_LONG após 400 ms e END_LONG ao soltar (a TV repete a tecla, como no controle).
     */
    @SuppressLint("ClickableViewAccessibility")
    private void bindKey(View v, int key) {
        final boolean[] holding = {false};
        final Runnable startHold = () -> {
            holding[0] = true;
            if (remote != null) remote.sendKey(key, AtvRemote.START_LONG);
        };
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(60).start();
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    if (remote == null || !remote.isReady()) {
                        Toast.makeText(this, "Conecte ao SmartChart primeiro", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    holding[0] = false;
                    main.postDelayed(startHold, HOLD_MS);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(90).start();
                    main.removeCallbacks(startHold);
                    if (remote != null && remote.isReady()) {
                        if (holding[0]) remote.sendKey(key, AtvRemote.END_LONG);
                        else if (e.getActionMasked() == MotionEvent.ACTION_UP) remote.sendKey(key, AtvRemote.SHORT);
                    }
                    holding[0] = false;
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) view.performClick();
                    return true;
            }
            return false;
        });
        v.setClickable(true);
    }

    private void setStatus(String text, int dotColor) {
        statusText.setText(text);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(dotColor);
        statusDot.setBackground(d);
    }

    // ================================================================ Diagnóstico

    private final List<String> logLines = new ArrayList<>();

    private void log(String s) { onLog(s); }

    @Override
    public void onLog(String line) {
        main.post(() -> {
            String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            logLines.add(t + "  " + line);
            if (logLines.size() > 100) logLines.remove(0);
        });
    }

    private void showLog() {
        StringBuilder sb = new StringBuilder();
        sb.append("Android ").append(Build.VERSION.RELEASE).append(" — ")
          .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n\n");
        for (String l : logLines) sb.append(l).append('\n');
        final String text = sb.toString();
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextIsSelectable(true);
        tv.setTextSize(12);
        tv.setPadding(dp(20), dp(12), dp(20), dp(12));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Diagnóstico")
                .setView(sv)
                .setPositiveButton("Fechar", null)
                .setNeutralButton("Compartilhar", (d, w) -> {
                    Intent s = new Intent(Intent.ACTION_SEND);
                    s.setType("text/plain");
                    s.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(s, "Compartilhar diagnóstico"));
                })
                .show();
    }

    /** ImageView sempre quadrada (altura = largura). */
    static class SquareImage extends ImageView {
        SquareImage(Context c) {
            super(c);
            setScaleType(ScaleType.FIT_CENTER);
            setAdjustViewBounds(false);
        }
        @Override protected void onMeasure(int w, int h) {
            super.onMeasure(w, w);
            int s = getMeasuredWidth();
            setMeasuredDimension(s, s);
        }
    }
}
