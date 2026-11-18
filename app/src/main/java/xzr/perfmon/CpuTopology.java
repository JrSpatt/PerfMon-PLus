package xzr.perfmon;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CPU çekirdek sayısı ve little/mid/big küme dağılımı. */
final class CpuTopology {
    static int count = 1;
    static int[][] clusters = {{0}};
    static String[] labels = {"CPU"};

    static void probe() {
        count = countCpus();
        clusters = detectClusters();
        labels = makeLabels(clusters.length);
    }

    private static int countCpus() {
        String[] names = new File("/sys/devices/system/cpu").list();
        int max = -1;
        if (names != null) {
            for (String n : names) {
                if (n.matches("cpu[0-9]+")) {
                    max = Math.max(max, Integer.parseInt(n.substring(3)));
                }
            }
        }
        if (max < 0) return Math.max(1, Runtime.getRuntime().availableProcessors());
        return max + 1;
    }

    private static int[][] detectClusters() {
        Map<String, List<Integer>> groups = new LinkedHashMap<>();

        // Çevrimdışı çekirdekleri de kapsadığı için policy dizinleri en güvenilir kaynak
        File[] policies = new File("/sys/devices/system/cpu/cpufreq").listFiles();
        if (policies != null) {
            java.util.Arrays.sort(policies);
            for (File p : policies) {
                if (!p.getName().startsWith("policy")) continue;
                String rel = Sysfs.readString(p.getPath() + "/related_cpus");
                if (rel == null || rel.isEmpty()) continue;
                List<Integer> list = new ArrayList<>();
                for (String t : rel.split("\\s+")) {
                    try {
                        int id = Integer.parseInt(t);
                        if (id < count) list.add(id);
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (!list.isEmpty()) groups.put(p.getName(), list);
            }
        }

        // Yedek: çekirdek başına cpufreq/related_cpus
        if (groups.isEmpty()) {
            for (int i = 0; i < count; i++) {
                String rel = Sysfs.readString("/sys/devices/system/cpu/cpu" + i + "/cpufreq/related_cpus");
                String key = rel == null || rel.isEmpty() ? "cpu" + i : rel;
                List<Integer> list = groups.get(key);
                if (list == null) {
                    list = new ArrayList<>();
                    groups.put(key, list);
                }
                list.add(i);
            }
        }

        // Hiçbir şey okunamazsa tek küme
        if (groups.isEmpty()) {
            int[] all = new int[count];
            for (int i = 0; i < count; i++) all[i] = i;
            return new int[][]{all};
        }

        List<int[]> out = new ArrayList<>();
        for (List<Integer> l : groups.values()) {
            int[] a = new int[l.size()];
            for (int i = 0; i < a.length; i++) a[i] = l.get(i);
            java.util.Arrays.sort(a);
            out.add(a);
        }
        // En düşük çekirdek numarasına göre sırala: little ilk, big/prime son
        java.util.Collections.sort(out, (x, y) -> Integer.compare(x[0], y[0]));
        return out.toArray(new int[0][]);
    }

    private static String[] makeLabels(int n) {
        switch (n) {
            case 1:
                return new String[]{"CPU"};
            case 2:
                return new String[]{"Little", "Big"};
            case 3:
                return new String[]{"Little", "Mid", "Big"};
            case 4:
                return new String[]{"Little", "Mid", "Big", "Prime"};
            default:
                String[] l = new String[n];
                for (int i = 0; i < n; i++) l[i] = "C" + i;
                return l;
        }
    }
}
