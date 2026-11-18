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
        StringBuilder out = new StringBuilder();
        Process p = null;
        Thread killer = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            final Process fp = p;
            killer = new Thread(() -> {
                try {
                    Thread.sleep(timeoutMs);
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
}
