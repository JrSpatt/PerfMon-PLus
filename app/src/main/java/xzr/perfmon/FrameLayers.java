package xzr.perfmon;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pencere odağını ve SurfaceFlinger katman adlarını aynı activity bileşenine eşler. */
final class FrameLayers {
    private static final Pattern COMPONENT = Pattern.compile(
            "(?<![A-Za-z0-9_.])([A-Za-z][A-Za-z0-9_.]*)/([A-Za-z0-9_.$]+)");
    private static final Pattern DISPLAY = Pattern.compile("\\bmDisplayId=(\\d+)");
    private static final Pattern WRAPPED = Pattern.compile(
            "^RequestedLayerState\\{(.+?#\\d+)(?:\\s+[\\w.]+\\s*=.*)?\\}$");
    private static final Pattern ID = Pattern.compile("#\\d+$");

    private static String component(String line) {
        Matcher m = COMPONENT.matcher(line);
        if (!m.find()) return null;
        String pkg = m.group(1), cls = m.group(2);
        return pkg + "/" + (cls.startsWith(".") ? pkg + cls : cls);
    }

    // null: alan bu sürümde bulunamadı. Boş: bir odak kaydı var ama hedef belirsiz.
    static String windowFocus(List<String> lines) {
        boolean primary = true, seen = false;
        String window = null, activity = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("Display:")) {
                Matcher id = DISPLAY.matcher(line);
                primary = id.find() && "0".equals(id.group(1));
            } else if (primary && line.startsWith("mCurrentFocus=")) {
                seen = true;
                window = component(line);
            } else if (primary && line.startsWith("mFocusedApp=")) {
                activity = component(line);
            }
        }
        if (!seen) return null;
        if (window == null || (activity != null && !window.equals(activity))) return "";
        return window;
    }

    static String singleActivity(List<String> lines, boolean topDump) {
        String only = null;
        for (String line : lines) {
            if (topDump ? !line.trim().startsWith("ACTIVITY ") : !line.contains("ResumedActivity")) continue;
            String found = component(line);
            if (found == null) continue;
            if (only != null && !only.equals(found)) return "";
            only = found;
        }
        return only;
    }

    static String name(String line) {
        String text = line.trim();
        if (!text.startsWith("RequestedLayerState{")) return text;
        Matcher m = WRAPPED.matcher(text);
        return m.matches() ? m.group(1) : "";
    }

    static List<String> candidates(List<String> lines, String target) {
        String pkg = target.substring(0, target.indexOf('/'));
        Set<String> surfaces = new LinkedHashSet<>(), windows = new LinkedHashSet<>();
        for (String line : lines) {
            String n = name(line);
            if (!target.equals(component(n))) continue;
            if (n.startsWith("SurfaceView[") || n.startsWith("SurfaceView - ") || n.startsWith("SurfaceView(")) {
                surfaces.add(n);
            } else if (n.startsWith(pkg + "/")) {
                windows.add(n);
            }
        }
        return new ArrayList<>(surfaces.isEmpty() ? windows : surfaces);
    }

    // Bir SurfaceView ve onun tek BLAST çocuğu aynı üreticinin iki adıdır.
    static String singleProducer(List<String> names) {
        if (names.size() == 1) return names.get(0);
        if (names.size() != 2) return null;
        String a = ID.matcher(names.get(0)).replaceFirst("");
        String b = ID.matcher(names.get(1)).replaceFirst("");
        if (a.equals(b + "(BLAST)")) return names.get(0);
        if (b.equals(a + "(BLAST)")) return names.get(1);
        return null;
    }

    static final int HWC_LIMIT = 240;

    static String visibleProducer(List<String> names, List<String> hwc) {
        if (hwc.isEmpty() || hwc.size() >= HWC_LIMIT || !hwc.get(0).trim().endsWith("HWC layers:"))
            return null;
        boolean active = false;
        int frameColumn = -1;
        String only = null;
        for (int i = 0; i < hwc.size(); i++) {
            String line = hwc.get(i).trim();
            if (line.startsWith("Display ") && line.endsWith("HWC layers:")) {
                active = line.contains("(active)");
                frameColumn = -1;
            } else if (active && line.contains("Disp Frame")) {
                String[] header = line.split("\\|");
                for (int j = 0; j < header.length; j++)
                    if (header[j].contains("Disp Frame")) frameColumn = j;
            } else if (active && names.contains(line)) {
                if (frameColumn < 0 || i + 1 >= hwc.size()) return null;
                String[] cells = hwc.get(++i).split("\\|");
                if (cells.length <= frameColumn) return null;
                String[] bounds = cells[frameColumn].trim().split("\\s+");
                if (bounds.length != 4) return null;
                try {
                    if (Integer.parseInt(bounds[2]) <= Integer.parseInt(bounds[0])
                            || Integer.parseInt(bounds[3]) <= Integer.parseInt(bounds[1])) continue;
                } catch (NumberFormatException e) { return null; }
                if (only != null && !only.equals(line)) return null;
                only = line;
            }
        }
        return only;
    }
}
