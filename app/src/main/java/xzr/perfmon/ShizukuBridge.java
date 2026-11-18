package xzr.perfmon;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import rikka.shizuku.Shizuku;

/** Shizuku bağlantısı: izin isteme ve shell yetkili komut servisine bağlanma. */
final class ShizukuBridge {
    static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final int REQUEST_CODE = 1001;

    static final int STATE_OFF = 0, STATE_NEED_PERMISSION = 1, STATE_CONNECTING = 2, STATE_READY = 3;

    /** Bağlıysa shell yetkili komut servisi, değilse null. */
    static volatile IShellService service;
    /** Durum değişince çağrılır (herhangi bir thread'den). */
    static volatile Runnable onChange;

    private static boolean initialized, binding;
    private static Shizuku.UserServiceArgs args;

    private static final ServiceConnection CONN = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = binder != null && binder.pingBinder() ? IShellService.Stub.asInterface(binder) : null;
            binding = false;
            notifyChange();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            binding = false;
            notifyChange();
        }
    };

    static synchronized void init(Context ctx) {
        if (initialized) return;
        initialized = true;
        args = new Shizuku.UserServiceArgs(
                new ComponentName(ctx.getPackageName(), ShellUserService.class.getName()))
                .daemon(false)
                .processNameSuffix("shell")
                .debuggable(false)
                .version(2)
                .tag("perfmon-shell");
        Shizuku.addBinderReceivedListenerSticky(() -> {
            tryBind();
            notifyChange();
        });
        Shizuku.addBinderDeadListener(() -> {
            service = null;
            binding = false;
            notifyChange();
        });
        Shizuku.addRequestPermissionResultListener((code, result) -> {
            if (code != REQUEST_CODE) return;
            if (result == PackageManager.PERMISSION_GRANTED) tryBind();
            notifyChange();
        });
    }

    static boolean permitted() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    static int state() {
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return STATE_OFF;
        } catch (Throwable t) {
            return STATE_OFF;
        }
        if (service != null) return STATE_READY;
        return permitted() ? STATE_CONNECTING : STATE_NEED_PERMISSION;
    }

    static synchronized void tryBind() {
        if (binding || service != null || !permitted()) return;
        binding = true;
        try {
            Shizuku.bindUserService(args, CONN);
        } catch (Throwable t) {
            binding = false;
        }
    }

    static void requestPermission() {
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(REQUEST_CODE);
        } catch (Throwable ignored) {
        }
    }

    private static void notifyChange() {
        Runnable r = onChange;
        if (r != null) r.run();
    }
}
