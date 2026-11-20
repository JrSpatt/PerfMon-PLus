package xzr.perfmon;

import android.content.Context;
import android.os.BatteryManager;

/** Cihazda hangi verilerin okunabildiği. */
final class Support {
    private static volatile Support current = new Support();

    static Support get() { return current; }

    boolean cpuFreq, cpuLoad, gpuFreq, gpuLoad, temp, power;
    boolean minCpuBw, cpuBw, gpuBw, llcBw, m4m;

    String gpuFreqPath;
    long gpuFreqDiv = 1;
    String gpuLoadPath;
    int gpuLoadKind; // 0: doğrudan yüzde, 1: "a b" oranı

    String pMinCpuBw, pCpuBw, pGpuBw, pLlcBw, pM4m;

    /** Yalnızca dosya okuma yapar, süreç başlatmaz; hızlıdır. */
    static synchronized void probe(Context ctx) {
        Support next = new Support();
        next.detect(ctx);
        current = next;
    }

    private void detect(Context ctx) {
        CpuTopology.probe();
        cpuFreq = Sysfs.readLong("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq") != Sysfs.NA;
        cpuLoad = probeCpuLoad();

        String[][] gf = {
                {"/sys/kernel/gpu/gpu_clock", "1"},
                {"/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq", "1000000"},
                {"/sys/class/kgsl/kgsl-3d0/gpuclk", "1000000"}
        };
        for (String[] c : gf) {
            if (Sysfs.readLong(c[0]) != Sysfs.NA) {
                gpuFreqPath = c[0];
                gpuFreqDiv = Long.parseLong(c[1]);
                break;
            }
        }
        gpuFreq = gpuFreqPath != null;

        if (Sysfs.readLong("/sys/kernel/gpu/gpu_busy") != Sysfs.NA) {
            gpuLoadPath = "/sys/kernel/gpu/gpu_busy";
            gpuLoadKind = 0;
        } else if (Sysfs.readLong("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage") != Sysfs.NA) {
            gpuLoadPath = "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage";
            gpuLoadKind = 0;
        } else if (Sysfs.readString("/sys/class/kgsl/kgsl-3d0/gpubusy") != null) {
            gpuLoadPath = "/sys/class/kgsl/kgsl-3d0/gpubusy";
            gpuLoadKind = 1;
        }
        gpuLoad = gpuLoadPath != null;

        temp = Thermal.probe();

        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        power = bm != null && bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) != Integer.MIN_VALUE;

        pMinCpuBw = Sysfs.firstReadable("/sys/class/devfreq/soc:qcom,mincpubw/cur_freq");
        pCpuBw = Sysfs.firstReadable("/sys/class/devfreq/soc:qcom,cpubw/cur_freq",
                "/sys/class/devfreq/soc:qcom,cpu-cpu-llcc-bw/cur_freq");
        pGpuBw = Sysfs.firstReadable("/sys/class/devfreq/soc:qcom,gpubw/cur_freq");
        pLlcBw = Sysfs.firstReadable("/sys/class/devfreq/soc:qcom,cpu-llcc-ddr-bw/cur_freq",
                "/sys/class/devfreq/soc:qcom,llccbw/cur_freq");
        pM4m = Sysfs.firstReadable("/sys/class/devfreq/soc:qcom,m4m/cur_freq");
        minCpuBw = pMinCpuBw != null;
        cpuBw = pCpuBw != null;
        gpuBw = pGpuBw != null;
        llcBw = pLlcBw != null;
        m4m = pM4m != null;
    }

    private static boolean probeCpuLoad() {
        String s = Sysfs.readString("/proc/stat");
        return s != null && s.startsWith("cpu");
    }
}
