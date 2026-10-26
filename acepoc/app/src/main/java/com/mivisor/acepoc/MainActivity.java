package com.mivisor.acepoc;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
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
    private static final int PORT = 16900;
    private static final String BASE = "http://" + HOST + ":" + PORT;
    private static final long SOURCE_TIMEOUT_MS = 25000L;
    private static final Pattern HEX40 = Pattern.compile("^[0-9a-fA-F]{40}$");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final AtomicInteger attemptGeneration = new AtomicInteger();

    private EditText input;
    private TextView engineState;
    private TextView sourceState;
    private TextView diagnostics;
    private Button autoButton;
    private Button nextButton;
    private Button stopButton;
    private PlayerView playerView;
    private ExoPlayer player;

    private Process engineProcess;
    private volatile boolean engineReady = false;
    private volatile String lastEngineLog = "";

    private final List<String> sources = new ArrayList<>();
    private int currentIndex = -1;
    private boolean autoSearching = false;
    private boolean sourceAccepted = false;
    private String currentId = null;
    private Runnable timeoutRunnable;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        buildPlayer();
        startEngine();
        startDiagnosticsLoop();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(16), dp(22), dp(16));
        root.setBackgroundColor(Color.rgb(12, 15, 22));

        TextView title = text("MiVisor · P2P Test", 24, Color.WHITE);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView help = text(
                "Pega uno o varios Content ID / acestream:// (uno por línea). " +
                "La prueba NO abre la app Ace Stream: intenta la red P2P directamente y " +
                "se queda con la primera fuente que llegue a vídeo.",
                14, Color.LTGRAY);
        help.setPadding(0, 0, 0, dp(8));
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
        buttons.setPadding(0, dp(10), 0, dp(8));

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
        playerLp.topMargin = dp(10);
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
                    final int idx = currentIndex;
                    sourceState.setText("✓ Fuente " + (idx + 1) + " de " + sources.size() +
                            " seleccionada · preview activa");
                    sourceState.setTextColor(Color.rgb(95, 220, 120));
                    nextButton.setEnabled(idx + 1 < sources.size());
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (currentId != null && !sourceAccepted) {
                    failCurrent("El reproductor no recibió vídeo: " + shortMessage(error.getMessage()));
                }
            }
        });
    }

    private void startEngine() {
        engineState.setText("Motor: arrancando núcleo P2P local...");
        io.execute(() -> {
            try {
                String nativeDir = getApplicationInfo().nativeLibraryDir;
                String binary = nativeDir + "/liboutpace.so";

                ProcessBuilder pb = new ProcessBuilder(binary);
                pb.redirectErrorStream(true);
                pb.environment().put("OUTPACE_BIND", HOST + ":" + PORT);
                pb.environment().put("OUTPACE_DATA_DIR", getFilesDir().getAbsolutePath() + "/outpace");
                pb.environment().put("HOME", getFilesDir().getAbsolutePath());
                engineProcess = pb.start();

                Thread logThread = new Thread(() -> {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(engineProcess.getInputStream()))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            lastEngineLog = line;
                        }
                    } catch (Exception ignored) {
                    }
                }, "outpace-log");
                logThread.setDaemon(true);
                logThread.start();

                boolean ok = false;
                for (int i = 0; i < 30 && engineProcess.isAlive(); i++) {
                    if (healthCheck()) {
                        ok = true;
                        break;
                    }
                    Thread.sleep(500);
                }

                final boolean ready = ok;
                main.post(() -> {
                    engineReady = ready;
                    if (ready) {
                        engineState.setText("Motor: ✓ P2P local preparado · sin abrir Ace Stream");
                        engineState.setTextColor(Color.rgb(95, 220, 120));
                        autoButton.setEnabled(true);
                    } else {
                        engineState.setText("Motor: ✕ no pudo iniciarse");
                        engineState.setTextColor(Color.rgb(255, 110, 110));
                        diagnostics.setText("Último mensaje: " + shortMessage(lastEngineLog));
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    engineReady = false;
                    engineState.setText("Motor: ✕ error al iniciar");
                    engineState.setTextColor(Color.rgb(255, 110, 110));
                    diagnostics.setText(shortMessage(e.toString()));
                });
            }
        });
    }

    private boolean healthCheck() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(BASE + "/healthz").openConnection();
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
            autoSearching = false;
            sourceAccepted = false;
            sourceState.setText("✕ Ninguna de las " + sources.size() + " fuentes llegó a vídeo.");
            sourceState.setTextColor(Color.rgb(255, 110, 110));
            diagnostics.setText("Prueba terminada. Esto puede significar: sin peers, fuente inactiva, " +
                    "o que este Content ID necesita una resolución de descriptor distinta.");
            return;
        }

        String id = sources.get(currentIndex);
        currentId = id;
        sourceAccepted = false;
        int gen = attemptGeneration.incrementAndGet();

        sourceState.setText("Probando fuente " + (currentIndex + 1) + " de " + sources.size() + "...");
        sourceState.setTextColor(Color.YELLOW);
        diagnostics.setText("Buscando peers y esperando vídeo · ID " + masked(id));

        MediaItem item = new MediaItem.Builder()
                .setMediaId(id)
                .setUri(Uri.parse(BASE + "/streams/ace/" + id + ".ts"))
                .build();
        player.stop();
        player.clearMediaItems();
        player.setMediaItem(item);
        player.prepare();
        player.play();

        cancelTimeout();
        timeoutRunnable = () -> {
            if (gen == attemptGeneration.get() && !sourceAccepted && id.equals(currentId)) {
                failCurrent("Tiempo agotado sin llegar a vídeo");
            }
        };
        main.postDelayed(timeoutRunnable, SOURCE_TIMEOUT_MS);
    }

    private void failCurrent(String reason) {
        final String failedId = currentId;
        if (failedId == null || sourceAccepted) return;
        cancelTimeout();
        player.stop();
        sourceState.setText("Fuente " + (currentIndex + 1) + " falló · " + reason);
        sourceState.setTextColor(Color.rgb(255, 160, 90));
        stopDaemonSession(failedId);

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
        diagnostics.setText("Motor P2P sigue preparado para otra prueba.");
    }

    private void stopCurrent(boolean clearId) {
        attemptGeneration.incrementAndGet();
        cancelTimeout();
        String id = currentId;
        player.stop();
        player.clearMediaItems();
        if (id != null) stopDaemonSession(id);
        if (clearId) currentId = null;
    }

    private void stopDaemonSession(String id) {
        io.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(BASE + "/streams/ace/" + id).openConnection();
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
                String id = currentId;
                if (id != null && engineReady) {
                    io.execute(() -> fetchStatus(id));
                } else if (engineReady && !lastEngineLog.isEmpty()) {
                    main.post(() -> {
                        if (currentId == null) diagnostics.setText("Motor: " + shortMessage(lastEngineLog));
                    });
                }
                main.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private void fetchStatus(String id) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(BASE + "/streams/ace/" + id + "/status").openConnection();
            c.setConnectTimeout(700);
            c.setReadTimeout(700);
            if (c.getResponseCode() != 200) return;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONObject o = new JSONObject(sb.toString());
            int peers = o.optInt("peers", 0);
            long engineBuffer = o.optLong("buffer_ms", 0);
            main.post(() -> {
                if (!id.equals(currentId)) return;
                long playerBuffer = Math.max(0, player.getBufferedPosition() - player.getCurrentPosition());
                diagnostics.setText("Peers: " + peers +
                        " · buffer motor: " + engineBuffer + " ms" +
                        " · buffer player: " + playerBuffer + " ms" +
                        (lastEngineLog.isEmpty() ? "" : "\n" + shortMessage(lastEngineLog)));
            });
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String masked(String id) {
        if (id == null || id.length() < 10) return "—";
        return id.substring(0, 6) + "…" + id.substring(id.length() - 4);
    }

    private String shortMessage(String s) {
        if (s == null || s.trim().isEmpty()) return "sin detalle";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 150 ? s.substring(0, 150) + "…" : s;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        autoSearching = false;
        attemptGeneration.incrementAndGet();
        cancelTimeout();
        if (currentId != null) stopDaemonSession(currentId);
        if (player != null) player.release();
        if (engineProcess != null) engineProcess.destroy();
        io.shutdownNow();
        super.onDestroy();
    }
}
