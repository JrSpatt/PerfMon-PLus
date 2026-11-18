package xzr.perfmon;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.view.Display;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FPS kaynakları (en iyiden en basite):
 * 1) APP: DUMP izniyle dumpsys SurfaceFlinger --latency (öndeki uygulamanın gerçek FPS'i, rootsuz)
 * 2) PANEL: sysfs measured_fps (ekran hattı FPS'i, çoğu cihazda SELinux engeller)
 * 3) HZ: ekranın o anki yenileme hızı
 */
final class FpsMeter {
    static final int MODE_APP = 1, MODE_PANEL = 2, MODE_HZ = 3;

    private static final String[] PANEL_PATHS = {
            "/sys/devices/virtual/graphics/fb0/measured_fps",
            "/sys/class/drm/sde-crtc-0/measured_fps"
    };
    private static final String PERM_DUMP = "android.permission.DUMP";
    private static final long RESOLVE_EVERY_MS = 5000;

    private static final Pattern ACTIVITY = Pattern.compile("ACTIVITY\\s+([A-Za-z0-9_.]+)/");
    private static final Pattern COMPONENT = Pattern.compile("\\s([A-Za-z][A-Za-z0-9_.]+)/");
    private static final Pattern NUM = Pattern.compile("\\d+(?:\\.\\d+)?");

    private static final ScheduledExecutorService KILLER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "perfmon-killer");
                t.setDaemon(true);
                return t;
            });

    /** Tanı için: son seçilen katman ve paket. */
    static volatile String debugLayer, debugPkg;

    private final Context ctx;
    private String layer;
    private long resolvedAt;
    private boolean cmdFail;
    private int fails;

    FpsMeter(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** DUMP iznini ve en iyi kullanılabilir kaynağı belirler. Süreç başlattığı için arka planda çağır. */
    static void detect(Context ctx) {
        Support.dump = ctx.checkSelfPermission(PERM_DUMP)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (Support.dump && layersAvailable()) {
            Support.fpsMode = MODE_APP;
        } else if (panelValue() >= 0) {
            Support.fpsMode = MODE_PANEL;
        } else {
            Support.fpsMode = MODE_HZ;
        }
    }

    private static boolean layersAvailable() {
        List<String> l = run(0, 2500, "dumpsys", "SurfaceFlinger", "--list");
        if (l.isEmpty() || l.get(0).startsWith("Permission Denial")) return false;
        for (String s : l)
            if (s.contains("#")) return true;
        return false;
    }

    float read() {
        switch (Support.fpsMode) {
            case MODE_APP: {
                float f = readApp();
                if (cmdFail && ++fails >= 3) {
                    Support.fpsMode = panelValue() >= 0 ? MODE_PANEL : MODE_HZ;
                }
                return f;
            }
            case MODE_PANEL:
                return panelValue();
            default:
                return displayHz();
        }
    }

    private float displayHz() {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
        return d == null ? -1 : d.getRefreshRate();
    }

    private static float panelValue() {
        for (String p : PANEL_PATHS) {
            String s = Sysfs.readString(p);
            if (s == null) continue;
            Matcher m = NUM.matcher(s);
            if (m.find()) return Float.parseFloat(m.group());
        }
        return -1;
    }

    // ---------------------------------------------------------------- APP modu

    private float readApp() {
        long now = SystemClock.elapsedRealtime();
        if (layer == null || now - resolvedAt > RESOLVE_EVERY_MS) resolve(now);
        if (cmdFail) return -1;
        return layer == null ? -1 : measure(layer);
    }

    private void resolve(long now) {
        resolvedAt = now;
        layer = null;
        List<String> all = run(0, 2500, "dumpsys", "SurfaceFlinger", "--list");
        if (all.isEmpty() || all.get(0).startsWith("Permission Denial")) {
            cmdFail = true;
            return;
        }
        cmdFail = false;
        fails = 0;

        String pkg = topPackage();
        debugPkg = pkg;

        // Aday katmanlar: önce öndeki uygulamanın katmanları, yoksa tüm uygulama katmanları
        List<String> cands = new ArrayList<>();
        if (pkg != null) {
            for (String l : all) {
                l = l.trim();
                if (l.contains(pkg)) cands.add(l);
            }
        }
        if (cands.isEmpty()) {
            for (String l : all) {
                l = l.trim();
                if (!l.contains("/") || !l.contains("#")) continue;
                if (l.startsWith("Background for") || l.startsWith("Bounds for")) continue;
                if (l.contains("com.android.systemui") || l.contains(ctx.getPackageName())) continue;
                cands.add(l);
            }
        }

        String best = null;
        float bestFps = -1;
        int tested = 0;
        for (String l : cands) {
            if (++tested > 10) break;
            float f = measure(l);
            if (f > bestFps) {
                bestFps = f;
                best = l;
            }
        }
        layer = best;
        debugLayer = best;
    }

    /** Öndeki uygulamanın paketi: önce activity top, olmazsa resumed activity satırı. */
    private String topPackage() {
        for (String l : run(200, 2500, "dumpsys", "activity", "top")) {
            Matcher m = ACTIVITY.matcher(l);
            if (m.find()) return m.group(1);
        }
        for (String l : run(600, 3000, "dumpsys", "activity", "activities")) {
            if (!l.contains("ResumedActivity") && !l.contains("topResumedActivity")) continue;
            Matcher m = COMPONENT.matcher(l);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /** Katmanın son 1 saniyedeki sunulan kare sayısı. */
    private float measure(String layerName) {
        List<String> lines = run(0, 2500, "dumpsys", "SurfaceFlinger", "--latency", layerName);
        if (lines.size() < 2) return 0;
        long now = System.nanoTime();
        long latest = 0;
        List<Long> ts = new ArrayList<>(lines.size());
        for (int i = 1; i < lines.size(); i++) {
            String[] p = lines.get(i).trim().split("\\s+");
            if (p.length < 3) continue;
            try {
                long t = Long.parseLong(p[1]);
                if (t <= 0 || t == Long.MAX_VALUE) continue;
                ts.add(t);
                if (t > latest) latest = t;
            } catch (NumberFormatException ignored) {
            }
        }
        if (ts.isEmpty()) return 0;
        // Saatler aynıysa ve son kare 1,2 sn'den eskiyse ekran durağandır
        if (latest <= now && now - latest > 1_200_000_000L) return 0;
        int count = 0;
        for (long t : ts)
            if (t > latest - 1_000_000_000L) count++;
        return count;
    }

    // ------------------------------------------------------------ süreç yardımcı

    /** Komutu çalıştırır; zaman aşımında öldürür. maxLines>0 ise o kadar satırdan sonra keser. */
    private static List<String> run(int maxLines, int timeoutMs, String... cmd) {
        List<String> out = new ArrayList<>();
        Process p = null;
        ScheduledFuture<?> killer = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            final Process fp = p;
            killer = KILLER.schedule(fp::destroy, timeoutMs, TimeUnit.MILLISECONDS);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()), 8192);
            String l;
            while ((l = r.readLine()) != null) {
                out.add(l);
                if (maxLines > 0 && out.size() >= maxLines) break;
            }
        } catch (Exception ignored) {
        } finally {
            if (killer != null) killer.cancel(false);
            if (p != null) p.destroy();
        }
        return out;
    }
}
