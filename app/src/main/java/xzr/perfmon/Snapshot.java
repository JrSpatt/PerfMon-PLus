package xzr.perfmon;

/** Bir yenileme turunda okunan tüm değerler. -1 / NaN = okunamadı. */
final class Snapshot {
    int[] cpuOnline = new int[0];
    int[] cpuFreq = new int[0];   // MHz
    int[] cpuLoad = new int[0];   // %
    int gpuFreq = -1;             // MHz
    int gpuLoad = -1;             // %
    float cpuTemp = Float.NaN;    // °C
    int memPct = -1;
    float powerW = Float.NaN;
    int currentMa = Integer.MIN_VALUE;
    float battTemp = Float.NaN;   // °C
    FpsMeter.Reading frame = FpsMeter.Reading.IDLE;
    long minCpuBw = -1, cpuBw = -1, gpuBw = -1, llcBw = -1, m4m = -1;
}
