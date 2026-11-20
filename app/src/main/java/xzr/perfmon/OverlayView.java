package xzr.perfmon;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ImageButton;
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
        final Bind bind, labelBind;

        Row(TextView label, TextView value, Bind bind, Bind labelBind) {
            this.label = label;
            this.value = value;
            this.bind = bind;
            this.labelBind = labelBind;
        }
    }

    final ImageButton pin, close;
    private final List<Row> rows = new ArrayList<>();
    private final TextView title;
    private final GradientDrawable background = new GradientDrawable();
    private final Support support = Support.get();
    private final boolean showFreq, showLoad;
    private float sp;

    OverlayView(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setBackground(background);
        showFreq = Prefs.on(Prefs.CPUFREQ, true) && support.cpuFreq;
        showLoad = Prefs.on(Prefs.CPULOAD, true) && support.cpuLoad;
        LinearLayout head = Ui.row(c);
        title = Ui.text(c, "PerfMon+", 11, Theme.ACCENT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setIncludeFontPadding(false);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        pin = action(c, R.drawable.ic_pin, R.string.pin_action);
        close = action(c, R.drawable.ic_close, R.string.close_action);
        head.addView(pin);
        head.addView(close);
        addView(head);
        sp = 11;
        buildRows(c);
        setLocked(Prefs.on(Prefs.LOCK, false));
        setScalePercent(Prefs.num(Prefs.SCALE, Prefs.SCALE_DEF));
    }

    private ImageButton action(Context c, int image, int description) {
        ImageButton button = new ImageButton(c);
        button.setImageResource(image);
        button.setContentDescription(c.getString(description));
        android.util.TypedValue ripple = new android.util.TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        button.setBackgroundResource(ripple.resourceId);
        int side = Ui.dp(c, 32);
        button.setLayoutParams(new LinearLayout.LayoutParams(side, side));
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    void setLocked(boolean locked) {
        pin.setVisibility(locked ? GONE : VISIBLE);
        close.setVisibility(locked ? GONE : VISIBLE);
    }

    void setBackgroundOpacity(float alpha) {
        background.setColor(Color.argb(Math.round(255 * alpha), 15, 17, 21));
    }

    void setScalePercent(int percent) {
        Context c = getContext();
        float scale = percent / 100f;
        sp = 11 * scale;
        int side = Ui.dp(c, 6 * scale), vertical = Ui.dp(c, 4 * scale);
        setPadding(side, vertical, side, vertical);
        background.setCornerRadius(Ui.dp(c, 8 * scale));
        title.setTextSize(sp);
        title.setPadding(0, 0, Ui.dp(c, 3 * scale), Ui.dp(c, scale));
        for (Row r : rows) {
            r.label.setTextSize(sp);
            r.value.setTextSize(sp);
            r.label.setPadding(0, 0, Ui.dp(c, 5 * scale), 0);
            r.value.setMinWidth((int) Math.ceil(r.value.getPaint().measureText("000")));
        }
    }

    private void buildRows(Context c) {
        if (Prefs.on(Prefs.FPS, true)) {
            addRow(c, null, s -> s.frame.value < 0 ? "--" : String.format(Locale.US, "%.0f", s.frame.value),
                    s -> s.frame.mode == FpsMeter.MODE_HZ ? "Hz" : "FPS");
        }

        if (showFreq || showLoad) {
            CpuTopology.Data topo = CpuTopology.get();
            boolean group = Prefs.on(Prefs.GROUP, true) && topo.clusters.length > 1;
            if (group) {
                for (int k = 0; k < topo.clusters.length; k++) {
                    final int[] cpus = topo.clusters[k];
                    addRow(c, topo.labels[k], s -> clusterText(s, cpus), null);
                }
            } else {
                for (int i = 0; i < topo.count; i++) {
                    final int[] cpu = {i};
                    addRow(c, "CPU " + i, s -> clusterText(s, cpu), null);
                }
            }
        }

        if (Prefs.on(Prefs.GPU, true) && (support.gpuFreq || support.gpuLoad)) {
            addRow(c, "GPU", s -> metrics(support.gpuFreq, s.gpuFreq, support.gpuLoad, s.gpuLoad), null);
        }

        if (Prefs.on(Prefs.TEMP, true) && support.temp)
            addRow(c, "TEMP", s -> Float.isNaN(s.cpuTemp) ? "--" : String.format(Locale.US, "%.0f °C", s.cpuTemp), null);

        if (Prefs.on(Prefs.MEM, true))
            addRow(c, "RAM", s -> s.memPct < 0 ? "--" : s.memPct + "%", null);

        if (support.power && Prefs.on(Prefs.POWER, true))
            addRow(c, "PWR", s -> s.currentMa == Integer.MIN_VALUE ? "--" : s.currentMa + " mA", null);
        if (support.power && Prefs.on(Prefs.POWER_WATTS, true))
            addRow(c, "PWR W", s -> Float.isNaN(s.powerW) ? "--" : String.format(Locale.US, "%.1f W", s.powerW), null);

        if (Prefs.on(Prefs.BATT, true))
            addRow(c, "BAT", s -> Float.isNaN(s.battTemp) ? "--" : String.format(Locale.US, "%.1f °C", s.battTemp), null);

        if (Prefs.on(Prefs.MINCPUBW, false) && support.minCpuBw)
            addRow(c, "MINBW", s -> metric(s.minCpuBw, ""), null);
        if (Prefs.on(Prefs.CPUBW, false) && support.cpuBw)
            addRow(c, "CPUBW", s -> metric(s.cpuBw, ""), null);
        if (Prefs.on(Prefs.GPUBW, false) && support.gpuBw)
            addRow(c, "GPUBW", s -> metric(s.gpuBw, ""), null);
        if (Prefs.on(Prefs.LLCBW, false) && support.llcBw)
            addRow(c, "LLCBW", s -> metric(s.llcBw, ""), null);
        if (Prefs.on(Prefs.M4M, false) && support.m4m)
            addRow(c, "M4M", s -> metric(s.m4m, " MHz"), null);
    }

    private String clusterText(Snapshot s, int[] cpus) {
        int maxFreq = -1, loadSum = 0, validLoads = 0, online = 0;
        for (int id : cpus) {
            if (id >= s.cpuOnline.length || s.cpuOnline[id] != 1) continue;
            online++;
            maxFreq = Math.max(maxFreq, s.cpuFreq[id]);
            if (s.cpuLoad[id] >= 0) {
                validLoads++;
                loadSum += s.cpuLoad[id];
            }
        }
        if (online == 0) return getContext().getString(R.string.offline);
        return metrics(showFreq, maxFreq, showLoad, validLoads == 0 ? -1 : loadSum / validLoads);
    }

    private static String metrics(boolean showFreq, int freq, boolean showLoad, int load) {
        String f = showFreq ? metric(freq, " MHz") : "";
        if (!showLoad) return f;
        return (showFreq ? f + " " : "") + metric(load, "%");
    }

    private static String metric(long value, String unit) {
        return value < 0 ? "--" : value + unit;
    }

    /** labelBind verilirse etiket her turda ondan hesaplanır (FPS / Hz geçişi için). */
    private void addRow(Context c, String label, Bind valueBind, Bind labelBind) {
        LinearLayout line = new LinearLayout(c);
        TextView l = Ui.text(c, label == null ? "" : label, sp, Theme.MUTED);
        l.setTypeface(Typeface.MONOSPACE);
        l.setIncludeFontPadding(false);
        l.setSingleLine(true);
        TextView v = Ui.text(c, "--", sp, Theme.TEXT);
        v.setIncludeFontPadding(false);
        v.setSingleLine(true);
        v.setTypeface(Typeface.MONOSPACE);
        v.setGravity(Gravity.END);
        line.addView(l);
        line.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        addView(line);
        rows.add(new Row(l, v, valueBind, labelBind));
    }

    void update(Snapshot s) {
        for (Row row : rows) {
            if (row.labelBind != null) row.label.setText(row.labelBind.get(s));
            row.value.setText(row.bind.get(s));
        }
    }
}
