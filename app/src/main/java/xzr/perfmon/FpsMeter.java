package xzr.perfmon;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.view.Display;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FPS kaynakları, her turda sırayla denenir:
 * 1) APP: DUMP izniyle dumpsys SurfaceFlinger --latency (öndeki uygulamanın gerçek FPS'i)
 * 2) PANEL: sysfs measured_fps (ekran hattı FPS'i)
 * 3) HZ: yalnızca yukarıdakilerin hiçbiri mümkün değilse ekranın yenileme hızı
 * Geçersiz / doğrulanamayan ölçüm 0 değil -1 döner ve ekranda "--" görünür.
 */
final class FpsMeter {
    static final int MODE_APP = 1, MODE_PANEL = 2, MODE_HZ = 3;

    private static final String[] PANEL_PATHS = {
            "/sys/devices/virtual/graphics/fb0/measured_fps",
            "/sys/class/drm/sde-crtc-0/measured_fps"
    };
    private static final String PERM_DUMP = "android.permission.DUMP";
    private static final long RESOLVE_EVERY_MS = 3000;
    private static final long WINDOW_NS = 1_000_000_000L;

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
    /** Son okumada hangi kaynağın değer verdiği (etiket için). */
    volatile int lastMode = MODE_HZ;

    private String layer;
    private long resolvedAt;
    private boolean cmdFail;
    private int fails;
    private boolean appDisabled;
    private int zeros;
    private int seenGeneration = -1;

    FpsMeter(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** İzni ve en iyi kullanılabilir kaynağı belirler (kartta gösterilir). Arka planda çağır. */
    static void detect(Context ctx) {
        Support.dump = ctx.checkSelfPermission(PERM_DUMP) == PackageManager.PERMISSION_GRANTED;
        Support.shizuku = ShizukuBridge.service != null;
        if (shellAvailable() && layersAvailable()) {
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

    /** dumpsys çalıştırabilecek bir yol var mı: Shizuku servisi veya doğrudan DUMP izni. */
    private static boolean shellAvailable() {
        return ShizukuBridge.service != null || Support.dump;
    }

    float read() {
        int gen = ShizukuBridge.generation;
        if (gen != seenGeneration) { // Shizuku (yeniden) bağlandı: eski hata durumunu sıfırla
            seenGeneration = gen;
            appDisabled = false;
            fails = 0;
            layer = null;
        }
        Support.shizuku = ShizukuBridge.service != null;
        boolean appPossible = shellAvailable() && !appDisabled;
        if (appPossible) {
            float f = readApp();
            if (f >= 0) {
                lastMode = MODE_APP;
                fails = 0;
                return f;
            }
            if (cmdFail && ++fails >= 3) appDisabled = true;
        }
        float p = panelValue();
        if (p >= 0) {
            lastMode = MODE_PANEL;
            return p;
        }
        if (!appPossible) {
            lastMode = MODE_HZ;
            return displayHz();
        }
        lastMode = MODE_APP;
        return -1;
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
        if (cmdFail || layer == null) return -1;
        float f = measure(layer);
        // Üst üste sıfır: uygulama değişmiş olabilir, katmanı hemen yeniden bul
        if (f == 0) {
            if (++zeros >= 2) {
                zeros = 0;
                resolvedAt = 0;
            }
        } else {
            zeros = 0;
        }
        return f;
    }

    /** Yalnızca öndeki uygulamanın katmanları aranır; paket veya katman doğrulanamazsa ölçüm yok. */
    private void resolve(long now) {
        resolvedAt = now;
        layer = null;
        debugLayer = null;
        List<String> all = run(0, 2500, "dumpsys", "SurfaceFlinger", "--list");
        if (all.isEmpty() || all.get(0).startsWith("Permission Denial")) {
            cmdFail = true;
            return;
        }
        cmdFail = false;

        String pkg = topPackage();
        debugPkg = pkg;
        if (pkg == null) return;

        // "com.game" yalnızca "com.game/..." ile eşleşir, "com.game.beta/..." ile değil
        Pattern own = Pattern.compile("(?:^|[^A-Za-z0-9_.])" + Pattern.quote(pkg) + "/");
        String best = null;
        float bestFps = -1;
        int tested = 0;
        for (String l : all) {
            l = l.trim();
            if (!own.matcher(l).find()) continue;
            if (++tested > 8) break;
            float f = measure(l);
            if (f > bestFps) {
                bestFps = f;
                best = l;
            }
        }
        layer = best; // hiçbirinden geçerli ölçüm gelmediyse null kalır
        debugLayer = best;
    }

    /** Öndeki uygulamanın paketi: önce activity top, olmazsa resumed activity satırı. */
    private String topPackage() {
        for (String l : run(200, 2500, "dumpsys", "activity", "top")) {
            Matcher m = ACTIVITY.matcher(l);
            if (m.find()) return m.group(1);
        }
        for (String l : run(600, 3000, "dumpsys", "activity", "activities")) {
            if (!l.contains("ResumedActivity")) continue;
            Matcher m = COMPONENT.matcher(l);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /**
     * Katmanın FPS'i: geçerli kare zamanları arasındaki süreden hesaplanır.
     * SurfaceFlinger geçmişi en fazla ~127 kare tuttuğu için 1 sn'de sayım 144/165 FPS'te taşar;
     * bu yüzden (kare sayısı - 1) / (ilk ile son kare arası süre) kullanılır.
     * Geçersiz yanıtta -1, ekran durağansa 0 döner.
     */
    private float measure(String layerName) {
        List<String> lines = run(0, 2500, "dumpsys", "SurfaceFlinger", "--latency", layerName);
        if (lines.size() < 2 || lines.get(0).startsWith("Permission Denial")) return -1;

        long now = System.nanoTime();
        List<Long> ts = new ArrayList<>(lines.size());
        for (int i = 1; i < lines.size(); i++) {
            String[] p = lines.get(i).trim().split("\\s+");
            if (p.length < 3) continue;
            try {
                long t = Long.parseLong(p[1]);
                if (t > 0 && t != Long.MAX_VALUE) ts.add(t);
            } catch (NumberFormatException ignored) {
            }
        }
        if (ts.size() < 2) return -1; // tek kareden hız hesaplanamaz
        Collections.sort(ts);
        long latest = ts.get(ts.size() - 1);

        // Son kare 1,2 sn'den eskiyse ekran durağandır
        if (latest <= now && now - latest > 1_200_000_000L) return 0;

        int first = ts.size() - 1;
        while (first > 0 && ts.get(first - 1) > latest - WINDOW_NS) first--;
        int n = ts.size() - first;
        if (n < 2) { // son 1 sn'de tek kare: iki son kare arasından hesapla
            long gap = latest - ts.get(ts.size() - 2);
            return gap > 0 ? 1_000_000_000f / gap : -1;
        }
        long span = latest - ts.get(first);
        if (span <= 0) return -1;
        return (n - 1) * 1_000_000_000f / span;
    }

    // ------------------------------------------------------------ süreç yardımcı

    /** Önce Shizuku (shell yetkisi), yoksa doğrudan DUMP izniyle yerel çalıştırma; ikisi de yoksa boş liste. */
    private static List<String> run(int maxLines, int timeoutMs, String... cmd) {
        IShellService s = ShizukuBridge.service;
        if (s != null) {
            try {
                String out = s.exec(cmd, maxLines, timeoutMs);
                List<String> l = new ArrayList<>();
                if (out != null && !out.isEmpty()) Collections.addAll(l, out.split("\n"));
                return l;
            } catch (Exception ignored) {
                // servis koptuysa yerel yola düş
            }
        }
        if (!Support.dump) return new ArrayList<>();
        return runLocal(maxLines, timeoutMs, cmd);
    }

    private static List<String> runLocal(int maxLines, int timeoutMs, String... cmd) {
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
