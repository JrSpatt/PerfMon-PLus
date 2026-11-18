package xzr.perfmon;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

public class MainActivity extends Activity {
    private ScrollView scroll;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Önizleme ve konum seçici
    private FrameLayout stage, phone;
    private View posChip;
    private OverlayView previewView;
    private Monitor previewMonitor;
    private int chipW, chipH;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.init(this);
        ShizukuBridge.init(this);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Theme.BG);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ShizukuBridge.onChange = this::refreshAsync;
        refreshAsync();
    }

    @Override
    protected void onPause() {
        ShizukuBridge.onChange = null;
        stopPreview();
        super.onPause();
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
        stopPreview();

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        root.addView(header(c));
        root.addView(startButton(c));
        root.addView(previewCard(c));
        root.addView(monitorCard(c));
        root.addView(windowCard(c));
        root.addView(advancedCard(c));
        root.addView(compatCard(c));
        root.addView(aboutCard(c));

        int y = scroll.getScrollY();
        scroll.removeAllViews();
        scroll.addView(root);
        scroll.post(() -> scroll.scrollTo(0, y));
        startPreview();
    }

    /** Ayar değişince çalışan monitörü yeniler ve önizlemeyi tazeler. */
    private void onSettingChanged() {
        FloatingWindow.reload(this);
        startPreview();
    }

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

    /** Canlı önizleme (gerçek veriyle, gerçek boyutta) ve sürüklenebilir konum haritası. */
    private View previewCard(Context c) {
        LinearLayout card = Ui.card(c, getString(R.string.sec_preview));

        stage = new FrameLayout(c);
        stage.setBackground(Ui.round(c, 0xFF0B0D10, 12));
        int sp = Ui.dp(c, 10);
        stage.setPadding(sp, sp, sp, sp);
        card.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        DisplayMetrics dm = getResources().getDisplayMetrics();
        final int pw = Ui.dp(c, 110);
        final int ph = Math.round(pw * (float) dm.heightPixels / dm.widthPixels);
        final float ratio = pw / (float) dm.widthPixels;

        phone = new FrameLayout(c);
        GradientDrawable frame = Ui.round(c, Theme.BG, 14);
        frame.setStroke(Ui.dp(c, 2), Theme.SURFACE_HI);
        phone.setBackground(frame);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(pw, ph);
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.topMargin = Ui.dp(c, 14);
        card.addView(phone, plp);

        posChip = new View(c);
        posChip.setBackground(Ui.round(c, 0xCC4CD08A, 3));
        phone.addView(posChip, new FrameLayout.LayoutParams(Ui.dp(c, 30), Ui.dp(c, 20)));

        phone.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) v.getParent().requestDisallowInterceptTouchEvent(true);
            if (a != MotionEvent.ACTION_DOWN && a != MotionEvent.ACTION_MOVE && a != MotionEvent.ACTION_UP)
                return false;
            float cx = Math.max(0, Math.min(e.getX() - chipW / 2f, pw - chipW));
            float cy = Math.max(0, Math.min(e.getY() - chipH / 2f, ph - chipH));
            posChip.setX(cx);
            posChip.setY(cy);
            if (a == MotionEvent.ACTION_UP) {
                Prefs.sp.edit().putInt(Prefs.POS_X, Math.round(cx / ratio))
                        .putInt(Prefs.POS_Y, Math.round(cy / ratio)).apply();
                FloatingWindow.reload(this);
            }
            return true;
        });

        TextView hint = Ui.text(c, getString(R.string.drag_hint), 12, Theme.MUTED);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        hint.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 4));
        card.addView(hint);
        return card;
    }

    private void startPreview() {
        if (stage == null || isFinishing() || isDestroyed()) return;
        stopPreview();
        stage.removeAllViews();
        previewView = new OverlayView(this);
        stage.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final OverlayView pv = previewView;
        previewMonitor = new Monitor(this, s -> pv.update(s));
        previewMonitor.start();
        placeChip();
    }

    private void stopPreview() {
        if (previewMonitor != null) {
            previewMonitor.stop();
            previewMonitor = null;
        }
    }

    /** Konum kutusunun boyutunu önizlemeye, yerini kayıtlı konuma göre ayarlar. */
    private void placeChip() {
        if (previewView == null || phone == null || posChip == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        previewView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        final int pw = Ui.dp(this, 110);
        final int ph = Math.round(pw * (float) dm.heightPixels / dm.widthPixels);
        final float ratio = pw / (float) dm.widthPixels;
        chipW = Math.min(pw, Math.max(Ui.dp(this, 14), Math.round(previewView.getMeasuredWidth() * ratio)));
        chipH = Math.min(ph, Math.max(Ui.dp(this, 10), Math.round(previewView.getMeasuredHeight() * ratio)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(chipW, chipH);
        posChip.setLayoutParams(lp);
        int x = Prefs.sp.getInt(Prefs.POS_X, Ui.dp(this, 16));
        int y = Prefs.sp.getInt(Prefs.POS_Y, Ui.dp(this, 80));
        posChip.setX(Math.max(0, Math.min(x * ratio, pw - chipW)));
        posChip.setY(Math.max(0, Math.min(y * ratio, ph - chipH)));
    }

    private View monitorCard(Context c) {
        LinearLayout card = Ui.card(c, getString(R.string.sec_monitor));
        Runnable r = this::onSettingChanged;

        java.util.List<View> items = new java.util.ArrayList<>();
        items.add(Ui.toggle(c, getString(R.string.fps), Prefs.FPS, true, r));
        if (Support.cpuFreq)
            items.add(Ui.toggle(c, getString(R.string.cpu_freq), Prefs.CPUFREQ, true, r));
        if (Support.cpuLoad)
            items.add(Ui.toggle(c, getString(R.string.cpu_load), Prefs.CPULOAD, true, r));
        if (Support.cpuFreq || Support.cpuLoad)
            items.add(Ui.toggle(c, getString(R.string.group_clusters), Prefs.GROUP, true, r));
        if (Support.gpuFreq || Support.gpuLoad)
            items.add(Ui.toggle(c, getString(R.string.gpu), Prefs.GPU, true, r));
        if (Support.temp)
            items.add(Ui.toggle(c, getString(R.string.cpu_temp), Prefs.TEMP, true, r));
        items.add(Ui.toggle(c, getString(R.string.ram), Prefs.MEM, true, r));
        if (Support.power)
            items.add(Ui.toggle(c, getString(R.string.power), Prefs.POWER, true, r));
        items.add(Ui.toggle(c, getString(R.string.batt_temp), Prefs.BATT, true, r));
        card.addView(Ui.grid(c, items, 2));
        return card;
    }

    private View windowCard(Context c) {
        LinearLayout card = Ui.card(c, getString(R.string.sec_window));
        Runnable r = this::onSettingChanged;
        card.addView(Ui.slider(c, getString(R.string.refresh_interval), Prefs.DELAY,
                250, 3000, 250, Prefs.DELAY_DEF, "ms", r));
        card.addView(Ui.slider(c, getString(R.string.ui_scale), Prefs.SCALE,
                70, 150, 5, Prefs.SCALE_DEF, "%", r));
        card.addView(Ui.slider(c, getString(R.string.opacity), Prefs.ALPHA,
                20, 100, 5, Prefs.ALPHA_DEF, "%", r));
        card.addView(Ui.toggle(c, getString(R.string.lock_pos), Prefs.LOCK, false, r));
        if (Support.power)
            card.addView(Ui.toggle(c, getString(R.string.reverse_current), Prefs.REVERSE_CURRENT, false, r));
        return card;
    }

    private View advancedCard(Context c) {
        LinearLayout card = Ui.card(c, getString(R.string.sec_advanced));
        Runnable r = this::onSettingChanged;
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
                "GPU MHz", "GPU %", getString(R.string.cpu_temp), getString(R.string.ram),
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
        int srcRes = Support.fpsMode == FpsMeter.MODE_APP
                ? (Support.shizuku ? R.string.fps_src_shizuku : R.string.fps_src_app)
                : Support.fpsMode == FpsMeter.MODE_PANEL ? R.string.fps_src_panel : R.string.fps_src_hz;
        card.addView(infoRow(c, getString(R.string.fps_src), getString(srcRes),
                Support.fpsMode == FpsMeter.MODE_APP ? Theme.ACCENT : Theme.WARN));

        if (Support.fpsMode == FpsMeter.MODE_APP) {
            String dl = FpsMeter.debugLayer;
            TextView layerInfo = Ui.text(c, dl == null ? "—" : dl, 11, Theme.MUTED);
            layerInfo.setPadding(0, 0, 0, Ui.dp(c, 4));
            card.addView(layerInfo);
        }

        // Shizuku
        int st = ShizukuBridge.state();
        int stRes = st == ShizukuBridge.STATE_READY ? R.string.shz_on
                : st == ShizukuBridge.STATE_CONNECTING ? R.string.shz_wait
                : st == ShizukuBridge.STATE_NEED_PERMISSION ? R.string.shz_need : R.string.shz_off;
        card.addView(infoRow(c, getString(R.string.shizuku), getString(stRes),
                st == ShizukuBridge.STATE_READY ? Theme.ACCENT : Theme.WARN));

        if (st == ShizukuBridge.STATE_NEED_PERMISSION) {
            Button ask = Ui.button(c, getString(R.string.shz_request), Theme.SURFACE_HI, Theme.ACCENT);
            ask.setOnClickListener(v -> ShizukuBridge.requestPermission());
            card.addView(ask);
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
}
