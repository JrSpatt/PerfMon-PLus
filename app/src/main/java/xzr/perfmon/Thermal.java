package xzr.perfmon;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** CPU ile ilgili thermal zone'ları bir kez bulur, sonra yalnızca temp dosyalarını okur. */
final class Thermal {
    private static volatile List<String> paths = new ArrayList<>();

    static boolean probe() {
        List<String> found = new ArrayList<>();
        File[] zones = new File("/sys/class/thermal").listFiles();
        if (zones == null) {
            paths = found;
            return false;
        }
        for (File z : zones) {
            if (!z.getName().startsWith("thermal_zone")) continue;
            String type = Sysfs.readString(z.getPath() + "/type");
            if (type == null) continue;
            type = type.toLowerCase(Locale.US);
            if (type.contains("cpu") || type.startsWith("tsens") || type.contains("cluster"))
                found.add(z.getPath() + "/temp");
        }
        paths = found;
        return !Float.isNaN(readMax());
    }

    /** Bulunan CPU sensörlerinin en yükseği (°C); okunamazsa NaN. */
    static float readMax() {
        float max = Float.NaN;
        List<String> list = paths;
        for (String p : list) {
            long v = Sysfs.readLong(p);
            if (v == Sysfs.NA) continue;
            float c = v >= 1000 ? v / 1000f : (v >= 150 ? v / 10f : v);
            if (c <= 0 || c > 150) continue;
            if (Float.isNaN(max) || c > max) max = c;
        }
        return max;
    }
}
