package xzr.perfmon;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import rikka.shizuku.Shizuku;

final class ShizukuBridge {
    static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    static final int STATE_OFF = 0, STATE_NEED_PERMISSION = 1, STATE_CONNECTING = 2,
            STATE_READY = 3, STATE_DISCONNECTED = 4;

    private static final int REQUEST_CODE = 1001;
    private static final long BIND_TIMEOUT_MS = 5000, FIRST_RETRY_MS = 1000, MAX_RETRY_MS = 30000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable RETRY = ShizukuBridge::tryBind;

    static volatile IShellService service;
    static Runnable onChange;

    // Bağlantı durumu ve zamanlayıcılar yalnızca ana iş parçacığında değiştirilir.
    private static Shizuku.UserServiceArgs args;
    private static Connection current;
    private static boolean monitoring;
    private static long retryDelay = FIRST_RETRY_MS;

    static void init(Context ctx) {
        if (args != null) return;
        args = new Shizuku.UserServiceArgs(new ComponentName(ctx.getPackageName(), ShellUserService.class.getName()))
                .daemon(false).processNameSuffix("shell").debuggable(false).version(3).tag("perfmon-shell");
        Shizuku.addBinderReceivedListenerSticky(() -> MAIN.post(() -> {
            tryBind();
            notifyChange();
        }));
        Shizuku.addBinderDeadListener(() -> MAIN.post(() -> {
            clear(current, false);
            notifyChange();
        }));
        Shizuku.addRequestPermissionResultListener((code, result) -> MAIN.post(() -> {
            if (code != REQUEST_CODE) return;
            if (result == PackageManager.PERMISSION_GRANTED) tryBind();
            notifyChange();
        }));
    }

    private static final class Connection implements ServiceConnection, Runnable {
        @Override public void run() {
            if (this == current && service == null) failed(this);
        }

        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            MAIN.post(() -> {
                if (this != current) return;
                if (binder == null || !binder.pingBinder()) {
                    failed(this);
                    return;
                }
                MAIN.removeCallbacks(this);
                service = IShellService.Stub.asInterface(binder);
                retryDelay = FIRST_RETRY_MS;
                notifyChange();
            });
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            MAIN.post(() -> { if (this == current) failed(this); });
        }
    }

    private static void clear(Connection conn, boolean detach) {
        MAIN.removeCallbacks(RETRY);
        current = null;
        service = null;
        if (conn == null) return;
        MAIN.removeCallbacks(conn);
        if (detach) {
            try { Shizuku.unbindUserService(args, conn, false); }
            catch (RuntimeException ignored) { }
        }
    }

    private static void failed(Connection conn) {
        clear(conn, true);
        if (monitoring && permitted()) {
            MAIN.postDelayed(RETRY, retryDelay);
            retryDelay = Math.min(retryDelay * 2, MAX_RETRY_MS);
        }
        notifyChange();
    }

    static void setMonitoring(boolean active) {
        monitoring = active;
        if (active) tryBind();
        else {
            MAIN.removeCallbacks(RETRY);
            retryDelay = FIRST_RETRY_MS;
        }
    }

    static boolean permitted() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) { return false; }
    }

    static int state() {
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return STATE_OFF;
        } catch (RuntimeException e) { return STATE_OFF; }
        if (!permitted()) return STATE_NEED_PERMISSION;
        if (service != null) return STATE_READY;
        return current == null ? STATE_DISCONNECTED : STATE_CONNECTING;
    }

    static void tryBind() {
        if (args == null || current != null || !permitted()) return;
        MAIN.removeCallbacks(RETRY);
        Connection conn = new Connection();
        current = conn;
        MAIN.postDelayed(conn, BIND_TIMEOUT_MS);
        try {
            Shizuku.bindUserService(args, conn);
            notifyChange();
        } catch (RuntimeException e) {
            failed(conn);
        }
    }

    static void requestPermission() {
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(REQUEST_CODE);
        } catch (RuntimeException ignored) { }
    }

    private static void notifyChange() {
        if (onChange != null) onChange.run();
    }
}
