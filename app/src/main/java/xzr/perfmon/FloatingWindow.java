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
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.PointF;
import android.hardware.input.InputManager;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.WindowInsets;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/** Kayan monitör penceresini ve onu canlı tutan ön plan bildirimini yönetir. */
public class FloatingWindow extends Service {
    static final String ACTION_STOP = "com.spatt.perfmon.STOP";
    private static final String CHANNEL = "perfmon";
    static volatile boolean running;
    // Activity and service receive state changes on the Android main thread.
    static Runnable onChange;
    private static FloatingWindow active;
    private Context overlayContext;
    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private OverlayView view;
    private Monitor monitor;
    private Point area;
    private boolean landscape, dragging;
    static final FrameHistory frames = new FrameHistory();
    static int startError;
    private float windowOpacity = 1;


    static int markerWidth(Context c) {
        return active == null || active.view == null || active.view.getWidth() == 0 ? Ui.dp(c, 82) : active.view.getWidth();
    }

    static int markerHeight(Context c) {
        return active == null || active.view == null || active.view.getHeight() == 0 ? Ui.dp(c, 56) : active.view.getHeight();
    }

    static void reload(Context c) {
        if (active != null) active.rebuildMetrics();
    }

    static void appearance(String key, int value) {
        if (active == null || active.view == null) return;
        if (Prefs.SCALE.equals(key)) active.view.setScalePercent(value);
        else if (Prefs.ALPHA.equals(key)) active.setOpacity(value);
    }

    static void lockChanged() {
        if (active == null || active.view == null) return;
        active.dragging = false;
        active.applyLock();
        active.wm.updateViewLayout(active.view, active.lp);
        active.position();
        notifyState();
    }

    static void positionChanged(boolean horizontal) {
        if (active != null && active.landscape == horizontal) active.position();
    }

    private static void notifyState() {
        if (onChange != null) onChange.run();
    }

    @Override public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        ShizukuBridge.init(this);
        startForeground(1, buildNotification());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
        } else if (view == null) {
            startError = 0;
            Support.probe(this);
            rebuildMetrics();
        }
        return START_NOT_STICKY;
    }

    private void rebuildMetrics() {
        teardown();
        try {
            if (wm == null) prepareWindowContext();
            attachWindow();
        } catch (RuntimeException e) {
            teardown();
            running = false;
            active = null;
            startError = !android.provider.Settings.canDrawOverlays(this) ? R.string.overlay_needed
                    : e instanceof WindowManager.InvalidDisplayException ? R.string.window_display_error
                    : R.string.window_open_error;
            android.widget.Toast.makeText(this, startError, android.widget.Toast.LENGTH_LONG).show();
            notifyState();
            stopSelf();
            return;
        }
        active = this;
        running = true;
        frames.current = FpsMeter.Reading.IDLE;
        ShizukuBridge.setMonitoring(true);
        monitor = new Monitor(this, s -> {
            view.update(s);
            int previous = frames.current.mode;
            frames.accept(s.frame, getPackageName(), onChange != null);
            if (previous != s.frame.mode) notifyState();
        });
        monitor.start();
        notifyState();
        requestTileUpdate();
    }

    private void prepareWindowContext() {
        overlayContext = this;
        if (Build.VERSION.SDK_INT >= 30) {
            Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            if (display != null) {
                overlayContext = createDisplayContext(display)
                        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            }
        }
        wm = (WindowManager) overlayContext.getSystemService(WINDOW_SERVICE);
    }

    private void attachWindow() {
        area = OverlayPosition.area(overlayContext);
        landscape = area.x > area.y;
        view = new OverlayView(overlayContext);
        lp = new WindowManager.LayoutParams(-2, -2, Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 30) {
            lp.setFitInsetsTypes(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            lp.setFitInsetsIgnoringVisibility(true);
        }
        applyLock();
        view.close.setOnClickListener(v -> stopSelf());
        view.pin.setOnClickListener(v -> {
            savePosition();
            Prefs.sp.edit().putBoolean(Prefs.LOCK, true).apply();
            lockChanged();
        });
        view.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dragging = true;
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!dragging) return false;
                        move(Math.round(startX + e.getRawX() - downX), Math.round(startY + e.getRawY() - downY));
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging) savePosition();
                        dragging = false;
                        notifyState();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        dragging = false;
                        position();
                        return true;
                    default: return false;
                }
            }
        });
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (v == view && !dragging && (r - l != or - ol || b - t != ob - ot)) {
                v.post(() -> { if (v == view && !dragging) position(); });
            }
        });
        view.measure(View.MeasureSpec.makeMeasureSpec(area.x, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(area.y, View.MeasureSpec.AT_MOST));
        PointF pos = OverlayPosition.activate(landscape);
        lp.x = Math.round(pos.x * travelX());
        lp.y = Math.round(pos.y * travelY());
        wm.addView(view, lp);
    }

    private void applyLock() {
        boolean locked = Prefs.on(Prefs.LOCK, false);
        view.setLocked(locked);
        windowOpacity = 1;
        if (locked) {
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            if (Build.VERSION.SDK_INT >= 31) {
                InputManager im = getSystemService(InputManager.class);
                windowOpacity = im.getMaximumObscuringOpacityForTouch();
            }
        } else {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        lp.alpha = windowOpacity;
        setOpacity(Prefs.num(Prefs.ALPHA, Prefs.ALPHA_DEF));
    }

    private void setOpacity(int percent) {
        view.setBackgroundOpacity(Math.min(1f, percent / (100f * Math.max(.01f, windowOpacity))));
    }

    private int travelX() { return Math.max(0, area.x - view.getMeasuredWidth()); }
    private int travelY() { return Math.max(0, area.y - view.getMeasuredHeight()); }

    private void savePosition() {
        OverlayPosition.save(landscape, lp.x / (float) Math.max(1, travelX()),
                lp.y / (float) Math.max(1, travelY()));
    }

    private void position() {
        if (view == null || !view.isAttachedToWindow()) return;
        PointF p = OverlayPosition.read(landscape);
        move(Math.round(p.x * travelX()), Math.round(p.y * travelY()));
    }

    private void move(int x, int y) {
        x = Math.max(0, Math.min(x, travelX()));
        y = Math.max(0, Math.min(y, travelY()));
        if (lp.x == x && lp.y == y) return;
        lp.x = x;
        lp.y = y;
        wm.updateViewLayout(view, lp);
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (view == null) return;
        dragging = false;
        area = OverlayPosition.area(overlayContext);
        landscape = area.x > area.y;
        OverlayPosition.activate(landscape);
        position();
        view.post(this::position);
    }

    private void teardown() {
        dragging = false;
        if (monitor != null) {
            monitor.stop();
            monitor = null;
        }
        if (view != null && view.isAttachedToWindow()) wm.removeViewImmediate(view);
        view = null;
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
        active = null;
        ShizukuBridge.setMonitoring(false);
        frames.current = FpsMeter.Reading.IDLE;
        teardown();
        notifyState();
        stopForeground(true);
        requestTileUpdate();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
