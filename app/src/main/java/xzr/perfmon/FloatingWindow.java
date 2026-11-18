package xzr.perfmon;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.service.quicksettings.TileService;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;

/** Kayan monitör penceresini ve onu canlı tutan ön plan bildirimini yönetir. */
public class FloatingWindow extends Service {
    static final String ACTION_STOP = "xzr.perfmon.STOP";
    static final String ACTION_RELOAD = "xzr.perfmon.RELOAD";
    private static final String CHANNEL = "perfmon";

    static volatile boolean running;

    private WindowManager wm;
    private OverlayView view;
    private WindowManager.LayoutParams lp;
    private Monitor monitor;

    /** Çalışıyorsa ayarların yeniden okunmasını ister. */
    static void reload(Context c) {
        if (running) c.startService(new Intent(c, FloatingWindow.class).setAction(ACTION_RELOAD));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        ShizukuBridge.init(this);
        running = true;
        startForeground(1, buildNotification());
        Support.probe(this);
        setup();
        requestTileUpdate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(a)) {
            stopSelf();
        } else if (ACTION_RELOAD.equals(a)) {
            teardown();
            setup();
        }
        return START_NOT_STICKY;
    }

    private void setup() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        view = new OverlayView(this);
        final boolean locked = Prefs.on(Prefs.LOCK, false);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | (locked ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0),
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int x = Prefs.sp.getInt(Prefs.POS_X, Ui.dp(this, 16));
        int y = Prefs.sp.getInt(Prefs.POS_Y, Ui.dp(this, 80));
        lp.x = Math.max(0, Math.min(x, dm.widthPixels - Ui.dp(this, 80)));
        lp.y = Math.max(0, Math.min(y, dm.heightPixels - Ui.dp(this, 80)));

        if (!locked) {
            view.close.setOnClickListener(v -> stopSelf());
            view.setOnTouchListener(new android.view.View.OnTouchListener() {
            private float downX, downY;
            private int startX, startY;

            @Override
            public boolean onTouch(android.view.View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = startX + (int) (e.getRawX() - downX);
                        lp.y = startY + (int) (e.getRawY() - downY);
                        try {
                            wm.updateViewLayout(view, lp);
                        } catch (Exception ignored) {
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        Prefs.sp.edit().putInt(Prefs.POS_X, lp.x).putInt(Prefs.POS_Y, lp.y).apply();
                        return true;
                    default:
                        return false;
                }
            }
            });
        }

        try {
            wm.addView(view, lp);
        } catch (Exception e) { // üstte gösterme izni yoksa
            stopSelf();
            return;
        }

        final OverlayView v = view;
        monitor = new Monitor(this, s -> v.update(s));
        monitor.start();
    }

    private void teardown() {
        if (monitor != null) {
            monitor.stop();
            monitor = null;
        }
        if (view != null) {
            try {
                wm.removeView(view);
            } catch (Exception ignored) {
            }
            view = null;
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, FloatingWindow.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat),
                        getString(R.string.stop), stop).build())
                .build();
    }

    private void requestTileUpdate() {
        try {
            TileService.requestListeningState(this, new ComponentName(this, MonitorTileService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        teardown();
        stopForeground(true);
        requestTileUpdate();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
