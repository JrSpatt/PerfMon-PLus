package xzr.perfmon;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.FileReader;

/** Tek bir arka plan thread'inde, her turda tüm değerleri okuyup Snapshot olarak yayınlar. */
final class Monitor {
    interface Listener {
        void onSnapshot(Snapshot s);
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler worker;
    private volatile boolean running;

    private final int delay;
    private final boolean reverseCurrent;
    private final boolean wantFreq, wantLoad, wantGpu, wantTemp, wantMem, wantPower, wantBatt, wantFps;
    private final boolean wantMinBw, wantCpuBw, wantGpuBw, wantLlcBw, wantM4m;

    private final String[] freqPaths, onlinePaths;
    private long[] prevIdle, prevTotal;
    private FpsMeter fpsMeter;

    Monitor(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;

        delay = Math.max(200, Prefs.num(Prefs.DELAY, Prefs.DELAY_DEF));
        reverseCurrent = Prefs.on(Prefs.REVERSE_CURRENT, false);
        wantFreq = Prefs.on(Prefs.CPUFREQ, true) && Support.cpuFreq;
        wantLoad = Prefs.on(Prefs.CPULOAD, true) && Support.cpuLoad;
        wantGpu = Prefs.on(Prefs.GPU, true) && (Support.gpuFreq || Support.gpuLoad);
        wantTemp = Prefs.on(Prefs.TEMP, true) && Support.temp;
        wantMem = Prefs.on(Prefs.MEM, true);
        wantPower = Prefs.on(Prefs.POWER, true) && Support.power;
        wantBatt = Prefs.on(Prefs.BATT, true);
        wantFps = Prefs.on(Prefs.FPS, true) && Support.fps;
        wantMinBw = Prefs.on(Prefs.MINCPUBW, false) && Support.minCpuBw;
        wantCpuBw = Prefs.on(Prefs.CPUBW, false) && Support.cpuBw;
        wantGpuBw = Prefs.on(Prefs.GPUBW, false) && Support.gpuBw;
        wantLlcBw = Prefs.on(Prefs.LLCBW, false) && Support.llcBw;
        wantM4m = Prefs.on(Prefs.M4M, false) && Support.m4m;

        int n = CpuTopology.get().count;
        freqPaths = new String[n];
        onlinePaths = new String[n];
        for (int i = 0; i < n; i++) {
            freqPaths[i] = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq";
            onlinePaths[i] = "/sys/devices/system/cpu/cpu" + i + "/online";
        }
        prevIdle = new long[n];
        prevTotal = new long[n];
    }

    void start() {
        running = true;
        thread = new HandlerThread("perfmon-monitor");
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(() -> {
            if (wantFps) {
                FpsMeter.detect(ctx);
                fpsMeter = new FpsMeter(ctx);
            }
            tick.run();
        });
    }

    void stop() {
        running = false;
        if (worker != null) worker.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            final Snapshot s = sample();
            main.post(() -> {
                if (running) listener.onSnapshot(s);
            });
            if (running) worker.postDelayed(this, delay);
        }
    };

    private Snapshot sample() {
        Snapshot s = new Snapshot();
        int n = CpuTopology.get().count;
        s.cpuOnline = new int[n];
        s.cpuFreq = new int[n];
        s.cpuLoad = new int[n];

        if (wantLoad) readCpuLoad(s);

        for (int i = 0; i < n; i++) {
            long on = Sysfs.readLong(onlinePaths[i]);
            s.cpuOnline[i] = on == 0 ? 0 : 1; // cpu0'da online dosyası olmayabilir
            if (wantFreq && s.cpuOnline[i] == 1) {
                long f = Sysfs.readLong(freqPaths[i]);
                s.cpuFreq[i] = f == Sysfs.NA ? -1 : (int) (f / 1000);
            }
        }

        if (wantGpu) {
            if (Support.gpuFreq) {
                long v = Sysfs.readLong(Support.gpuFreqPath);
                s.gpuFreq = v == Sysfs.NA ? -1 : (int) (v / Support.gpuFreqDiv);
            }
            if (Support.gpuLoad) s.gpuLoad = readGpuLoad();
        }

        if (wantTemp) s.cpuTemp = Thermal.readMax();

        if (wantMem) {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            if (mi.totalMem > 0)
                s.memPct = (int) Math.round(100.0 * (mi.totalMem - mi.availMem) / mi.totalMem);
        }

        if (wantPower || wantBatt) readBattery(s);

        if (wantFps && fpsMeter != null) {
            s.fps = fpsMeter.read();
        }
        s.fpsMode = fpsMeter != null ? fpsMeter.lastMode : Support.fpsMode;

        if (wantMinBw) s.minCpuBw = Sysfs.readLong(Support.pMinCpuBw);
        if (wantCpuBw) s.cpuBw = Sysfs.readLong(Support.pCpuBw);
        if (wantGpuBw) s.gpuBw = Sysfs.readLong(Support.pGpuBw);
        if (wantLlcBw) s.llcBw = Sysfs.readLong(Support.pLlcBw);
        if (wantM4m) {
            long v = Sysfs.readLong(Support.pM4m);
            s.m4m = v == Sysfs.NA ? -1 : v / 1000;
        }
        return s;
    }

    /** Önceki turla arasındaki /proc/stat farkından yük hesaplar (uyku/bekleme yok). */
    private void readCpuLoad(Snapshot s) {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"), 4096)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("cpu") || line.length() < 4 || !Character.isDigit(line.charAt(3)))
                    continue;
                String[] p = line.split("\\s+");
                int id = Integer.parseInt(p[0].substring(3));
                if (id >= prevTotal.length || p.length < 5) continue;
                long total = 0;
                for (int i = 1; i < Math.min(p.length, 9); i++) total += Long.parseLong(p[i]);
                long idle = Long.parseLong(p[4]) + (p.length > 5 ? Long.parseLong(p[5]) : 0);
                long dTotal = total - prevTotal[id];
                long dIdle = idle - prevIdle[id];
                if (prevTotal[id] != 0 && dTotal > 0)
                    s.cpuLoad[id] = (int) Math.max(0, Math.min(100, 100 * (dTotal - dIdle) / dTotal));
                prevTotal[id] = total;
                prevIdle[id] = idle;
            }
        } catch (Exception ignored) {
        }
    }

    private int readGpuLoad() {
        if (Support.gpuLoadKind == 0) {
            long v = Sysfs.readLong(Support.gpuLoadPath);
            return v == Sysfs.NA ? -1 : (int) v;
        }
        String t = Sysfs.readString(Support.gpuLoadPath);
        if (t == null) return -1;
        String[] p = t.trim().split("\\s+");
        try {
            long a = Long.parseLong(p[0]);
            long b = Long.parseLong(p[1]);
            return b <= 0 ? 0 : (int) Math.min(100, 100 * a / b);
        } catch (Exception e) {
            return -1;
        }
    }

    private void readBattery(Snapshot s) {
        Intent bi = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int mv = 0;
        if (bi != null) {
            mv = bi.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            int t = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (t != Integer.MIN_VALUE) s.battTemp = t / 10f;
        }
        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        if (bm == null) return;
        int cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        if (cur == Integer.MIN_VALUE) return;
        // BatteryManager akımı µA olarak tanımlar; dönüşüm standart (µA / 1000 = mA)
        if (reverseCurrent) cur = -cur;
        s.currentMa = Math.round(cur / 1000f);
        if (mv > 0) s.powerW = Math.abs((float) cur) * mv / 1_000_000_000f;
    }
}
