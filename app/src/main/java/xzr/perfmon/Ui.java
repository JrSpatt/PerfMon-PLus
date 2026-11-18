package xzr.perfmon;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** Kodla arayüz kurmak için küçük yardımcılar. */
final class Ui {
    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout card(Context c, CharSequence title) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 16);
        l.setPadding(p, dp(c, 14), p, dp(c, 10));
        l.setBackground(round(c, Theme.SURFACE, 16));
        LinearLayout.LayoutParams lp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        l.setLayoutParams(lp);
        TextView t = text(c, title, 12, Theme.ACCENT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, 0, 0, dp(c, 6));
        l.addView(t);
        return l;
    }

    static Switch toggle(Context c, CharSequence label, String key, boolean def, Runnable changed) {
        Switch s = new Switch(c);
        s.setText(label);
        s.setTextColor(Theme.TEXT);
        s.setTextSize(15);
        s.setMinHeight(dp(c, 46));
        s.setChecked(Prefs.on(key, def));
        s.setOnCheckedChangeListener((b, on) -> {
            Prefs.sp.edit().putBoolean(key, on).apply();
            if (changed != null) changed.run();
        });
        return s;
    }

    /** Etiket + değer satırı ve altında kaydırıcı. Değer Prefs'e tam sayı olarak yazılır. */
    static LinearLayout slider(Context c, CharSequence label, String key, int min, int max, int step,
                               int def, String unit, Runnable changed) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(c, 6), 0, dp(c, 4));

        LinearLayout head = new LinearLayout(c);
        TextView name = text(c, label, 15, Theme.TEXT);
        final TextView value = text(c, "", 14, Theme.MUTED);
        head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(value);
        box.addView(head);

        final int cur = Prefs.num(key, def);
        value.setText(cur + " " + unit);
        SeekBar bar = new SeekBar(c);
        bar.setMax((max - min) / step);
        bar.setProgress((cur - min) / step);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                value.setText((min + p * step) + " " + unit);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                Prefs.sp.edit().putInt(key, min + sb.getProgress() * step).apply();
                if (changed != null) changed.run();
            }
        });
        box.addView(bar);
        return box;
    }

    static Button button(Context c, CharSequence label, int bg, int fg) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        b.setBackground(round(c, bg, 12));
        b.setMinHeight(dp(c, 46));
        return b;
    }

    static View space(Context c, int heightDp) {
        View v = new View(c);
        v.setLayoutParams(lp(1, dp(c, heightDp)));
        return v;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }
}
