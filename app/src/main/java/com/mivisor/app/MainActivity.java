package com.mivisor.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    // Link que se usa si todavía no se ha podido leer nunca el config.json
    private static final String DEFAULT_URL = "https://www.google.com";

    private WebView web;
    private ProgressBar progress;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("config", MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        // Navegador incrustado
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        web.setWebViewClient(new WebViewClient()); // los links se abren dentro de la app
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Barra de carga fina arriba
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4), Gravity.TOP));

        // Botón flotante de actualizar
        Button refresh = new Button(this);
        refresh.setText("⟳");
        refresh.setTextSize(24);
        refresh.setTextColor(Color.WHITE);
        refresh.setPadding(0, 0, 0, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#CC1A73E8"));
        refresh.setBackground(bg);
        refresh.setElevation(dp(6));
        refresh.setOnClickListener(v -> {
            Toast.makeText(this, "Buscando actualización…", Toast.LENGTH_SHORT).show();
            fetchConfig(true);
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(56), dp(56),
                Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, dp(16), dp(24));
        root.addView(refresh, lp);

        setContentView(root);

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            // Abre el último link guardado al instante y luego comprueba si hay uno nuevo
            web.loadUrl(prefs.getString("url", DEFAULT_URL));
            fetchConfig(false);
        }
    }

    /** Descarga config.json de GitHub. Si el link cambió (o es manual), lo carga. */
    private void fetchConfig(boolean manual) {
        new Thread(() -> {
            String url = null;
            String error = null;
            // 1º la API de GitHub (sin caché), 2º el archivo "raw" como respaldo
            String[] sources = {
                    BuildConfig.API_URL,
                    BuildConfig.RAW_URL + "?t=" + System.currentTimeMillis()
            };
            for (String src : sources) {
                try {
                    JSONObject json = new JSONObject(download(src));
                    url = json.getString("url").trim();
                    if (!url.isEmpty()) break;
                } catch (Exception e) {
                    error = e.getMessage();
                }
            }

            final String newUrl = url;
            final String err = error;
            runOnUiThread(() -> {
                if (newUrl == null || newUrl.isEmpty()) {
                    if (manual) {
                        Toast.makeText(this, "No se pudo actualizar (" + err + "). Uso el último link guardado.",
                                Toast.LENGTH_LONG).show();
                        web.reload();
                    }
                    return;
                }
                String old = prefs.getString("url", DEFAULT_URL);
                prefs.edit().putString("url", newUrl).apply();
                if (manual || !newUrl.equals(old)) {
                    web.loadUrl(newUrl);
                }
                if (manual) {
                    Toast.makeText(this, newUrl.equals(old) ? "Ya tienes la última versión" : "¡Link actualizado!",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private static String download(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "MiVisor-App");
        c.setRequestProperty("Accept", "application/vnd.github.raw");
        c.setRequestProperty("Cache-Control", "no-cache");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
