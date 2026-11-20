package xzr.perfmon;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.io.BufferedReader;
import java.io.StringReader;

import static org.junit.Assert.*;

public class FrameLayersTest {
    private static final String GAME = "com.game/com.game.Play";
    private static final String VIEW = "SurfaceView[" + GAME + "]";
    private static final String BLAST = VIEW + "(BLAST)#42";
    private static final String HWC = "Display 123 (active) HWC layers:";
    private static final String HEADER = "Z | Disp Frame (LTRB) | Source Crop";

    @Test public void focusedWindowResolvesTwoResumedApps() {
        List<String> activities = Arrays.asList("topResumedActivity=u0 com.chat/.Main",
                "topResumedActivity=u0 com.game/.Play", "ResumedActivity: u0 " + GAME);
        assertEquals("", FrameLayers.singleActivity(activities, false));
        assertEquals(GAME, FrameLayers.windowFocus(Arrays.asList("Display: mDisplayId=0",
                "mCurrentFocus=Window{abc u0 com.game/.Play}",
                "mFocusedApp=ActivityRecord{def u0 " + GAME + "}",
                "Display: mDisplayId=4", "mCurrentFocus=Window{abc u0 com.chat/.Main}")));
        assertEquals("", FrameLayers.windowFocus(Arrays.asList("mCurrentFocus=null",
                "mFocusedApp=ActivityRecord{def u0 " + GAME + "}")));
    }

    private static List<String> hwc(String... names) {
        List<String> lines = new ArrayList<>(Arrays.asList(HWC, HEADER));
        for (String n : names) {
            lines.add(n);
            lines.add("4 | 0 0 1080 2400 | 0");
        }
        return lines;
    }

    @Test public void wrappedGameBufferMatchesAbbreviatedHwcNameById() {
        String child = "939f110 " + BLAST;
        List<String> list = Arrays.asList("RequestedLayerState{" + child + " parentId=41}",
                "RequestedLayerState{939f110 " + VIEW + "#41 parentId=50 !handle}",
                "RequestedLayerState{" + GAME + "#50 parentId=40}");
        FrameLayers.Plan plan = FrameLayers.plan(list, GAME, hwc("939f110 Surfa[...]Play](BLAST)#42"));
        assertEquals(Collections.singletonList(child), plan.content);
        assertTrue(plan.windows.isEmpty());
        assertEquals("939f110 " + VIEW + "#41", FrameLayers.name(list.get(1)));
    }

    @Test public void wrappedRendererMustDescendFromFocusedWindow() {
        String window = GAME + "#50", child = "com.game/Renderer#53";
        List<String> list = Arrays.asList("RequestedLayerState{" + window + " parentId=1}",
                "RequestedLayerState{Bounds#51 parentId=50}", "RequestedLayerState{Container#52 parentId=51}",
                "RequestedLayerState{" + child + " parentId=52}",
                "RequestedLayerState{com.game/Renderer#54 parentId=80}",
                "RequestedLayerState{com.other/Renderer#55 parentId=50}");
        FrameLayers.Plan plan = FrameLayers.plan(list, GAME,
                hwc(window, child, "com.game/Renderer#54", "com.other/Renderer#55"));
        assertEquals(Collections.singletonList(child), plan.content);
        assertEquals(Collections.singletonList(window), plan.windows);
    }

    @Test public void flatRendererRequiresOneVisiblePackageWindow() {
        String window = GAME + "#50", child = "com.game/Renderer#53", other = "com.game/com.game.Chat#22";
        List<String> list = Arrays.asList(window, VIEW + "#41", BLAST, child, other, "com.game.beta/Renderer#55");
        assertEquals(Collections.singletonList(child), FrameLayers.plan(list, GAME, hwc(window, child)).content);
        assertTrue(FrameLayers.plan(list, GAME, hwc(window, child, other)).content.isEmpty());
        FrameLayers.Plan unverified = FrameLayers.plan(list, GAME, Collections.emptyList());
        assertEquals(Collections.singletonList(BLAST), unverified.content);
        assertTrue(unverified.visible.isEmpty());
    }

    @Test public void smallAuxiliaryBuffersAreNotMainContent() {
        String window = GAME + "#50", child = "com.game/Renderer#53";
        List<String> composition = hwc(window, child);
        composition.set(composition.size()-1, "3 | 0 0 180 240 | 0");
        FrameLayers.Plan plan = FrameLayers.plan(Arrays.asList(window, child), GAME, composition);
        assertEquals(1, plan.size());
        assertEquals(window, plan.windows.get(0));
    }

    @Test public void oldDumpCanContainHwcAfterHundredsOfLines() throws Exception {
        StringBuilder data = new StringBuilder();
        for (int i=0; i<740; i++) data.append("other dump section\n");
        List<String> expected = hwc(BLAST);
        for (String line : expected) data.append(line).append('\n');
        data.append("\nunrelated section\n");
        assertEquals(expected, FrameLayers.readHwc(new BufferedReader(new StringReader(data.toString())), 240));
        assertTrue(FrameLayers.readHwc(new BufferedReader(new StringReader(data.toString())), 3).isEmpty());
    }

    private static FrameSample frames(long last, long now) {
        return FrameSample.parse(Arrays.asList("16666666", "0 " + (last-16666667) + " 0",
                "0 " + last + " 0"), now);
    }

    @Test public void scrollingSwitchesBetweenVisibleChildAndOrdinaryWindow() {
        String window = GAME + "#50", child = "com.game/Renderer#53";
        FrameLayers.Plan p = FrameLayers.plan(Arrays.asList(window, child), GAME, hwc(window, child));
        FrameSample.Tracker track = new FrameSample.Tracker();
        Map<String,FrameSample> samples = new HashMap<>();
        long now = 8_000_000_000L;
        samples.put(child, frames(now, now));
        samples.put(window, frames(now, now));
        assertEquals(60f, track.choose(p, samples), .01f);
        assertEquals(child, track.selected);
        now += 2_000_000_000L;
        samples.put(child, frames(now-2_000_000_000L, now));
        samples.put(window, frames(now, now));
        assertEquals(60f, track.choose(p, samples), .01f);
        assertEquals(window, track.selected);
    }

    private static FrameSample sequence(long last) {
        return FrameSample.parse(Arrays.asList("16666666", "0 " + (last-33333334) + " 0",
                "0 " + (last-16666667) + " 0", "0 " + last + " 0"), last);
    }

    @Test public void oneAdvancingFrameDoesNotMaskAnotherSurfacesSequence() {
        String window = GAME + "#50", child = "com.game/Renderer#53";
        FrameLayers.Plan plan = FrameLayers.plan(Arrays.asList(window, child), GAME, hwc(window, child));
        FrameSample.Tracker track = new FrameSample.Tracker();
        Map<String, FrameSample> samples = new HashMap<>();
        long t = 8_000_000_000L;
        samples.put(child, sequence(t));
        samples.put(window, sequence(t));
        track.choose(plan, samples);
        samples.put(child, sequence(t+1));
        samples.put(window, sequence(t+500_000_000L));
        assertEquals(60f, track.choose(plan, samples), .01);
        assertEquals(window, track.selected);
        samples.put(window, sequence(t+500_000_001L));
        samples.put(child, sequence(t+950_000_000L));
        assertEquals(60f, track.choose(plan, samples), .01);
        assertEquals(child, track.selected);
    }

    @Test public void oneFreshFrameAfterALongPauseCannotProduceNearZero() {
        long t = 8_000_000_000L;
        FrameSample isolated = FrameSample.parse(Arrays.asList("1", "0 1000000000 0", "0 "+t+" 0"), t);
        assertEquals(-1f, isolated.fps, 0);
        FrameSample slow = FrameSample.parse(Arrays.asList("1", "0 6900000000 0", "0 "+t+" 0"), t);
        assertEquals(1f/1.1f, slow.fps, .001);
    }

    @Test public void zeroRequiresAnUnchangedPreviouslyMeasuredVisibleBuffer() {
        String window = GAME + "#50";
        long t = 8_000_000_000L;
        List<String> names = Collections.singletonList(window);
        FrameLayers.Plan visible = FrameLayers.plan(names, GAME, hwc(window));
        FrameLayers.Plan unknown = FrameLayers.plan(names, GAME, Collections.emptyList());
        FrameSample.Tracker track = new FrameSample.Tracker();
        Map<String,FrameSample> map = new HashMap<>();
        map.put(window, frames(t-2_000_000_000L,t));
        assertEquals(-1f, track.choose(visible,map),0);
        map.put(window, frames(t,t));
        assertEquals(60f, track.choose(visible,map),.01);
        map.put(window, frames(t,t+2_000_000_000L));
        assertEquals(0f, track.choose(visible,map),0);
        assertEquals(-1f, track.choose(unknown,map),0);
        map.put(window, FrameSample.MISSING);
        assertEquals(-1f, track.choose(visible,map),0);
    }

    @Test public void isolatedNewFrameOnAlternativePreventsReportingZero() {
        long t = 8_000_000_000L;
        String window = GAME+"#50", child = "com.game/Renderer#53";
        FrameLayers.Plan plan = FrameLayers.plan(Arrays.asList(window, child), GAME, hwc(window, child));
        FrameSample.Tracker track = new FrameSample.Tracker();
        Map<String,FrameSample> samples = new HashMap<>();
        samples.put(child, frames(t,t));
        samples.put(window, frames(t-3_000_000_000L,t));
        assertEquals(60f, track.choose(plan,samples), .01);
        long later = t+2_000_000_000L;
        samples.put(child, frames(t,later));
        samples.put(window, FrameSample.parse(Arrays.asList("1","0 "+(t-3_000_000_000L)+" 0",
                "0 "+later+" 0"),later));
        assertEquals(-1f, track.choose(plan,samples), 0);
    }

    @Test public void twoMovingComparableChildrenRemainAmbiguous() {
        long t = 8_000_000_000L;
        String a = VIEW+"(BLAST)#10", b = VIEW+"(BLAST)#11";
        List<String> names = Arrays.asList(a,b);
        FrameLayers.Plan plan = FrameLayers.plan(names, GAME, hwc(a,b));
        Map<String, FrameSample> samples = new HashMap<>();
        samples.put(a,frames(t,t));
        samples.put(b,frames(t-1,t));
        FrameSample.Tracker track = new FrameSample.Tracker();
        assertEquals(-1f,track.choose(plan,samples),0);
        assertTrue(track.ambiguous);
    }

    @Test public void highRefreshRatesRemainAccurateWithDuplicateTimestamps() {
        long now = 8_000_000_000L;
        for (int fps : new int[]{30, 60, 90, 120, 144, 165}) {
            List<String> frames = new ArrayList<>();
            frames.add("16666666");
            for (int i = 0; i < 127; i++) {
                long timestamp = now - Math.round((126 - i) * 1e9 / fps);
                frames.add("1 " + timestamp + " 1");
            }
            frames.add(frames.get(126));
            assertEquals(fps, FrameSample.parse(frames, now).fps, .01f);
        }
        assertEquals(-1f, FrameSample.parse(Arrays.asList("1", "0 0 0", "0 9223372036854775807 0"), now).fps, 0f);
        assertEquals(-1f, FrameSample.parse(Arrays.asList("1", "0 7000000000 0", "0 7000000000 0"), now).fps, 0f);
        assertEquals(-1f, FrameSample.parse(Arrays.asList("1", "0 6000000000 0", "0 6010000000 0"), now).fps, 0f);
    }

    @Test public void thresholdZonesDoNotCountAsCpuTemperatures() {
        assertFalse(Thermal.isCpuSensor("cpu-hw-trip-0"));
        assertFalse(Thermal.isCpuSensor("cpu-hw-trip-1"));
        assertFalse(Thermal.isCpuSensor("gpuss-1"));
        assertTrue(Thermal.isCpuSensor("cpu-0-0-0"));
        assertTrue(Thermal.isCpuSensor("cpu-0-0-usr"));
    }
}
