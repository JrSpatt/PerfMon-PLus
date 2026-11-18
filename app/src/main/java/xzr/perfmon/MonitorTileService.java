package xzr.perfmon;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Hızlı Ayarlar kutucuğu: monitörü açar / kapatır. */
public class MonitorTileService extends TileService {

    @Override
    public void onStartListening() {
        render(FloatingWindow.running);
    }

    @Override
    public void onClick() {
        if (FloatingWindow.running) {
            stopService(new Intent(this, FloatingWindow.class));
            render(false);
        } else if (!Settings.canDrawOverlays(this)) {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndCollapse(i);
        } else {
            Intent i = new Intent(this, FloatingWindow.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            render(true);
        }
    }

    private void render(boolean on) {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel(getString(R.string.app_name));
        t.updateTile();
    }
}
