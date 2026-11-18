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

/** Kare ölçümünü ve her örneğin kaynağını birlikte belirler. */
final class FpsMeter {
    static final int MODE_NONE = 0, MODE_SHIZUKU = 1, MODE_DUMP = 2, MODE_PANEL = 3, MODE_HZ = 4;
    private static final String[] PANEL_PATHS = {
            "/sys/devices/virtual/graphics/fb0/measured_fps",
            "/sys/class/drm/sde-crtc-0/measured_fps"
    };
    private static final String PERM_DUMP = "android.permission.DUMP";
    private static final long RESOLVE_EVERY_MS = 3000;
    private static final long WINDOW_NS = 1_000_000_000L;

    private static final Pattern ACTIVITY = Pattern.compile("ACTIVITY\\s+([A-Za-z0-9_.]+)/");
    private static final Pattern COMPONENT = Pattern.compile("\\s([A-Za-z][A-Za-z0-9_.]+)/");
    private static final Pattern NUM = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");

    private static final ScheduledExecutorService KILLER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "perfmon-killer");
                t.setDaemon(true);
                return t;
            });

    private final Context ctx;
    private final LayerSource shizuku = new LayerSource(), direct = new LayerSource();
    int lastMode = MODE_NONE;
    private IShellService previousService;

    FpsMeter(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    float read() {
        IShellService service = ShizukuBridge.service;
        if (service != previousService) {
            shizuku.reset();
            previousService = service;
        }
        if (service != null) {
            float f = shizuku.read(service);
            if (f >= 0) { lastMode = MODE_SHIZUKU; return f; }
        }
        if (ctx.checkSelfPermission(PERM_DUMP) == PackageManager.PERMISSION_GRANTED) {
            float f = direct.read(null);
            if (f >= 0) { lastMode = MODE_DUMP; return f; }
        }
        float f = panelValue();
        if (f >= 0) { lastMode = MODE_PANEL; return f; }
        f = displayHz();
        lastMode = f >= 0 ? MODE_HZ : MODE_NONE;
        return f;
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
            if (m.find()) {
                float measured = Float.parseFloat(m.group());
                if (measured >= 0 && !Float.isInfinite(measured)) return measured;
            }
        }
        return -1;
    }

    private static final class LayerSource {
        String layer, pkg;
        long nextPackageCheck, nextSearch;
        int zeros;

        void reset() {
            layer = null;
            pkg = null;
            nextPackageCheck = nextSearch = 0;
            zeros = 0;
        }

        float read(IShellService service) {
            long now = SystemClock.elapsedRealtime();
            if (now >= nextPackageCheck) {
                String current = topPackage(service);
                if (!java.util.Objects.equals(current, pkg)) {
                    pkg = current;
                    layer = null;
                    nextSearch = 0;
                    zeros = 0;
                }
                nextPackageCheck = now + 1000;
            }
            if (pkg == null) return -1;
            if (layer == null || now >= nextSearch) {
                if (now < nextSearch) return -1;
                nextSearch = now + RESOLVE_EVERY_MS;
                return resolve(service);
            }
            float f = measure(service, layer);
            if (f < 0 || (f == 0 && ++zeros >= 2)) {
                layer = null;
                nextSearch = 0;
                nextPackageCheck = 0;
                zeros = 0;
            } else if (f > 0) {
                zeros = 0;
            }
            return f;
        }

        private float resolve(IShellService service) {
            Pattern own = Pattern.compile("(?:^|[^A-Za-z0-9_.])" + Pattern.quote(pkg) + "/");
            List<String> candidates = new ArrayList<>();
            for (String name : run(service, 0, 2500, "dumpsys", "SurfaceFlinger", "--list")) {
                name = name.trim();
                if (own.matcher(name).find() && !name.startsWith("Background for") && !name.startsWith("Bounds for"))
                    candidates.add(name);
            }
            // Oyun yüzeyi konteyner ve dekor katmanlarından önce incelenir.
            Collections.sort(candidates, (a, b) -> Boolean.compare(b.contains("SurfaceView"), a.contains("SurfaceView")));
            layer = null;
            float best = -1;
            for (int i = 0; i < Math.min(8, candidates.size()); i++) {
                String candidate = candidates.get(i);
                float f = measure(service, candidate);
                if (f > best) { best = f; layer = candidate; }
                if (f > 0 && candidate.contains("SurfaceView")) break;
            }
            return best;
        }

        private String topPackage(IShellService service) {
            String fallback = null, resumed = null;
            for (String l : run(service, 1000, 2500, "dumpsys", "activity", "activities")) {
                if (!l.contains("ResumedActivity")) continue;
                Matcher m = COMPONENT.matcher(l);
                if (m.find()) {
                    if (l.contains("topResumedActivity")) return m.group(1);
                    if (l.contains("mResumedActivity")) resumed = m.group(1);
                    else fallback = m.group(1);
                }
            }
            if (resumed != null) return resumed;
            if (fallback != null) return fallback;
            String only = null;
            for (String l : run(service, 300, 2500, "dumpsys", "activity", "top")) {
                Matcher m = ACTIVITY.matcher(l);
                if (!m.find()) continue;
                String found = m.group(1);
                if (only != null && !only.equals(found)) return null;
                only = found;
            }
            return only;
        }
    }

    /**
     * Katmanın FPS'i: geçerli kare zamanları arasındaki süreden hesaplanır.
     * SurfaceFlinger geçmişi en fazla ~127 kare tuttuğu için 1 sn'de sayım 144/165 FPS'te taşar;
     * bu yüzden (kare sayısı - 1) / (ilk ile son kare arası süre) kullanılır.
     * Geçersiz yanıtta -1, ekran durağansa 0 döner.
     */
    private static float measure(IShellService service, String layerName) {
        List<String> lines = run(service, 0, 2500, "dumpsys", "SurfaceFlinger", "--latency", layerName);
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

    private static List<String> run(IShellService service, int maxLines, int timeoutMs, String... cmd) {
        if (service == null) return runLocal(maxLines, timeoutMs, cmd);
        try {
            String out = service.exec(cmd, maxLines, timeoutMs);
            if (out != null && !out.isEmpty()) return java.util.Arrays.asList(out.split("\\n"));
        } catch (android.os.RemoteException ignored) {
            // Sıradaki kaynak aynı örnek içinde denenir.
        }
        return Collections.emptyList();
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
