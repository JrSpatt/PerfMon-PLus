package xzr.perfmon;

import android.content.Context;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

final class OverlayPosition {
    static Point area(Context context) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics metrics = wm.getMaximumWindowMetrics();
            Rect b = metrics.getBounds();
            android.graphics.Insets i = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            return new Point(Math.max(1, b.width() - i.left - i.right),
                    Math.max(1, b.height() - i.top - i.bottom));
        }
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(m);
        return new Point(m.widthPixels, m.heightPixels);
    }

    static PointF read(boolean landscape) {
        String key = landscape ? "landscape_" : "portrait_";
        if (!Prefs.sp.contains(key + "x")) key = landscape ? "portrait_" : "landscape_";
        return new PointF(unit(Prefs.sp.getFloat(key + "x", .04f)),
                unit(Prefs.sp.getFloat(key + "y", .10f)));
    }

    static PointF activate(boolean landscape) {
        PointF p = read(landscape);
        if (!Prefs.sp.contains(landscape ? "landscape_x" : "portrait_x")) save(landscape, p.x, p.y);
        return p;
    }

    static void save(boolean landscape, float x, float y) {
        String key = landscape ? "landscape_" : "portrait_";
        Prefs.sp.edit().putFloat(key + "x", unit(x)).putFloat(key + "y", unit(y)).apply();
    }

    static float unit(float n) { return Math.max(0f, Math.min(1f, n)); }
}
