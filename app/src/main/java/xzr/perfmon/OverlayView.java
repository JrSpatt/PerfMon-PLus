package xzr.perfmon;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Ekranda süzülen monitör penceresinin içeriği. */
final class OverlayView extends LinearLayout {
    private interface Bind {
        String get(Snapshot s);
    }

    private static final class Row {
        final TextView label, value;
        final Bind bind;

        Row(TextView label, TextView value, Bind bind) {
            this.label = label;
            this.value = value;
            this.bind = bind;
        }
    }

    final TextView close;
    private final List<Row> rows = new ArrayList<>();
    private final float sp;
    private final boolean showFreq, showLoad;

    OverlayView(Context c) {
        super(c);
        setOrientation(VERTICAL);
        int pad = Ui.dp(c, 10);
        setPadding(pad, Ui.dp(c, 6), pad, pad);

        float scale = Prefs.num(Prefs.SCALE, Prefs.SCALE_DEF) / 100f;
        sp = 11 * scale;
        int alpha = Math.round(Prefs.num(Prefs.ALPHA, Prefs.ALPHA_DEF) * 2.55f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(alpha, 15, 17, 21));
        bg.setCornerRadius(Ui.dp(c, 12));
        bg.setStroke(1, 0x33FFFFFF);
        setBackground(bg);

        showFreq = Prefs.on(Prefs.CPUFREQ, true) && Support.cpuFreq;
        showLoad = Prefs.on(Prefs.CPULOAD, true) && Support.cpuLoad;

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(c, "PerfMon+", sp, Theme.ACCENT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        close = Ui.text(c, "✕", sp + 3, Theme.MUTED);
        close.setPadding(Ui.dp(c, 14), 0, 0, 0);
        head.addView(close);
        addView(head);

        buildRows(c);
    }

    private void buildRows(Context c) {
        if (Prefs.on(Prefs.FPS, true) && Support.fps) {
            addRow(c, null, s -> s.fps < 0 ? "--" : String.format(Locale.US, "%.0f", s.fps),
                    s -> s.fpsMode == FpsMeter.MODE_HZ ? "Hz" : "FPS");
        }

        if (showFreq || showLoad) {
            boolean group = Prefs.on(Prefs.GROUP, true) && CpuTopology.clusters.length > 1;
            if (group) {
                for (int k = 0; k < CpuTopology.clusters.length; k++) {
                    final int[] cpus = CpuTopology.clusters[k];
                    addRow(c, CpuTopology.labels[k], s -> clusterText(s, cpus), null);
                }
            } else {
                for (int i = 0; i < CpuTopology.count; i++) {
                    final int id = i;
                    addRow(c, "cpu" + i, s -> clusterText(s, new int[]{id}), null);
                }
            }
        }

        if (Prefs.on(Prefs.GPU, true) && (Support.gpuFreq || Support.gpuLoad)) {
            addRow(c, "GPU", s -> {
                StringBuilder b = new StringBuilder();
                if (Support.gpuFreq) b.append(String.format(Locale.US, "%4d MHz", s.gpuFreq));
                if (Support.gpuLoad) b.append(b.length() > 0 ? " " : "").append(String.format(Locale.US, "%3d%%", s.gpuLoad));
                return b.toString();
            }, null);
        }

        if (Prefs.on(Prefs.TEMP, true) && Support.temp)
            addRow(c, "TEMP", s -> Float.isNaN(s.cpuTemp) ? "--" : String.format(Locale.US, "%.0f °C", s.cpuTemp), null);

        if (Prefs.on(Prefs.MEM, true))
            addRow(c, "RAM", s -> s.memPct < 0 ? "--" : s.memPct + "%", null);

        if (Prefs.on(Prefs.POWER, true) && Support.power) {
            addRow(c, "PWR", s -> {
                if (s.currentMa == Integer.MIN_VALUE) return "--";
                String w = Float.isNaN(s.powerW) ? "" : String.format(Locale.US, "%.1f W  ", s.powerW);
                return w + s.currentMa + " mA";
            }, null);
        }

        if (Prefs.on(Prefs.BATT, true))
            addRow(c, "BAT", s -> Float.isNaN(s.battTemp) ? "--" : String.format(Locale.US, "%.1f °C", s.battTemp), null);

        if (Prefs.on(Prefs.MINCPUBW, false) && Support.minCpuBw)
            addRow(c, "MINBW", s -> String.valueOf(s.minCpuBw), null);
        if (Prefs.on(Prefs.CPUBW, false) && Support.cpuBw)
            addRow(c, "CPUBW", s -> String.valueOf(s.cpuBw), null);
        if (Prefs.on(Prefs.GPUBW, false) && Support.gpuBw)
            addRow(c, "GPUBW", s -> String.valueOf(s.gpuBw), null);
        if (Prefs.on(Prefs.LLCBW, false) && Support.llcBw)
            addRow(c, "LLCBW", s -> String.valueOf(s.llcBw), null);
        if (Prefs.on(Prefs.M4M, false) && Support.m4m)
            addRow(c, "M4M", s -> s.m4m + " MHz", null);
    }

    private String clusterText(Snapshot s, int[] cpus) {
        int maxFreq = -1, loadSum = 0, online = 0;
        for (int id : cpus) {
            if (id >= s.cpuOnline.length || s.cpuOnline[id] != 1) continue;
            online++;
            maxFreq = Math.max(maxFreq, s.cpuFreq[id]);
            loadSum += s.cpuLoad[id];
        }
        if (online == 0) return getContext().getString(R.string.offline);
        StringBuilder b = new StringBuilder();
        if (showFreq) b.append(String.format(Locale.US, "%4d MHz", maxFreq));
        if (showLoad) b.append(b.length() > 0 ? " " : "").append(String.format(Locale.US, "%3d%%", loadSum / online));
        return b.toString();
    }

    /** labelBind verilirse etiket her turda ondan hesaplanır (FPS / Hz geçişi için). */
    private void addRow(Context c, String label, Bind valueBind, Bind labelBind) {
        LinearLayout line = new LinearLayout(c);
        TextView l = Ui.text(c, label == null ? "" : label, sp, Theme.MUTED);
        l.setTypeface(Typeface.MONOSPACE);
        l.setMinWidth(Ui.dp(c, label != null && label.length() > 4 ? 52 : 40));
        TextView v = Ui.text(c, "--", sp, Theme.TEXT);
        v.setTypeface(Typeface.MONOSPACE);
        v.setGravity(Gravity.END);
        line.addView(l);
        line.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        addView(line);
        final Row row = new Row(l, v, valueBind);
        rows.add(row);
        if (labelBind != null) {
            rows.add(new Row(l, l, labelBind));
        }
    }

    void update(Snapshot s) {
        for (Row r : rows) r.value.setText(r.bind.get(s));
    }
}
