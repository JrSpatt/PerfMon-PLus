package xzr.perfmon;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    static SharedPreferences sp;

    static final String DELAY = "refreshing_delay";
    static final int DELAY_DEF = 1000;
    static final String SCALE = "ui_scale_pct";
    static final int SCALE_DEF = 100;
    static final String ALPHA = "bg_alpha";
    static final int ALPHA_DEF = 80;
    static final String REVERSE_CURRENT = "reverse_current";
    static final String GROUP = "group_clusters";
    static final String LOCK = "lock_pos";

    static final String CPUFREQ = "show_cpufreq";
    static final String CPULOAD = "show_cpuload";
    static final String GPU = "show_gpu";
    static final String TEMP = "show_thermal";
    static final String MEM = "show_mem";
    static final String POWER = "show_power";
    static final String BATT = "show_battemp";
    static final String FPS = "show_fps";

    static final String MINCPUBW = "show_mincpubw";
    static final String CPUBW = "show_cpubw";
    static final String GPUBW = "show_gpubw";
    static final String LLCBW = "show_llcbw";
    static final String M4M = "show_m4m";

    static void init(Context c) {
        if (sp == null)
            sp = c.getApplicationContext().getSharedPreferences("main", Context.MODE_PRIVATE);
    }

    static boolean on(String key, boolean def) {
        return sp.getBoolean(key, def);
    }

    static int num(String key, int def) {
        return sp.getInt(key, def);
    }
}
