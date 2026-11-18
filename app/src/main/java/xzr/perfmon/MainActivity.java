package xzr.perfmon;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

public class MainActivity extends Activity {
    private static final String GRANT_CMD = "pm grant xzr.perfmon android.permission.DUMP";

    private ScrollView scroll;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.init(this);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Theme.BG);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAsync();
    }

    /** Destek taraması (dumpsys çağırabildiği için) arka planda yapılır, sonra ekran yeniden kurulur. */
    private void refreshAsync() {
        new Thread(() -> {
            Support.probe(getApplicationContext());
            FpsMeter.detect(getApplicationContext());
            runOnUiThread(this::build);
        }, "perfmon-probe").start();
    }

    private void build() {
        if (isFinishing() || isDestroyed()) return;
        final Context c = this;
        final int pad = Ui.dp(c, 16);

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        root.addView(header(c));
        root.addView(startButton(c));
        root.addView(monitorCard(c));
        root.addView(windowCard(c));
        root.addView(advancedCard(c));
        root.addView(compatCard(c));
        root.addView(aboutCard(c));

        scroll.removeAllViews();
        scroll.addView(root);
    }

    // ------------------------------------------------------------------ bölümler

    private View header(Context c) {
        LinearLayout row = Ui.row(c);
        TextView title = Ui.text(c, getString(R.string.app_name), 26, Theme.TEXT);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        boolean on = FloatingWindow.running;
        TextView pill = Ui.text(c, getString(on ? R.string.state_on : R.string.state_off), 12,
                on ? Theme.BG : Theme.MUTED);
        pill.setPadding(Ui.dp(c, 12), Ui.dp(c, 5), Ui.dp(c, 12), Ui.dp(c, 5));
        pill.setBackground(Ui.round(c, on ? Theme.ACCENT : Theme.SURFACE_HI, 20));
        row.addView(pill);

        LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(c, 14);
        row.setLayoutParams(lp);
        return row;
    }

    private View startButton(Context c) {
        boolean on = FloatingWindow.running;
        Button b = Ui.button(c, getString(on ? R.string.stop : R.string.start),
                on ? Theme.SURFACE_HI : Theme.ACCENT, on ? Theme.TEXT : Theme.BG);
        b.setTextSize(16);
        LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 54));
        lp.bottomMargin = Ui.dp(c, 14);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> toggleMonitor());
        return b;
    }

    private View monitorCard(Context c) {
        LinearLayout card = Ui.card(c, getString(R.string.sec_monitor));
        Runnable r = () -> FloatingWindow.reload(this);

        card.addView(Ui.toggle(c, getString(R.string.fps), Prefs.FPS, true, r));
        if (Support.cpuFreq)
            card.addView(Ui.toggle(c, getString(R.string.cpu_freq), Prefs.CPUFREQ, true, r));
        if (Support.cpuLoad)
            card.addView(Ui.toggle(c, getString(R.string.cpu_load), Prefs.CPULOAD, true, r));
        if (Support.cpuFreq || Support.cpuLoad)
            card.addView(Ui.toggle(c, getString(R.string.group_clusters), Prefs.GROUP, true, r));
        if (Support.gpuFreq || Support.gpuLoad)
            card.addView(Ui.toggle(c, getString(R.string.gpu), Prefs.GPU, true, r));
        if (Support.temp)
            card.addView(Ui.toggle(c, getString(R.string.cpu_temp), Prefs.TEMP, true, r));
        card.addView(Ui.toggle(c, getString(R.string.ram), Prefs.MEM, true, r));
        if (Support.power)
            card.addView(Ui.toggle(c, getString(R.string.power), Prefs.POWER, true, r));
        card.addView(Ui.toggle(c, getString(R.string.batt_temp), Prefs.BATT, true, r));
        return card;
    }

    private View windowCard(Context c) {
        LinearLayout card = Ui.card(c, getString(R.string.sec_window));
        Runnable r = () -> FloatingWindow.reload(this);
        card.addView(Ui.slider(c, getString(R.string.refresh_interval), Prefs.DELAY,
                250, 3000, 250, Prefs.DELAY_DEF, "ms", r));
        card.addView(Ui.slider(c, getString(R.string.ui_scale), Prefs.SCALE,
                70, 150, 5, Prefs.SCALE_DEF, "%", r));
        card.addView(Ui.slider(c, getString(R.string.opacity), Prefs.ALPHA,
                20, 100, 5, Prefs.ALPHA_DEF, "%", r));
        if (Support.power)
            card.addView(Ui.toggle(c, getString(R.string.reverse_current), Prefs.REVERSE_CURRENT, false, r));
        return card;
    }

    private View advancedCard(Context c) {
        LinearLayout card = Ui.card(c, getString(R.string.sec_advanced));
        Runnable r = () -> FloatingWindow.reload(this);
        if (Support.minCpuBw)
            card.addView(Ui.toggle(c, getString(R.string.bw_min_cpu), Prefs.MINCPUBW, false, r));
        if (Support.cpuBw)
            card.addView(Ui.toggle(c, getString(R.string.bw_cpu), Prefs.CPUBW, false, r));
        if (Support.gpuBw)
            card.addView(Ui.toggle(c, getString(R.string.bw_gpu), Prefs.GPUBW, false, r));
        if (Support.llcBw)
            card.addView(Ui.toggle(c, getString(R.string.bw_llc), Prefs.LLCBW, false, r));
        if (Support.m4m)
            card.addView(Ui.toggle(c, getString(R.string.m4m), Prefs.M4M, false, r));

        Button se = Ui.button(c, getString(R.string.permissive_selinux), Theme.SURFACE_HI, Theme.TEXT);
        LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, 6);
        se.setLayoutParams(lp);
        se.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setMessage(R.string.permissive_warn)
                .setPositiveButton(R.string.ok, (d, w) -> setEnforce(0))
                .setNegativeButton(R.string.cancel, null)
                .show());
        se.setOnLongClickListener(v -> {
            setEnforce(1);
            return true;
        });
        card.addView(se);
        TextView hint = Ui.text(c, getString(R.string.permissive_hint), 12, Theme.MUTED);
        hint.setPadding(0, Ui.dp(c, 4), 0, 0);
        card.addView(hint);
        return card;
    }

    private View compatCard(Context c) {
        LinearLayout card = Ui.card(c, getString(R.string.sec_compat));

        String[] names = {getString(R.string.cpu_freq), getString(R.string.cpu_load),
                "GPU " + "MHz", "GPU %", getString(R.string.cpu_temp), getString(R.string.ram),
                getString(R.string.power), getString(R.string.batt_temp)};
        boolean[] ok = {Support.cpuFreq, Support.cpuLoad, Support.gpuFreq, Support.gpuLoad,
                Support.temp, true, Support.power, true};
        for (int i = 0; i < names.length; i += 2) {
            LinearLayout line = new LinearLayout(c);
            line.addView(chip(c, names[i], ok[i]), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            line.addView(chip(c, names[i + 1], ok[i + 1]), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            card.addView(line);
        }

        // FPS kaynağı
        int srcRes = Support.fpsMode == FpsMeter.MODE_APP ? R.string.fps_src_app
                : Support.fpsMode == FpsMeter.MODE_PANEL ? R.string.fps_src_panel : R.string.fps_src_hz;
        LinearLayout fpsLine = Ui.row(c);
        fpsLine.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 4));
        fpsLine.addView(Ui.text(c, getString(R.string.fps_src), 14, Theme.TEXT),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        fpsLine.addView(Ui.text(c, getString(srcRes), 14,
                Support.fpsMode == FpsMeter.MODE_APP ? Theme.ACCENT : Theme.WARN));
        card.addView(fpsLine);

        if (!Support.dump) {
            Button copy = Ui.button(c, getString(R.string.grant_dump), Theme.SURFACE_HI, Theme.TEXT);
            copy.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("pm", "adb shell " + GRANT_CMD));
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
            });
            Button root = Ui.button(c, getString(R.string.grant_root), Theme.SURFACE_HI, Theme.ACCENT);
            LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(c, 6);
            root.setLayoutParams(lp);
            root.setOnClickListener(v -> grantWithRoot());
            card.addView(copy);
            card.addView(root);
        }
        return card;
    }

    private View aboutCard(Context c) {
        LinearLayout card = Ui.card(c, getString(R.string.sec_about));

        card.addView(Ui.text(c, getString(R.string.about_origin), 14, Theme.TEXT));
        TextView lic = Ui.text(c, getString(R.string.about_license), 13, Theme.MUTED);
        lic.setPadding(0, Ui.dp(c, 4), 0, 0);
        card.addView(lic);

        card.addView(link(c, getString(R.string.about_github), "https://github.com/libxzr/PerfMon-Plus", ""));
        card.addView(link(c, getString(R.string.about_translator),
                "https://www.coolapk.com/apk/xzr.perfmon", getString(R.string.about_translated)));

        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        TextView v = Ui.text(c, getString(R.string.about_version, ver), 12, Theme.MUTED);
        v.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 4));
        card.addView(v);
        return card;
    }

    // ---------------------------------------------------------------- yardımcılar

    private View chip(Context c, String label, boolean ok) {
        LinearLayout l = Ui.row(c);
        l.setPadding(0, Ui.dp(c, 5), 0, Ui.dp(c, 5));
        TextView dot = Ui.text(c, ok ? "●" : "○", 12, ok ? Theme.ACCENT : Theme.BAD);
        dot.setPadding(0, 0, Ui.dp(c, 8), 0);
        l.addView(dot);
        l.addView(Ui.text(c, label, 13, ok ? Theme.TEXT : Theme.MUTED));
        return l;
    }

    private TextView link(Context c, String label, String url, String prefix) {
        SpannableString s = new SpannableString(prefix + label);
        s.setSpan(new URLSpan(url), prefix.length(), s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView t = Ui.text(c, s, 14, Theme.TEXT);
        t.setLinkTextColor(Theme.ACCENT);
        t.setMovementMethod(LinkMovementMethod.getInstance());
        t.setPadding(0, Ui.dp(c, 8), 0, 0);
        return t;
    }

    private void toggleMonitor() {
        if (FloatingWindow.running) {
            stopService(new Intent(this, FloatingWindow.class));
        } else if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_needed, Toast.LENGTH_SHORT).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } else {
            Intent i = new Intent(this, FloatingWindow.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            handler.postDelayed(() -> moveTaskToBack(true), 250);
        }
        handler.postDelayed(this::build, 400);
    }

    private void setEnforce(int mode) {
        new Thread(() -> {
            String msg;
            try {
                Process p = new ProcessBuilder("su").redirectErrorStream(true).start();
                OutputStreamWriter w = new OutputStreamWriter(p.getOutputStream());
                w.write("setenforce " + mode + "\nexit\n");
                w.flush();
                StringBuilder log = new StringBuilder();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String l;
                while ((l = r.readLine()) != null) log.append(l).append('\n');
                msg = log.length() == 0
                        ? getString(mode == 0 ? R.string.permissive_done : R.string.enforce_done)
                        : log.toString().trim();
            } catch (Exception e) {
                msg = getString(R.string.permission_denied);
            }
            final String m = msg;
            runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show());
            refreshAsync();
        }).start();
    }

    private void grantWithRoot() {
        new Thread(() -> {
            String msg;
            try {
                Process p = new ProcessBuilder("su", "-c", GRANT_CMD).redirectErrorStream(true).start();
                p.waitFor();
                msg = getString(R.string.granted);
            } catch (Exception e) {
                msg = getString(R.string.permission_denied);
            }
            final String m = msg;
            runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show());
            refreshAsync();
        }).start();
    }
}
