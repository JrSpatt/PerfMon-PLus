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

    private static final Pattern NUM = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");

    private static final ScheduledExecutorService KILLER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "perfmon-killer");
                t.setDaemon(true);
                return t;
            });

    static final class Reading {
        static final Reading IDLE = new Reading(-1, MODE_NONE, 0, null);
        final float value;
        final int mode, reason;
        final String pkg;
        final long time;

        Reading(float value, int mode, int reason, String pkg) {
            this.value = value;
            this.mode = mode;
            this.reason = reason;
            this.pkg = pkg;
            time = SystemClock.elapsedRealtime();
        }

        boolean isApp() { return mode == MODE_SHIZUKU || mode == MODE_DUMP; }
    }

    private final Context ctx;
    private LayerSource shizuku = new LayerSource();
    private final LayerSource direct = new LayerSource();
    private IShellService previousService;

    FpsMeter(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    Reading read() {
        IShellService service = ShizukuBridge.service;
        if (service != previousService) {
            shizuku = new LayerSource();
            previousService = service;
        }
        Reading failed = null;
        if (service != null) {
            Reading r = shizuku.read(service, MODE_SHIZUKU);
            if (r.isApp()) return r;
            failed = r;
        }
        if (ctx.checkSelfPermission(PERM_DUMP) == PackageManager.PERMISSION_GRANTED) {
            Reading r = direct.read(null, MODE_DUMP);
            if (r.isApp()) return r;
            if (failed == null || (failed.pkg == null && r.pkg != null)) failed = r;
        }
        int reason = failed == null ? R.string.fps_no_access : failed.reason;
        String pkg = failed == null ? null : failed.pkg;
        float panel = panelValue();
        if (panel >= 0) return new Reading(panel, MODE_PANEL, reason, pkg);
        float hz = displayHz();
        return hz > 0 && Float.isFinite(hz) ? new Reading(hz, MODE_HZ, reason, pkg)
                : new Reading(-1, MODE_NONE, reason, pkg);
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
        private String target, layer;
        private int issue;
        private long nextSearch;

        Reading read(IShellService service, int mode) {
            List<String> windows = run(service, 400, 2500, "dumpsys", "window", "displays");
            String focus = FrameLayers.windowFocus(windows);
            if (focus == null) {
                List<String> activities = run(service, 1000, 2500, "dumpsys", "activity", "activities");
                focus = FrameLayers.singleActivity(activities, false);
                if (focus == null && denied(activities))
                    return new Reading(-1, MODE_NONE, R.string.fps_denied, null);
                if (focus == null) focus = FrameLayers.singleActivity(
                        run(service, 300, 2500, "dumpsys", "activity", "top"), true);
            }
            if (focus == null || focus.isEmpty()) {
                target = layer = null;
                return new Reading(-1, MODE_NONE, R.string.fps_no_focus, null);
            }
            String pkg = focus.substring(0, focus.indexOf('/'));
            long now = SystemClock.elapsedRealtime();
            boolean search = !focus.equals(target) || now >= nextSearch;
            target = focus;
            if (search) resolve(service, now);
            if (layer == null) return new Reading(-1, MODE_NONE, issue, pkg);

            List<String> frames = latency(service);
            float value = rate(frames, System.nanoTime());
            if (value < 0 && !search && !denied(frames)) {
                String old = layer;
                resolve(service, now);
                if (layer == null) return new Reading(-1, MODE_NONE, issue, pkg);
                if (!old.equals(layer)) {
                    frames = latency(service);
                    value = rate(frames, System.nanoTime());
                }
            }
            if (value < 0) return new Reading(-1, MODE_NONE,
                    denied(frames) ? R.string.fps_denied : R.string.fps_no_frames, pkg);
            return new Reading(value, mode, 0, pkg);
        }

        private List<String> latency(IShellService service) {
            return run(service, 0, 2500, "dumpsys", "SurfaceFlinger", "--latency", layer);
        }

        private void resolve(IShellService service, long now) {
            nextSearch = now + RESOLVE_EVERY_MS;
            List<String> list = run(service, 0, 2500, "dumpsys", "SurfaceFlinger", "--list");
            List<String> names = FrameLayers.candidates(list, target);
            layer = FrameLayers.singleProducer(names);
            if (layer != null) return;
            if (names.isEmpty()) {
                issue = denied(list) ? R.string.fps_denied : R.string.fps_no_layer;
            } else {
                layer = FrameLayers.visibleProducer(names,
                        run(service, FrameLayers.HWC_LIMIT, 1500, "dumpsys", "SurfaceFlinger", "--hwclayers"));
                issue = R.string.fps_many_layers;
            }
        }
    }

    private static boolean denied(List<String> lines) {
        return !lines.isEmpty() && (lines.get(0).contains("Permission Denial")
                || lines.get(0).contains("Permission denied"));
    }

    /**
     * Katmanın FPS'i: geçerli kare zamanları arasındaki süreden hesaplanır.
     * SurfaceFlinger geçmişi en fazla ~127 kare tuttuğu için 1 sn'de sayım 144/165 FPS'te taşar;
     * bu yüzden (kare sayısı - 1) / (ilk ile son kare arası süre) kullanılır.
     * Geçersiz yanıtta -1, ekran durağansa 0 döner.
     */
    static float rate(List<String> lines, long now) {
        if (lines.size() < 2) return -1;
        List<Long> ts = new ArrayList<>(lines.size());
        for (int i = 1; i < lines.size(); i++) {
            String[] p = lines.get(i).trim().split("\\s+");
            if (p.length < 3) continue;
            try {
                long t = Long.parseLong(p[1]);
                if (t > 0 && t != Long.MAX_VALUE && t <= now) ts.add(t);
            } catch (NumberFormatException ignored) {
            }
        }
        if (ts.size() < 2) return -1; // tek kareden hız hesaplanamaz
        Collections.sort(ts);
        int unique = 1;
        for (int i = 1; i < ts.size(); i++) {
            if (!ts.get(i).equals(ts.get(unique - 1))) ts.set(unique++, ts.get(i));
        }
        if (unique < 2) return -1;
        long latest = ts.get(unique - 1);

        // Son kare 1,2 sn'den eskiyse ekran durağandır
        if (latest <= now && now - latest > 1_200_000_000L) return 0;

        int first = unique - 1;
        while (first > 0 && ts.get(first - 1) > latest - WINDOW_NS) first--;
        int n = unique - first;
        if (n < 2) { // son 1 sn'de tek kare: iki son kare arasından hesapla
            long gap = latest - ts.get(unique - 2);
            return gap > 0 ? 1_000_000_000f / gap : -1;
        }
        long span = latest - ts.get(first);
        if (span <= 0) return -1;
        return (n - 1) * 1_000_000_000f / span;
    }

    private static List<String> run(IShellService service, int maxLines, int timeoutMs, String... cmd) {
        if (service == null) return runLocal(maxLines, timeoutMs, cmd);
        try {
            String out = service.exec(cmd, maxLines, timeoutMs);
            if (out != null && !out.isEmpty()) return java.util.Arrays.asList(out.split("\\n"));
        } catch (android.os.RemoteException | RuntimeException ignored) {
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
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()), 8192)) {
                String l;
                int chars = 0;
                while (out.size() < (maxLines > 0 ? maxLines : 8000) && (l = r.readLine()) != null) {
                    chars += l.length() + 1;
                    if (chars > 240000) break;
                    out.add(l);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (killer != null) killer.cancel(false);
            if (p != null) p.destroy();
        }
        return out;
    }
}
