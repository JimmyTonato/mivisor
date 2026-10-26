package com.mivisor.acepoc;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String HOST = "127.0.0.1";
    private static final long SOURCE_TIMEOUT_MS = 50000L;
    private static final Pattern HEX40 = Pattern.compile("^[0-9a-fA-F]{40}$");
    private static final int MAX_LOG_LINES = 700;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final AtomicInteger attemptGeneration = new AtomicInteger();
    private final Object logLock = new Object();
    private final ArrayDeque<String> logLines = new ArrayDeque<>();

    private EditText input;
    private TextView engineState;
    private TextView sourceState;
    private TextView diagnostics;
    private Button autoButton;
    private Button nextButton;
    private Button stopButton;
    private Button settingsButton;
    private PlayerView playerView;
    private ExoPlayer player;

    private Process engineProcess;
    private volatile boolean engineReady = false;
    private volatile String lastEngineLog = "";
    private volatile int enginePort = -1;
    private volatile String engineBase = null;
    private volatile boolean logsEnabled = true;
    private volatile boolean logAutoScroll = true;

    private final List<String> sources = new ArrayList<>();
    private int currentIndex = -1;
    private boolean autoSearching = false;
    private boolean sourceAccepted = false;
    private String currentId = null;
    private String currentTarget = null;
    private Runnable timeoutRunnable;

    private SharedPreferences prefs;
    private String profile = "balanced";

    private Dialog settingsDialog;
    private TextView settingsProfileText;
    private TextView logText;
    private ScrollView logScroll;
    private CheckBox logsCheck;
    private CheckBox autoScrollCheck;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("p2p_test_v3", MODE_PRIVATE);
        profile = prefs.getString("profile", "balanced");
        logsEnabled = prefs.getBoolean("logs_enabled", true);
        logAutoScroll = prefs.getBoolean("logs_autoscroll", true);

        buildUi();
        buildPlayer();
        startEngine();
        startDiagnosticsLoop();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), dp(12));
        root.setBackgroundColor(Color.rgb(12, 15, 22));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = text("MiVisor · P2P Test v3", 24, Color.WHITE);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        settingsButton = new Button(this);
        settingsButton.setText("AJUSTES / LOGS");
        settingsButton.setOnClickListener(v -> showSettingsDialog());
        header.addView(settingsButton);
        root.addView(header);

        TextView help = text(
                "Pega uno o varios Content ID / acestream:// (uno por línea). " +
                "Resuelve descriptor, entra al P2P y mantiene la primera fuente que llegue a vídeo.",
                13, Color.LTGRAY);
        help.setPadding(0, dp(4), 0, dp(8));
        root.addView(help);

        input = new EditText(this);
        input.setHint("acestream://0123456789abcdef...\nacestream://...");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);
        input.setTextSize(16);
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setMaxLines(4);
        input.setBackgroundColor(Color.rgb(28, 33, 44));
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.START);
        buttons.setPadding(0, dp(8), 0, dp(6));

        autoButton = new Button(this);
        autoButton.setText("PROBAR AUTOMÁTICAMENTE");
        autoButton.setEnabled(false);
        autoButton.setOnClickListener(v -> beginAutoSearch());

        nextButton = new Button(this);
        nextButton.setText("SIGUIENTE FUENTE");
        nextButton.setEnabled(false);
        nextButton.setOnClickListener(v -> nextManual());

        stopButton = new Button(this);
        stopButton.setText("DETENER");
        stopButton.setOnClickListener(v -> stopEverythingButEngine());

        buttons.addView(autoButton);
        buttons.addView(nextButton);
        buttons.addView(stopButton);
        root.addView(buttons);

        engineState = text("Motor: iniciando...", 14, Color.YELLOW);
        sourceState = text("Fuente: —", 16, Color.WHITE);
        diagnostics = text("Diagnóstico: esperando motor local", 13, Color.LTGRAY);
        root.addView(engineState);
        root.addView(sourceState);
        root.addView(diagnostics);

        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setKeepScreenOn(true);
        playerView.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams playerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        playerLp.topMargin = dp(8);
        root.addView(playerView, playerLp);

        setContentView(root);
    }

    private TextView text(String value, float sp, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private void buildPlayer() {
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY && currentId != null && !sourceAccepted) {
                    sourceAccepted = true;
                    autoSearching = false;
                    cancelTimeout();
                    int idx = currentIndex;
                    sourceState.setText("✓ Fuente " + (idx + 1) + " de " + sources.size() +
                            " · descriptor resuelto · preview activa");
                    sourceState.setTextColor(Color.rgb(95, 220, 120));
                    nextButton.setEnabled(idx + 1 < sources.size());
                    addAppLog("PLAYBACK READY · fuente " + (idx + 1) + "/" + sources.size());
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                addAppLog("PLAYER ERROR · " + shortMessage(error.getMessage()));
                if (currentId != null && !sourceAccepted) {
                    failCurrent("El reproductor no recibió vídeo: " + shortMessage(error.getMessage()));
                }
            }
        });
    }

    private void startEngine() {
        stopEngineOnly();
        engineReady = false;
        autoButton.setEnabled(false);
        engineState.setText("Motor: arrancando núcleo P2P local...");
        engineState.setTextColor(Color.YELLOW);
        io.execute(() -> {
            try {
                int selectedPort = findFreeLoopbackPort();
                enginePort = selectedPort;
                engineBase = "http://" + HOST + ":" + selectedPort;
                addAppLog("ENGINE START · perfil=" + profile + " · port=" + selectedPort);

                String nativeDir = getApplicationInfo().nativeLibraryDir;
                String binary = nativeDir + "/liboutpace.so";

                ProcessBuilder pb = new ProcessBuilder(binary, "serve");
                pb.redirectErrorStream(true);

                pb.environment().put("OUTPACE_BIND", HOST + ":" + selectedPort);
                pb.environment().put("OUTPACE_DATA_DIR", getFilesDir().getAbsolutePath() + "/outpace");
                pb.environment().put("HOME", getFilesDir().getAbsolutePath());

                // Resolver de Content ID: catálogo firmado primero; BEP-9 queda como fallback.
                pb.environment().put("OUTPACE_BEP9_CONTENT_ID_FALLBACK", "1");

                applyProfileEnvironment(pb, profile);
                engineProcess = pb.start();

                Thread logThread = new Thread(() -> {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(engineProcess.getInputStream()))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            lastEngineLog = line;
                            addEngineLog(line);
                        }
                    } catch (Exception e) {
                        addAppLog("LOG READER ERROR · " + shortMessage(e.toString()));
                    }
                }, "outpace-log");
                logThread.setDaemon(true);
                logThread.start();

                boolean ok = false;
                for (int i = 0; i < 50 && engineProcess.isAlive(); i++) {
                    if (healthCheck()) {
                        ok = true;
                        break;
                    }
                    Thread.sleep(400);
                }

                final boolean ready = ok;
                main.post(() -> {
                    engineReady = ready;
                    if (ready) {
                        engineState.setText("Motor: ✓ preparado · perfil " + profileLabel(profile) +
                                " · puerto " + enginePort);
                        engineState.setTextColor(Color.rgb(95, 220, 120));
                        autoButton.setEnabled(true);
                        addAppLog("ENGINE READY");
                    } else {
                        engineState.setText("Motor: ✕ no pudo iniciarse");
                        engineState.setTextColor(Color.rgb(255, 110, 110));
                        diagnostics.setText("Último mensaje: " + shortMessage(lastEngineLog));
                        addAppLog("ENGINE FAILED · " + shortMessage(lastEngineLog));
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    engineReady = false;
                    engineState.setText("Motor: ✕ error al iniciar");
                    engineState.setTextColor(Color.rgb(255, 110, 110));
                    diagnostics.setText(shortMessage(e.toString()));
                });
                addAppLog("ENGINE EXCEPTION · " + shortMessage(e.toString()));
            }
        });
    }

    private int findFreeLoopbackPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName(HOST))) {
            socket.setReuseAddress(false);
            return socket.getLocalPort();
        }
    }

    private void applyProfileEnvironment(ProcessBuilder pb, String selected) {
        // Los tres perfiles usan límites razonables para Android TV.
        if ("fast".equals(selected)) {
            pb.environment().put("OUTPACE_PREBUFFER_MS", "8000");
            pb.environment().put("OUTPACE_PREBUFFER_TIMEOUT_MS", "7000");
            pb.environment().put("OUTPACE_REQUEST_TIMEOUT_MS", "1500");
            pb.environment().put("OUTPACE_REQUEST_CHECK_INTERVAL_MS", "750");
            pb.environment().put("OUTPACE_MAX_ACTIVE_UPSTREAMS", "4");
            pb.environment().put("OUTPACE_MAX_PARALLEL_CONNECT", "12");
            pb.environment().put("OUTPACE_SESSION_BUFFER", "256");
        } else if ("smooth".equals(selected)) {
            pb.environment().put("OUTPACE_PREBUFFER_MS", "30000");
            pb.environment().put("OUTPACE_PREBUFFER_TIMEOUT_MS", "15000");
            pb.environment().put("OUTPACE_REQUEST_TIMEOUT_MS", "1000");
            pb.environment().put("OUTPACE_REQUEST_CHECK_INTERVAL_MS", "500");
            pb.environment().put("OUTPACE_MAX_ACTIVE_UPSTREAMS", "8");
            pb.environment().put("OUTPACE_MAX_PARALLEL_CONNECT", "24");
            pb.environment().put("OUTPACE_SESSION_BUFFER", "512");
        } else {
            pb.environment().put("OUTPACE_PREBUFFER_MS", "15000");
            pb.environment().put("OUTPACE_PREBUFFER_TIMEOUT_MS", "12000");
            pb.environment().put("OUTPACE_REQUEST_TIMEOUT_MS", "1200");
            pb.environment().put("OUTPACE_REQUEST_CHECK_INTERVAL_MS", "600");
            pb.environment().put("OUTPACE_MAX_ACTIVE_UPSTREAMS", "6");
            pb.environment().put("OUTPACE_MAX_PARALLEL_CONNECT", "16");
            pb.environment().put("OUTPACE_SESSION_BUFFER", "384");
        }
        pb.environment().put("OUTPACE_STALE_UPSTREAM_TIMEOUT_MS", "12000");
    }

    private boolean healthCheck() {
        String base = engineBase;
        if (base == null) return false;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + "/healthz").openConnection();
            c.setConnectTimeout(600);
            c.setReadTimeout(600);
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void beginAutoSearch() {
        if (!engineReady) return;
        List<String> parsed = parseSources(input.getText().toString());
        if (parsed.isEmpty()) {
            sourceState.setText("No hay Content ID válidos de 40 caracteres hexadecimales.");
            sourceState.setTextColor(Color.rgb(255, 170, 90));
            return;
        }
        stopCurrent(false);
        sources.clear();
        sources.addAll(parsed);
        currentIndex = -1;
        autoSearching = true;
        sourceAccepted = false;
        nextButton.setEnabled(false);
        addAppLog("AUTO SEARCH · " + sources.size() + " fuente(s)");
        tryNext();
    }

    private void nextManual() {
        if (sources.isEmpty()) {
            beginAutoSearch();
            return;
        }
        autoSearching = false;
        sourceAccepted = false;
        int next = currentIndex + 1;
        if (next >= sources.size()) {
            sourceState.setText("No quedan más fuentes.");
            return;
        }
        stopCurrent(false);
        currentIndex = next - 1;
        tryNext();
    }

    private void tryNext() {
        currentIndex++;
        if (currentIndex >= sources.size()) {
            currentId = null;
            currentTarget = null;
            autoSearching = false;
            sourceAccepted = false;
            sourceState.setText("✕ Ninguna de las " + sources.size() + " fuentes llegó a vídeo.");
            sourceState.setTextColor(Color.rgb(255, 110, 110));
            diagnostics.setText("Consulta Ajustes / Logs para ver la causa del último intento.");
            addAppLog("AUTO SEARCH END · ninguna fuente válida");
            return;
        }

        currentId = sources.get(currentIndex);
        currentTarget = "cid:" + currentId;
        sourceAccepted = false;
        int gen = attemptGeneration.incrementAndGet();

        sourceState.setText("Fuente " + (currentIndex + 1) + " de " + sources.size() +
                " · resolviendo descriptor y preparando buffer...");
        sourceState.setTextColor(Color.YELLOW);
        diagnostics.setText("ID " + masked(currentId) + " · perfil " + profileLabel(profile));
        addAppLog("SOURCE START · " + (currentIndex + 1) + "/" + sources.size() +
                " · " + masked(currentId));

        MediaItem item = new MediaItem.Builder()
                .setMediaId(currentTarget)
                .setUri(Uri.parse(engineBase + "/streams/ace/" + currentTarget + ".ts"))
                .build();
        player.stop();
        player.clearMediaItems();
        player.setMediaItem(item);
        player.prepare();
        player.play();

        cancelTimeout();
        final String expectedTarget = currentTarget;
        timeoutRunnable = () -> {
            if (gen == attemptGeneration.get()
                    && !sourceAccepted
                    && expectedTarget.equals(currentTarget)) {
                failCurrent("Tiempo agotado sin llegar a vídeo");
            }
        };
        main.postDelayed(timeoutRunnable, SOURCE_TIMEOUT_MS);
    }

    private void failCurrent(String reason) {
        String failedTarget = currentTarget;
        if (currentId == null || failedTarget == null || sourceAccepted) return;
        cancelTimeout();
        player.stop();
        stopDaemonSession(failedTarget);
        addAppLog("SOURCE FAIL · " + masked(currentId) + " · " + reason);

        sourceState.setText("Fuente " + (currentIndex + 1) + " falló · " + reason);
        sourceState.setTextColor(Color.rgb(255, 110, 110));

        if (autoSearching) {
            main.postDelayed(this::tryNext, 350);
        } else {
            nextButton.setEnabled(currentIndex + 1 < sources.size());
        }
    }

    private void stopEverythingButEngine() {
        autoSearching = false;
        sourceAccepted = false;
        stopCurrent(true);
        sourceState.setText("Fuente: detenida");
        sourceState.setTextColor(Color.WHITE);
        diagnostics.setText("Motor P2P sigue preparado.");
        addAppLog("PLAYBACK STOP");
    }

    private void stopCurrent(boolean clearId) {
        attemptGeneration.incrementAndGet();
        cancelTimeout();
        String target = currentTarget;
        player.stop();
        player.clearMediaItems();
        if (target != null) stopDaemonSession(target);
        currentTarget = null;
        if (clearId) currentId = null;
    }

    private void stopDaemonSession(String target) {
        io.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(engineBase + "/streams/ace/" + target).openConnection();
                c.setRequestMethod("DELETE");
                c.setConnectTimeout(1000);
                c.setReadTimeout(1000);
                c.getResponseCode();
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    private void stopEngineOnly() {
        if (engineProcess != null) {
            try {
                engineProcess.destroy();
            } catch (Exception ignored) {
            }
            engineProcess = null;
        }
        engineReady = false;
        engineBase = null;
        enginePort = -1;
    }

    private void restartEngineForProfile(String newProfile) {
        if (newProfile.equals(profile)) return;
        stopCurrent(true);
        profile = newProfile;
        prefs.edit().putString("profile", profile).apply();
        addAppLog("PROFILE CHANGE · " + profileLabel(profile));
        if (settingsProfileText != null) {
            settingsProfileText.setText("Perfil activo: " + profileLabel(profile));
        }
        startEngine();
    }

    private void cancelTimeout() {
        if (timeoutRunnable != null) {
            main.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    private List<String> parseSources(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) return new ArrayList<>();
        for (String token : raw.split("[\\r\\n,;]+")) {
            String s = normalize(token);
            if (s != null) out.add(s);
        }
        return new ArrayList<>(out);
    }

    private String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.toLowerCase(Locale.ROOT).startsWith("acestream://")) {
            s = s.substring("acestream://".length()).trim();
        } else if (s.toLowerCase(Locale.ROOT).startsWith("acestream:?")) {
            try {
                Uri u = Uri.parse(s);
                String q = u.getQueryParameter("content_id");
                if (q == null) q = u.getQueryParameter("id");
                if (q != null) s = q.trim();
            } catch (Exception ignored) {
            }
        }
        return HEX40.matcher(s).matches() ? s.toLowerCase(Locale.ROOT) : null;
    }

    private void startDiagnosticsLoop() {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                String target = currentTarget;
                if (target != null && engineReady) {
                    io.execute(() -> fetchStatus(target));
                } else if (engineReady && !lastEngineLog.isEmpty()) {
                    main.post(() -> {
                        if (currentTarget == null) {
                            diagnostics.setText("Motor listo · perfil " + profileLabel(profile));
                        }
                    });
                }
                refreshLogView();
                main.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private void fetchStatus(String target) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(engineBase + "/streams/ace/" + target + "/status").openConnection();
            c.setConnectTimeout(800);
            c.setReadTimeout(800);
            if (c.getResponseCode() != 200) return;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }

            JSONObject o = new JSONObject(sb.toString());
            int peers = o.optInt("peers", 0);
            long bitrate = o.optLong("bitrate", 0);
            long engineBuffer = o.optLong("buffer_ms", 0);
            long uploaded = o.optLong("uploaded", 0);
            int peersServed = o.optInt("peers_served", 0);
            JSONObject recovery = o.optJSONObject("recovery");
            Object livepos = o.opt("livepos");

            main.post(() -> {
                if (!target.equals(currentTarget)) return;
                long playerBuffer = Math.max(0, player.getBufferedPosition() - player.getCurrentPosition());
                String bitrateText = bitrate > 0
                        ? String.format(Locale.US, "%.2f Mb/s", (bitrate * 8.0) / 1_000_000.0)
                        : "—";
                String rec = recovery == null ? "—" : compact(recovery.toString(), 120);
                String lp = livepos == null ? "—" : compact(String.valueOf(livepos), 90);

                diagnostics.setText(
                        "Peers: " + peers +
                        " · bitrate: " + bitrateText +
                        " · buffer motor: " + engineBuffer + " ms" +
                        " · buffer player: " + playerBuffer + " ms" +
                        "\nRecuperación: " + rec +
                        " · live: " + lp +
                        " · subida: " + uploaded +
                        " · peers servidos: " + peersServed);
            });
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void showSettingsDialog() {
        if (settingsDialog != null && settingsDialog.isShowing()) {
            refreshLogView();
            return;
        }

        settingsDialog = new Dialog(this);
        settingsDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(18), dp(22), dp(18));
        root.setBackgroundColor(Color.rgb(20, 24, 32));

        TextView title = text("Ajustes y diagnóstico P2P", 22, Color.WHITE);
        root.addView(title);

        settingsProfileText = text("Perfil activo: " + profileLabel(profile), 16, Color.LTGRAY);
        settingsProfileText.setPadding(0, dp(6), 0, dp(8));
        root.addView(settingsProfileText);

        LinearLayout profiles = new LinearLayout(this);
        profiles.setOrientation(LinearLayout.HORIZONTAL);

        Button fast = new Button(this);
        fast.setText("RÁPIDO");
        fast.setOnClickListener(v -> restartEngineForProfile("fast"));

        Button balanced = new Button(this);
        balanced.setText("EQUILIBRADO");
        balanced.setOnClickListener(v -> restartEngineForProfile("balanced"));

        Button smooth = new Button(this);
        smooth.setText("FLUIDEZ MÁXIMA");
        smooth.setOnClickListener(v -> restartEngineForProfile("smooth"));

        profiles.addView(fast);
        profiles.addView(balanced);
        profiles.addView(smooth);
        root.addView(profiles);

        TextView profileHelp = text(
                "Rápido: menos espera. · Equilibrado: compromiso recomendado. · " +
                "Fluidez máxima: más prebuffer y más peers activos; consume más red/RAM.",
                13, Color.GRAY);
        profileHelp.setPadding(0, 0, 0, dp(8));
        root.addView(profileHelp);

        logsCheck = new CheckBox(this);
        logsCheck.setText("Guardar y mostrar logs detallados");
        logsCheck.setTextColor(Color.WHITE);
        logsCheck.setChecked(logsEnabled);
        logsCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            logsEnabled = isChecked;
            prefs.edit().putBoolean("logs_enabled", isChecked).apply();
            addAppLog(isChecked ? "LOGS ENABLED" : "LOGS DISABLED");
            refreshLogView();
        });
        root.addView(logsCheck);

        autoScrollCheck = new CheckBox(this);
        autoScrollCheck.setText("Autoscroll de logs");
        autoScrollCheck.setTextColor(Color.WHITE);
        autoScrollCheck.setChecked(logAutoScroll);
        autoScrollCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            logAutoScroll = isChecked;
            prefs.edit().putBoolean("logs_autoscroll", isChecked).apply();
        });
        root.addView(autoScrollCheck);

        LinearLayout logButtons = new LinearLayout(this);
        logButtons.setOrientation(LinearLayout.HORIZONTAL);

        Button clear = new Button(this);
        clear.setText("LIMPIAR");
        clear.setOnClickListener(v -> {
            synchronized (logLock) {
                logLines.clear();
            }
            refreshLogView();
        });

        Button copy = new Button(this);
        copy.setText("COPIAR LOG");
        copy.setOnClickListener(v -> copyLogs());

        Button close = new Button(this);
        close.setText("CERRAR");
        close.setOnClickListener(v -> settingsDialog.dismiss());

        logButtons.addView(clear);
        logButtons.addView(copy);
        logButtons.addView(close);
        root.addView(logButtons);

        logText = text("", 12, Color.LTGRAY);
        logText.setTextIsSelectable(true);
        logText.setPadding(dp(8), dp(8), dp(8), dp(8));
        logText.setBackgroundColor(Color.rgb(8, 10, 14));

        logScroll = new ScrollView(this);
        logScroll.addView(logText, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        settingsDialog.setContentView(root);
        Window w = settingsDialog.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawableResource(android.R.color.transparent);
        }
        settingsDialog.setOnShowListener(d -> {
            Window window = settingsDialog.getWindow();
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            }
            refreshLogView();
        });
        settingsDialog.show();
    }

    private void addEngineLog(String line) {
        if (!logsEnabled) return;
        addLogLine("ENG", line);
    }

    private void addAppLog(String line) {
        if (!logsEnabled && !line.startsWith("LOGS ENABLED")) return;
        addLogLine("APP", line);
    }

    private void addLogLine(String tag, String line) {
        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String clean = line == null ? "" : line.replace('\r', ' ').trim();
        synchronized (logLock) {
            logLines.addLast(timestamp + " [" + tag + "] " + clean);
            while (logLines.size() > MAX_LOG_LINES) {
                logLines.removeFirst();
            }
        }
    }

    private String allLogs() {
        StringBuilder sb = new StringBuilder();
        synchronized (logLock) {
            for (String line : logLines) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private void refreshLogView() {
        if (logText == null || settingsDialog == null || !settingsDialog.isShowing()) return;
        main.post(() -> {
            if (logText == null) return;
            if (!logsEnabled) {
                logText.setText("Logs desactivados. Actívalos para empezar a registrar desde ese momento.");
                return;
            }
            logText.setText(allLogs());
            if (logAutoScroll && logScroll != null) {
                logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private void copyLogs() {
        String logs = allLogs();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("MiVisor P2P logs", logs));
        Toast.makeText(this, "Logs copiados", Toast.LENGTH_SHORT).show();
    }

    private String profileLabel(String value) {
        if ("fast".equals(value)) return "Rápido";
        if ("smooth".equals(value)) return "Fluidez máxima";
        return "Equilibrado";
    }

    private String masked(String id) {
        if (id == null || id.length() < 10) return "—";
        return id.substring(0, 6) + "…" + id.substring(id.length() - 4);
    }

    private String shortMessage(String s) {
        if (s == null || s.trim().isEmpty()) return "sin detalle";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 180 ? s.substring(0, 180) + "…" : s;
    }

    private String compact(String s, int max) {
        if (s == null) return "—";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        autoSearching = false;
        attemptGeneration.incrementAndGet();
        cancelTimeout();
        if (currentTarget != null) stopDaemonSession(currentTarget);
        if (player != null) player.release();
        stopEngineOnly();
        io.shutdownNow();
        super.onDestroy();
    }
}
