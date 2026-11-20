package xzr.perfmon;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bir katmanın SurfaceFlinger sunum geçmişi ve aynı odaktaki ardışık ölçümler. */
final class FrameSample {
    static final FrameSample MISSING = new FrameSample(0, 0, -1);
    final long latest;
    private final long thirdRecent;
    final float fps;

    private FrameSample(long latest, long thirdRecent, float fps) {
        this.thirdRecent = thirdRecent;
        this.latest = latest;
        this.fps = fps;
    }

    static FrameSample parse(List<String> lines, long now) {
        long[] times = new long[lines.size()];
        int count = 0;
        for (int i = 1; i < lines.size(); i++) {
            String[] fields = lines.get(i).trim().split("\\s+");
            if (fields.length < 3) continue;
            try {
                long t = Long.parseLong(fields[1]);
                if (t > 0 && t != Long.MAX_VALUE && t <= now) times[count++] = t;
            } catch (NumberFormatException ignored) { }
        }
        if (count < 2) return MISSING;
        Arrays.sort(times, 0, count);
        int unique = 1;
        for (int i = 1; i < count; i++)
            if (times[i] != times[unique - 1]) times[unique++] = times[i];
        if (unique < 2) return MISSING;
        long last = times[unique - 1];
        if (now - last > 1_200_000_000L) return new FrameSample(last, 0, -1);
        int first = unique - 2;
        while (first > 0 && times[first - 1] > last - 1_000_000_000L) first--;
        if (last - times[first] > 1_200_000_000L) return new FrameSample(last, 0, -1);
        long third = unique - first >= 3 ? times[unique - 3] : 0;
        return new FrameSample(last, third, (unique - first - 1) * 1_000_000_000f / (last - times[first]));
    }

    static final class Tracker {
        private final Map<String, Long> lastSeen = new HashMap<>();
        String selected;
        boolean ambiguous;

        boolean advances(String name, FrameSample sample) {
            Long previous = lastSeen.get(name);
            return sample.fps > 0 && (previous == null || sample.latest > previous);
        }

        boolean hasSequence(String name, FrameSample sample) {
            Long previous = lastSeen.get(name);
            return sample.thirdRecent > (previous == null ? 0 : previous);
        }

        float accept(String name, FrameSample sample) {
            selected = name;
            ambiguous = false;
            lastSeen.put(name, sample.latest);
            return sample.fps;
        }

        float choose(FrameLayers.Plan plan, Map<String,FrameSample> samples) {
            ambiguous = false;
            String choice = moving(plan.content, samples, true);
            if (choice == null && !ambiguous) choice = moving(plan.windows, samples, true);
            if (choice == null && !ambiguous) choice = moving(plan.content, samples, false);
            if (choice == null && !ambiguous) choice = moving(plan.windows, samples, false);
            float value = -1;
            if (choice != null) {
                selected = choice;
                value = samples.get(choice).fps;
            } else if (!ambiguous && selected != null && plan.visible.contains(selected)
                    && (plan.content.contains(selected) || plan.windows.contains(selected))) {
                FrameSample s = samples.get(selected);
                Long previous = lastSeen.get(selected);
                if (s != null && previous != null && s.latest > 0 && s.latest == previous)
                    value = s.fps > 0 ? s.fps : (allUnchanged(samples) ? 0 : -1);
            }
            if (value < 0) selected = null;
            lastSeen.clear();
            for (Map.Entry<String,FrameSample> entry : samples.entrySet())
                if (entry.getValue().latest > 0) lastSeen.put(entry.getKey(), entry.getValue().latest);
            return value;
        }

        private boolean allUnchanged(Map<String,FrameSample> samples) {
            for (Map.Entry<String,FrameSample> entry : samples.entrySet()) {
                Long previous = lastSeen.get(entry.getKey());
                if (previous == null || entry.getValue().latest <= 0 || previous != entry.getValue().latest)
                    return false;
            }
            return true;
        }

        private String moving(List<String> names, Map<String,FrameSample> samples, boolean requireSequence) {
            String choice = null;
            for (String n : names) {
                FrameSample s = samples.get(n);
                if (s == null || !advances(n, s) || (requireSequence && !hasSequence(n, s))) continue;
                if (choice != null) { ambiguous = true; return null; }
                choice = n;
            }
            return choice;
        }
    }
}
