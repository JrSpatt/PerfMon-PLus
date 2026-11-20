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
import java.util.HashMap;
import java.util.Map;
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
    private static final int MAX_CANDIDATES = 6;

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
    private boolean hadFps;

    FpsMeter(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    Reading read() {
        Reading result = measure();
        boolean firstMiss = hadFps && result.mode == MODE_HZ;
        hadFps = result.isApp() || result.mode == MODE_PANEL;
        return firstMiss ? new Reading(-1, MODE_NONE, result.reason, result.pkg) : result;
    }

    private Reading measure() {
        long deadline = SystemClock.elapsedRealtime() + 2500;
        IShellService service = ShizukuBridge.service;
        if (service != previousService) {
            shizuku = new LayerSource();
            previousService = service;
        }
        Reading failed = null;
        if (service != null) {
            Reading r = shizuku.read(service, MODE_SHIZUKU, deadline);
            if (r.isApp()) {
                if (direct.target != null) direct.reset(null);
                return r;
            }
            failed = r;
        }
        if (ctx.checkSelfPermission(PERM_DUMP) == PackageManager.PERMISSION_GRANTED) {
            Reading r = direct.read(null, MODE_DUMP, deadline);
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
        private String target;
        private FrameLayers.Plan plan;
        private FrameSample.Tracker track = new FrameSample.Tracker();
        private long nextSearch, deadline;
        private int issue;

        private void reset(String focus) {
            target = focus;
            plan = null;
            track = new FrameSample.Tracker();
        }

        private List<String> query(IShellService service, int limit, String... args) {
            long left = deadline - SystemClock.elapsedRealtime();
            if (left < 100) return Collections.emptyList();
            return run(service, limit, (int) Math.min(left, 2500), args);
        }

        private String focus(IShellService service) {
            List<String> windows = query(service, 400, "dumpsys", "window", "displays");
            String focus = FrameLayers.windowFocus(windows);
            if (focus != null) return focus;
            List<String> activities = query(service, 1000, "dumpsys", "activity", "activities");
            if (denied(activities)) { issue = R.string.fps_denied; return null; }
            focus = FrameLayers.singleActivity(activities, false);
            if (focus == null)
                focus = FrameLayers.singleActivity(query(service, 300, "dumpsys", "activity", "top"), true);
            return focus;
        }

        private FrameSample sample(IShellService service, String name) {
            List<String> lines = query(service, 0, "dumpsys", "SurfaceFlinger", "--latency", name);
            if (denied(lines)) issue = R.string.fps_denied;
            return FrameSample.parse(lines, System.nanoTime());
        }

        Reading read(IShellService service, int mode, long until) {
            deadline = until;
            issue = R.string.fps_no_focus;
            String focus = focus(service);
            if (focus == null || focus.isEmpty()) {
                reset(null);
                return new Reading(-1, MODE_NONE, issue, null);
            }
            if (!focus.equals(target)) reset(focus);
            String pkg = focus.substring(0, focus.indexOf('/'));
            long now = SystemClock.elapsedRealtime();
            boolean refresh = plan == null || now >= nextSearch;
            Map<String,FrameSample> samples = new HashMap<>();
            issue = R.string.fps_no_frames;
            if (!refresh && track.selected != null) {
                String name = track.selected;
                FrameSample current = sample(service, name);
                samples.put(name, current);
                if (track.advances(name, current)) {
                    if (plan.fastPath(name) && (plan.size() == 1 || track.hasSequence(name, current)))
                        return new Reading(track.accept(name, current), mode, 0, pkg);
                } else {
                    // Durağanlık kararı için görünürlüğü ve alternatif üreticileri tekrar doğrula.
                    refresh = true;
                }
            }
            if (refresh) {
                List<String> list = query(service, 0, "dumpsys", "SurfaceFlinger", "--list");
                if (denied(list)) { reset(focus); return new Reading(-1, MODE_NONE, R.string.fps_denied, pkg); }
                List<String> hwc = list.isEmpty() ? Collections.emptyList()
                        : query(service, FrameLayers.HWC_LIMIT, "dumpsys", "SurfaceFlinger", "--hwclayers");
                plan = FrameLayers.plan(list, focus, hwc);
                nextSearch = now + (plan.size() == 0 ? 500 : RESOLVE_EVERY_MS);
            }
            if (plan.size() == 0) {
                track = new FrameSample.Tracker();
                return new Reading(-1, MODE_NONE, R.string.fps_no_layer, pkg);
            }
            if (plan.size() > MAX_CANDIDATES || (plan.visible.isEmpty()
                    && (plan.content.size() > 1 || plan.windows.size() > 1))) {
                track = new FrameSample.Tracker();
                return new Reading(-1, MODE_NONE, R.string.fps_many_layers, pkg);
            }
            List<String> candidates = new ArrayList<>(plan.content);
            candidates.addAll(plan.windows);
            samples.keySet().retainAll(candidates);
            for (String n : candidates) {
                if (!samples.containsKey(n)) samples.put(n, sample(service, n));
            }
            if (refresh && !focus.equals(focus(service))) {
                reset(null);
                return new Reading(-1, MODE_NONE, R.string.fps_no_focus, null);
            }
            if (SystemClock.elapsedRealtime() >= deadline)
                return new Reading(-1, MODE_NONE, R.string.fps_no_frames, pkg);
            float value = track.choose(plan, samples);
            if (value < 0) return new Reading(-1, MODE_NONE, track.ambiguous ? R.string.fps_many_layers : issue, pkg);
            return new Reading(value, mode, 0, pkg);
        }
    }

    private static boolean denied(List<String> lines) {
        return !lines.isEmpty() && (lines.get(0).contains("Permission Denial")
                || lines.get(0).contains("Permission denied"));
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
                if ("--hwclayers".equals(cmd[2])) return FrameLayers.readHwc(r, maxLines);
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
