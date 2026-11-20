package xzr.perfmon;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.content.pm.PackageManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Switch;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

public class MainActivity extends Activity {
    private ScrollView scroll;

    private TextView statePill, sourceValue;
    private Button startStop;
    private Switch lockSwitch;
    private PositionMap map;
    private boolean editLandscape, resumed, syncingLock;
    private int probeGeneration;
    private boolean startingAfterNotification;
    private static boolean waitingForWindow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.init(this);
        ShizukuBridge.init(this);
        android.graphics.Point area = OverlayPosition.area(this);
        editLandscape = savedInstanceState == null ? area.x > area.y : savedInstanceState.getBoolean("editLandscape");
        startingAfterNotification = savedInstanceState != null && savedInstanceState.getBoolean("starting");
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Theme.BG);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        FloatingWindow.onChange = this::refreshControls;
        ShizukuBridge.onChange = () -> runOnUiThread(() -> { if (resumed) build(); });
        ShizukuBridge.tryBind();
        refreshControls();
        refreshAsync();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("editLandscape", editLandscape);
        out.putBoolean("starting", startingAfterNotification);
    }

    @Override protected void onPause() {
        resumed = false;
        probeGeneration++;
        ShizukuBridge.onChange = null;
        FloatingWindow.onChange = null;
        super.onPause();
    }

    private void refreshAsync() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(this::refreshAsync);
            return;
        }
        if (!resumed) return;
        int generation = ++probeGeneration;
        new Thread(() -> {
            Support.probe(getApplicationContext());
            runOnUiThread(() -> { if (resumed && generation == probeGeneration) build(); });
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
        root.addView(positionCard(c));
        root.addView(Ui.collapsible(c, (LinearLayout) monitorCard(c), "expand_monitor", true));
        root.addView(Ui.collapsible(c, (LinearLayout) windowCard(c), "expand_window", false));
        root.addView(Ui.collapsible(c, (LinearLayout) advancedCard(c), "expand_advanced", false));
        root.addView(compatCard(c));
        root.addView(aboutCard(c));

        int y = scroll.getScrollY();
        scroll.removeAllViews();
        scroll.addView(root);
        scroll.post(() -> scroll.scrollTo(0, y));
        refreshControls();
    }

    private void refreshControls() {
        boolean on = FloatingWindow.running;
        if (waitingForWindow && (on || FloatingWindow.startError != 0)) {
            waitingForWindow = false;
            if (on && resumed) moveTaskToBack(true);
        }
        if (startStop == null) return;
        startStop.setEnabled(!waitingForWindow);
        statePill.setText(on ? R.string.state_on : R.string.state_off);
        statePill.setTextColor(on ? Theme.BG : Theme.MUTED);
        statePill.setBackground(Ui.round(this, on ? Theme.ACCENT : Theme.SURFACE_HI, 20));
        startStop.setText(on ? R.string.stop : R.string.start);
        startStop.setTextColor(on ? Theme.TEXT : Theme.BG);
        startStop.setBackground(Ui.round(this, on ? Theme.SURFACE_HI : Theme.ACCENT, 12));
        if (lockSwitch != null) {
            syncingLock = true;
            lockSwitch.setChecked(Prefs.on(Prefs.LOCK, false));
            syncingLock = false;
        }
        if (map != null) map.refresh();
        if (sourceValue != null) {
            FpsMeter.Reading r = FloatingWindow.frames.current;
            sourceValue.setText(r != FpsMeter.Reading.IDLE && r.mode == FpsMeter.MODE_NONE
                    ? R.string.fps_no_source : sourceLabel(r.mode));
        }
    }

    private void onSettingChanged() { FloatingWindow.reload(this); }

    // ------------------------------------------------------------------ bölümler

    private View header(Context c) {
        LinearLayout row = Ui.row(c);
        TextView title = Ui.text(c, getString(R.string.app_name), 26, Theme.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        boolean on = FloatingWindow.running;
        TextView pill = Ui.text(c, getString(on ? R.string.state_on : R.string.state_off), 12,
                on ? Theme.BG : Theme.MUTED);
        pill.setPadding(Ui.dp(c, 12), Ui.dp(c, 5), Ui.dp(c, 12), Ui.dp(c, 5));
        pill.setBackground(Ui.round(c, on ? Theme.ACCENT : Theme.SURFACE_HI, 20));
        row.addView(pill);
        statePill = pill;

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
        startStop = b;
        b.setOnClickListener(v -> toggleMonitor());
        return b;
    }

    private View positionCard(Context c) {
        LinearLayout card = Ui.card(c, getString(R.string.position));
        LinearLayout picker = Ui.row(c);
        Button portrait = Ui.button(c, getString(R.string.portrait), Theme.SURFACE_HI, Theme.TEXT);
        Button landscape = Ui.button(c, getString(R.string.landscape), Theme.SURFACE_HI, Theme.TEXT);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, Ui.dp(c, 40), 1);
        left.rightMargin = Ui.dp(c, 6);
        picker.addView(portrait, left);
        picker.addView(landscape, new LinearLayout.LayoutParams(0, Ui.dp(c, 40), 1));
        card.addView(picker);
        map = new PositionMap(c, editLandscape);
        card.addView(map, new LinearLayout.LayoutParams(-1, Ui.dp(c, 145)));
        Runnable select = () -> {
            modeStyle(portrait, !editLandscape);
            modeStyle(landscape, editLandscape);
            map.select(editLandscape);
        };
        portrait.setOnClickListener(v -> { editLandscape = false; select.run(); });
        landscape.setOnClickListener(v -> { editLandscape = true; select.run(); });
        select.run();
        lockSwitch = Ui.toggle(c, getString(R.string.lock_pos), Prefs.LOCK, false, () -> {
            if (!syncingLock) FloatingWindow.lockChanged();
        });
        card.addView(lockSwitch);
        return card;
    }

    private void modeStyle(Button button, boolean selected) {
        button.setSelected(selected);
        button.setTextColor(selected ? Theme.BG : Theme.TEXT);
        button.setBackground(Ui.round(this, selected ? Theme.ACCENT : Theme.SURFACE_HI, 10));
    }

    private View monitorCard(Context c) {
        LinearLayout card = Ui.card(c, getString(R.string.sec_monitor));
        Runnable r = this::onSettingChanged;

        Support support = Support.get();
        card.addView(Ui.toggle(c, getString(R.string.fps), Prefs.FPS, true, r));
        if (support.cpuFreq || support.cpuLoad) {
            LinearLayout cpu = new LinearLayout(c);
            cpu.setOrientation(LinearLayout.VERTICAL);
            cpu.addView(Ui.text(c, "CPU", 13, Theme.MUTED));
            java.util.List<View> cpuMetrics = new java.util.ArrayList<>();
            if (support.cpuFreq)
                cpuMetrics.add(Ui.toggle(c, getString(R.string.cpu_freq), Prefs.CPUFREQ, true, r));
            if (support.cpuLoad)
                cpuMetrics.add(Ui.toggle(c, getString(R.string.cpu_load), Prefs.CPULOAD, true, r));
            cpu.addView(Ui.grid(c, cpuMetrics, 2));
            if (CpuTopology.get().clusters.length > 1) {
                Switch group = Ui.toggle(c, getString(R.string.group_clusters), Prefs.GROUP, true, r);
                group.setPadding(Ui.dp(c, 16), 0, 0, 0);
                cpu.addView(group);
            }
            card.addView(cpu);
        }

        java.util.List<View> items = new java.util.ArrayList<>();
        if (support.gpuFreq || support.gpuLoad)
            items.add(Ui.toggle(c, getString(R.string.gpu), Prefs.GPU, true, r));
        if (support.temp)
            items.add(Ui.toggle(c, getString(R.string.cpu_temp), Prefs.TEMP, true, r));
        items.add(Ui.toggle(c, getString(R.string.ram), Prefs.MEM, true, r));
        items.add(Ui.toggle(c, getString(R.string.batt_temp), Prefs.BATT, true, r));
        card.addView(Ui.grid(c, items, 2));
        if (support.power) {
            card.addView(Ui.toggle(c, getString(R.string.power), Prefs.POWER, true, r));
            card.addView(Ui.toggle(c, getString(R.string.power_watts), Prefs.POWER_WATTS, true, r));
        }
        return card;
    }

    private View windowCard(Context c) {
        LinearLayout card = Ui.card(c, getString(R.string.sec_window));
        Runnable r = this::onSettingChanged;
        card.addView(Ui.slider(c, getString(R.string.refresh_interval), Prefs.DELAY,
                250, 3000, 250, Prefs.DELAY_DEF, "ms", null, r));
        card.addView(Ui.slider(c, getString(R.string.ui_scale), Prefs.SCALE,
                70, 150, 5, Prefs.SCALE_DEF, "%", value -> FloatingWindow.appearance(Prefs.SCALE, value), null));
        card.addView(Ui.slider(c, getString(R.string.opacity), Prefs.ALPHA,
                20, 100, 5, Prefs.ALPHA_DEF, "%", value -> FloatingWindow.appearance(Prefs.ALPHA, value), null));
        if (Support.get().power)
            card.addView(Ui.toggle(c, getString(R.string.reverse_current), Prefs.REVERSE_CURRENT, false, r));
        return card;
    }

    private View advancedCard(Context c) {
        LinearLayout card = Ui.card(c, getString(R.string.sec_advanced));
        Runnable r = this::onSettingChanged;
        Support support = Support.get();
        if (support.minCpuBw)
            card.addView(Ui.toggle(c, getString(R.string.bw_min_cpu), Prefs.MINCPUBW, false, r));
        if (support.cpuBw)
            card.addView(Ui.toggle(c, getString(R.string.bw_cpu), Prefs.CPUBW, false, r));
        if (support.gpuBw)
            card.addView(Ui.toggle(c, getString(R.string.bw_gpu), Prefs.GPUBW, false, r));
        if (support.llcBw)
            card.addView(Ui.toggle(c, getString(R.string.bw_llc), Prefs.LLCBW, false, r));
        if (support.m4m)
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
                "GPU MHz", "GPU %", getString(R.string.cpu_temp), getString(R.string.ram),
                getString(R.string.power), getString(R.string.batt_temp)};
        Support support = Support.get();
        boolean[] ok = {support.cpuFreq, support.cpuLoad, support.gpuFreq, support.gpuLoad,
                support.temp, true, support.power, true};
        for (int i = 0; i < names.length; i += 2) {
            LinearLayout line = new LinearLayout(c);
            line.addView(chip(c, names[i], ok[i]), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            line.addView(chip(c, names[i + 1], ok[i + 1]), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            card.addView(line);
        }

        LinearLayout sourceRow = Ui.row(c);
        sourceRow.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 4));
        sourceRow.addView(Ui.text(c, getString(R.string.fps_src), 14, Theme.TEXT),
                new LinearLayout.LayoutParams(0, -2, 1));
        sourceValue = Ui.text(c, getString(R.string.idle_source), 14, Theme.ACCENT);
        sourceRow.addView(sourceValue);
        TextView arrow = Ui.text(c, "›", 18, Theme.MUTED);
        arrow.setPadding(Ui.dp(c, 8), 0, 0, 0);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        sourceRow.addView(arrow);
        sourceRow.setMinimumHeight(Ui.dp(c, 48));
        sourceRow.setFocusable(true);
        sourceRow.setOnClickListener(v -> showFrameDetails());
        card.addView(sourceRow);

        // Shizuku
        int st = ShizukuBridge.state();
        int stRes = st == ShizukuBridge.STATE_READY ? R.string.shz_on
                : st == ShizukuBridge.STATE_CONNECTING ? R.string.shz_wait
                : st == ShizukuBridge.STATE_DISCONNECTED ? R.string.shz_disconnected
                : st == ShizukuBridge.STATE_NEED_PERMISSION ? R.string.shz_need : R.string.shz_off;
        card.addView(infoRow(c, getString(R.string.shizuku), getString(stRes),
                st == ShizukuBridge.STATE_READY ? Theme.ACCENT : Theme.WARN));

        if (st == ShizukuBridge.STATE_NEED_PERMISSION) {
            Button ask = Ui.button(c, getString(R.string.shz_request), Theme.SURFACE_HI, Theme.ACCENT);
            ask.setOnClickListener(v -> ShizukuBridge.requestPermission());
            card.addView(ask);
        } else if (st == ShizukuBridge.STATE_DISCONNECTED) {
            Button retry = Ui.button(c, getString(R.string.shz_retry), Theme.SURFACE_HI, Theme.ACCENT);
            retry.setOnClickListener(v -> ShizukuBridge.tryBind());
            card.addView(retry);
        } else if (st == ShizukuBridge.STATE_OFF) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(ShizukuBridge.SHIZUKU_PACKAGE);
            if (launch != null) {
                Button open = Ui.button(c, getString(R.string.shz_open), Theme.SURFACE_HI, Theme.ACCENT);
                open.setOnClickListener(v -> startActivity(launch));
                card.addView(open);
            }
        }
        return card;
    }

    private int sourceLabel(int mode) {
        switch (mode) {
            case FpsMeter.MODE_SHIZUKU: return R.string.fps_src_shizuku;
            case FpsMeter.MODE_DUMP: return R.string.fps_src_app;
            case FpsMeter.MODE_PANEL: return R.string.fps_src_panel;
            case FpsMeter.MODE_HZ: return R.string.fps_src_hz;
            default: return R.string.idle_source;
        }
    }

    private String sourceDetail(FpsMeter.Reading r) {
        if (r == FpsMeter.Reading.IDLE) {
            if (!FloatingWindow.running) return getString(R.string.state_off);
            return getString(Prefs.on(Prefs.FPS, true) ? R.string.fps_waiting : R.string.fps_disabled);
        }
        String name = getString(r.mode == FpsMeter.MODE_NONE ? R.string.fps_no_source : sourceLabel(r.mode));
        return r.reason == 0 ? name : name + " · " + getString(r.reason);
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private String age(long time) {
        long seconds = Math.max(0, android.os.SystemClock.elapsedRealtime() - time) / 1000;
        if (seconds < 60) return getResources().getQuantityString(R.plurals.fps_seconds_ago, (int) seconds, seconds);
        if (seconds < 3600) return getResources().getQuantityString(R.plurals.fps_minutes_ago, (int) (seconds / 60), seconds / 60);
        return getResources().getQuantityString(R.plurals.fps_hours_ago, (int) (seconds / 3600), seconds / 3600);
    }

    private void showFrameDetails() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.fps_src).setMessage(frameDetails(FloatingWindow.frames))
                .setPositiveButton(R.string.ok, null).show();
    }

    CharSequence frameDetails(FrameHistory h) {
        StringBuilder message = new StringBuilder(sourceDetail(h.current));
        FpsMeter.Reading fallback = h.lastFallback;
        if (fallback != null && fallback != h.current) {
            message.append("\n\n").append(getString(R.string.fps_last_outside)).append("\n");
            if (fallback.pkg != null) message.append(appLabel(fallback.pkg)).append(" · ");
            message.append(sourceDetail(fallback)).append(" · ").append(age(fallback.time));
        }
        message.append("\n\n").append(getString(R.string.fps_last_app)).append("\n");
        if (h.lastApp == null) {
            message.append(getString(R.string.fps_no_history));
        } else {
            message.append(appLabel(h.lastApp.pkg)).append(" · ")
                    .append(h.lastApp.mode == FpsMeter.MODE_SHIZUKU ? "Shizuku" : "DUMP")
                    .append(" · ").append(age(h.lastApp.time));
        }
        return message;
    }

    private View aboutCard(Context c) {
        LinearLayout card = Ui.card(c, getString(R.string.sec_about));

        card.addView(Ui.text(c, getString(R.string.about_origin), 14, Theme.TEXT));
        TextView lic = Ui.text(c, getString(R.string.about_license), 13, Theme.MUTED);
        lic.setPadding(0, Ui.dp(c, 4), 0, 0);
        card.addView(lic);

        card.addView(link(c, getString(R.string.about_github), "https://github.com/libxzr/PerfMon-Plus", ""));
        card.addView(link(c, getString(R.string.developer_handle),
                "https://t.me/al1deburdanyak", getString(R.string.about_developer)));

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

    private View infoRow(Context c, String label, String value, int valueColor) {
        LinearLayout row = Ui.row(c);
        row.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 4));
        row.addView(Ui.text(c, label, 14, Theme.TEXT),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(Ui.text(c, value, 14, valueColor));
        return row;
    }

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
        } else if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED && !Prefs.on("notification_asked", false)) {
            startingAfterNotification = true;
            Prefs.sp.edit().putBoolean("notification_asked", true).apply();
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 11);
        } else {
            startMonitor();
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 11 && startingAfterNotification) {
            startingAfterNotification = false;
            if (Settings.canDrawOverlays(this) && !FloatingWindow.running) startMonitor();
        }
    }

    private void startMonitor() {
        Intent intent = new Intent(this, FloatingWindow.class);
        waitingForWindow = true;
        FloatingWindow.startError = 0;
        refreshControls();
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
        } catch (RuntimeException e) {
            waitingForWindow = false;
            refreshControls();
            Toast.makeText(this, R.string.window_open_error, Toast.LENGTH_LONG).show();
        }
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
}
