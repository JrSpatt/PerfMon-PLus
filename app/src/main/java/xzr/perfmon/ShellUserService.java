package xzr.perfmon;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Shizuku tarafından shell (adb) yetkisiyle ayrı bir süreçte çalıştırılır.
 * Shell kullanıcısı DUMP iznine sahip olduğu için dumpsys çağrıları burada çalışır.
 */
public class ShellUserService extends IShellService.Stub {

    public ShellUserService() {
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public String exec(String[] cmd, int maxLines, int timeoutMs) {
        if (!isMeasurement(cmd)) return "";
        maxLines = maxLines <= 0 ? 8000 : Math.min(8000, maxLines);
        final int timeout = Math.max(100, Math.min(4000, timeoutMs));
        StringBuilder out = new StringBuilder();
        Process p = null;
        Thread killer = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            final Process fp = p;
            killer = new Thread(() -> {
                try {
                    Thread.sleep(timeout);
                    fp.destroy();
                } catch (InterruptedException ignored) {
                }
            });
            killer.setDaemon(true);
            killer.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()), 8192);
            String l;
            int n = 0;
            while ((l = r.readLine()) != null) {
                if (out.length() + l.length() > 240000) break;
                out.append(l).append('\n');
                if (maxLines > 0 && ++n >= maxLines) break;
            }
        } catch (Exception ignored) {
        } finally {
            if (killer != null) killer.interrupt();
            if (p != null) p.destroy();
        }
        return out.toString();
    }
    private static boolean isMeasurement(String[] cmd) {
        if (cmd == null || cmd.length < 3 || !"dumpsys".equals(cmd[0])) return false;
        if ("activity".equals(cmd[1]))
            return cmd.length == 3 && ("activities".equals(cmd[2]) || "top".equals(cmd[2]));
        return "SurfaceFlinger".equals(cmd[1]) && ((cmd.length == 3 && "--list".equals(cmd[2]))
                || (cmd.length == 4 && "--latency".equals(cmd[2]) && cmd[3] != null && !cmd[3].startsWith("-")));
    }
}
