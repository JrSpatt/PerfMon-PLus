package xzr.perfmon;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.View;

/** Konum birimi göstergenin kullanılabilir hareket alanının oranıdır. */
final class PositionMap extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF screen = new RectF(), marker = new RectF();
    private boolean landscape, moving;
    private float xFraction, yFraction, grabX, grabY;

    PositionMap(Context c, boolean landscape) {
        super(c);
        setFocusable(true);
        setClickable(true);
        select(landscape);
    }

    void select(boolean horizontal) {
        landscape = horizontal;
        moving = false;
        setContentDescription(getContext().getString(horizontal ? R.string.landscape : R.string.portrait)
                + " · " + getContext().getString(R.string.position));
        refresh();
    }

    void refresh() {
        if (moving) return;
        PointF p = OverlayPosition.read(landscape);
        xFraction = p.x;
        yFraction = p.y;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        Point area = OverlayPosition.area(getContext());
        float shortSide = Math.min(area.x, area.y), longSide = Math.max(area.x, area.y);
        float ratio = landscape ? longSide / shortSide : shortSide / longSide;
        float inset = Ui.dp(getContext(), 8);
        float w = Math.min(getWidth() - 2 * inset, (getHeight() - 2 * inset) * ratio);
        float h = w / ratio;
        screen.set((getWidth() - w) / 2f, (getHeight() - h) / 2f, (getWidth() + w) / 2f, (getHeight() + h) / 2f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Theme.BG);
        c.drawRoundRect(screen, inset, inset, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(getContext(), 1));
        paint.setColor(Theme.MUTED);
        c.drawRoundRect(screen, inset, inset, paint);
        paint.setStyle(Paint.Style.FILL);
        float scale = w / (landscape ? longSide : shortSide);
        float mw = Math.min(w, Math.max(Ui.dp(getContext(), 12), FloatingWindow.markerWidth(getContext()) * scale));
        float mh = Math.min(h, Math.max(Ui.dp(getContext(), 8), FloatingWindow.markerHeight(getContext()) * scale));
        float x = screen.left + xFraction * (w - mw), y = screen.top + yFraction * (h - mh);
        marker.set(x, y, x + mw, y + mh);
        paint.setColor(Theme.ACCENT);
        c.drawRoundRect(marker, 3, 3, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!screen.contains(x, y)) return false;
                moving = true;
                boolean hit = marker.contains(x, y);
                grabX = hit ? x - marker.left : marker.width() / 2;
                grabY = hit ? y - marker.top : marker.height() / 2;
                getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                if (!moving) return false;
                break;
            case MotionEvent.ACTION_CANCEL:
                moving = false;
                refresh();
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default: return false;
        }
        xFraction = OverlayPosition.unit((x - grabX - screen.left) / Math.max(1f, screen.width() - marker.width()));
        yFraction = OverlayPosition.unit((y - grabY - screen.top) / Math.max(1f, screen.height() - marker.height()));
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            moving = false;
            save();
            getParent().requestDisallowInterceptTouchEvent(false);
            performClick();
        }
        invalidate();
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: xFraction = OverlayPosition.unit(xFraction - .025f); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: xFraction = OverlayPosition.unit(xFraction + .025f); break;
            case KeyEvent.KEYCODE_DPAD_UP: yFraction = OverlayPosition.unit(yFraction - .025f); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: yFraction = OverlayPosition.unit(yFraction + .025f); break;
            default: return super.onKeyDown(code, e);
        }
        save();
        invalidate();
        return true;
    }

    private void save() {
        OverlayPosition.save(landscape, xFraction, yFraction);
        FloatingWindow.positionChanged(landscape);
    }
}
