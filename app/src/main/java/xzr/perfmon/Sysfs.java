package xzr.perfmon;

import java.io.FileInputStream;

/** Tek seferlik küçük dosya okumaları (sysfs / proc). */
final class Sysfs {
    static final long NA = Long.MIN_VALUE;

    /** Dosyadaki ilk tam sayıyı okur; okunamazsa NA döner. */
    static long readLong(String path) {
        try (FileInputStream in = new FileInputStream(path)) {
            byte[] b = new byte[64];
            int n = in.read(b);
            if (n <= 0) return NA;
            int i = 0;
            while (i < n && (b[i] == ' ' || b[i] == '\n' || b[i] == '\t')) i++;
            boolean neg = false;
            if (i < n && b[i] == '-') {
                neg = true;
                i++;
            }
            long v = 0;
            boolean any = false;
            while (i < n && b[i] >= '0' && b[i] <= '9') {
                v = v * 10 + (b[i] - '0');
                i++;
                any = true;
            }
            return any ? (neg ? -v : v) : NA;
        } catch (Exception e) {
            return NA;
        }
    }

    /** Dosya içeriğini (en fazla 512 bayt) kırpılmış metin olarak okur; okunamazsa null. */
    static String readString(String path) {
        try (FileInputStream in = new FileInputStream(path)) {
            byte[] b = new byte[512];
            int n = in.read(b);
            if (n <= 0) return null;
            return new String(b, 0, n).trim();
        } catch (Exception e) {
            return null;
        }
    }

    static String firstReadable(String... paths) {
        for (String p : paths)
            if (readLong(p) != NA) return p;
        return null;
    }
}
