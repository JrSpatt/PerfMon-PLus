package xzr.perfmon;

import java.util.ArrayList;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
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
            "^RequestedLayerState\\{(.+#[0-9]+)(?:\\s+.*)?\\}$");
    private static final Pattern ID = Pattern.compile("#\\d+$");
    private static final Pattern PARENT = Pattern.compile("\\bparentId=(\\d+)");
    private static final Pattern VIEW = Pattern.compile("^(?:[0-9a-fA-F]+ )?SurfaceView(?:\\[| - |\\()");
    static final int HWC_LIMIT = 240;

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

    static final class Plan {
        final List<String> content = new ArrayList<>(), windows = new ArrayList<>();
        final Set<String> visible = new HashSet<>();
        int size() { return content.size() + windows.size(); }

        boolean fastPath(String name) {
            return name != null && (size() == 1 || content.size() == 1 && name.equals(content.get(0)));
        }
    }

    private static String id(String name) {
        int hash = name.lastIndexOf('#');
        if (hash < 0 || hash + 1 == name.length()) return "";
        for (int i = hash + 1; i < name.length(); i++)
            if (!Character.isDigit(name.charAt(i))) return "";
        return name.substring(hash + 1);
    }

    // Eski sürümlerde --hwclayers tüm dökümü döndürebilir. Yalnızca aktif tablonun satırlarını geçir.
    static List<String> readHwc(BufferedReader input, int maxLines) throws IOException {
        List<String> table = new ArrayList<>();
        int chars = 0;
        for (int i = 0; i < 12000; i++) {
            String line = input.readLine();
            if (line == null) return table;
            chars += line.length() + 1;
            if (chars > 1_500_000) break;
            String trim = line.trim();
            if (table.isEmpty()) {
                if (trim.startsWith("Display ") && trim.endsWith("(active) HWC layers:")) table.add(trim);
            } else {
                if (trim.isEmpty() || trim.startsWith("Display ")) return table;
                table.add(line);
                if (table.size() >= Math.min(HWC_LIMIT, maxLines)) break;
            }
        }
        return Collections.emptyList();
    }

    private static Map<String, Long> areas(List<String> hwc) {
        if (hwc.isEmpty() || !hwc.get(0).trim().endsWith("(active) HWC layers:")
                || hwc.size() >= HWC_LIMIT) return null;
        Map<String, Long> visible = new HashMap<>();
        int col = -1;
        for (int i = 1; i < hwc.size(); i++) {
            String line = hwc.get(i).trim();
            if (line.contains("Disp Frame")) {
                String[] header = line.split("\\|");
                for (int j = 0; j < header.length; j++)
                    if (header[j].contains("Disp Frame")) col = j;
                continue;
            }
            // HWC uzun adların ortasını [...] ile kısaltır. Sondaki SurfaceFlinger kimliği korunur.
            String key = id(line);
            if (key.isEmpty()) continue;
            if (col < 0 || i + 1 >= hwc.size()) return null;
            String[] cells = hwc.get(++i).split("\\|");
            if (col >= cells.length) return null;
            String[] bounds = cells[col].trim().split("\\s+");
            if (bounds.length != 4) return null;
            try {
                long w = (long) Integer.parseInt(bounds[2]) - Integer.parseInt(bounds[0]);
                long h = (long) Integer.parseInt(bounds[3]) - Integer.parseInt(bounds[1]);
                if (w > 0 && h > 0 && w <= Integer.MAX_VALUE && h <= Integer.MAX_VALUE)
                    visible.put(key, w * h);
            } catch (NumberFormatException ignored) { return null; }
        }
        return col < 0 ? null : visible;
    }

    private static boolean attached(String key, Map<String,String> parents, Set<String> roots) {
        for (int depth = 0; key != null && depth < 64; depth++) {
            if (roots.contains(key)) return true;
            key = parents.get(key);
        }
        return false;
    }

    static Plan plan(List<String> listed, String target, List<String> hwc) {
        String pkg = target.substring(0, target.indexOf('/'));
        Map<String,Long> areas = areas(hwc);
        Map<String,String> parents = new HashMap<>();
        Set<String> roots = new HashSet<>(), surfaces = new LinkedHashSet<>(), windows = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        boolean wrapped = false;
        int visiblePackageWindows = 0;
        for (String raw : listed) {
            String n = name(raw);
            String key = id(n);
            if (key.isEmpty()) continue;
            names.add(n);
            if (raw.trim().startsWith("RequestedLayerState{")) {
                wrapped = true;
                Matcher m = PARENT.matcher(raw.substring(raw.indexOf(n) + n.length()));
                if (m.find()) parents.put(key, m.group(1));
            }
            String owner = component(n);
            if (n.startsWith(pkg + "/")) {
                if (target.equals(owner)) {
                    windows.add(n);
                    roots.add(key);
                }
                if (areas != null && areas.containsKey(key) && owner != null
                        && owner.substring(owner.indexOf('/') + 1).contains(".")) visiblePackageWindows++;
            } else if (target.equals(owner) && VIEW.matcher(n).find()) {
                surfaces.add(n);
                roots.add(key);
            }
        }
        Plan plan = new Plan();
        if (areas == null) {
            // Kompozisyon görünürlüğü bilinmiyorsa anonim alt katmanları veya durağan kareleri doğrulamayız.
            for (String n : surfaces) {
                String stem = ID.matcher(n).replaceFirst("");
                boolean hasChild = false;
                for (String s : surfaces)
                    if (ID.matcher(s).replaceFirst("").equals(stem + "(BLAST)")) { hasChild = true; break; }
                if (!hasChild) plan.content.add(n);
            }
            plan.windows.addAll(windows);
            return plan;
        }
        boolean legacyChild = !wrapped && visiblePackageWindows == 1;
        boolean mainVisible = false;
        for (String n : windows) if (areas.containsKey(id(n))) mainVisible = true;
        long largest = 0;
        for (String n : names) {
            String key = id(n);
            Long area = areas.get(key);
            if (area == null || n.startsWith("Background for ") || n.startsWith("Bounds for ")) continue;
            if (windows.contains(n)) {
                plan.windows.add(n);
            } else {
                String owner = component(n);
                if (owner != null && !owner.startsWith(pkg + "/")) continue;
                boolean linked = wrapped && attached(key, parents, roots);
                boolean flatBuffer = legacyChild && mainVisible && n.startsWith(pkg + "/") && owner != null
                        && !owner.substring(owner.indexOf('/') + 1).contains(".");
                if (!surfaces.contains(n) && !linked && !flatBuffer) continue;
                plan.content.add(n);
            }
            largest = Math.max(largest, area);
            plan.visible.add(n);
        }
        // Küçük yardımcı yüzeyler, görünür uygulama alanının çoğunu kaplayan içerikle yarışmaz.
        final long minArea = (largest + 1) / 2;
        plan.content.removeIf(n -> areas.get(id(n)) < minArea);
        plan.windows.removeIf(n -> areas.get(id(n)) < minArea);
        return plan;
    }
}
