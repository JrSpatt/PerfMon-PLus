package xzr.perfmon;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    @Test public void legacyAndWrappedNamesUseTheSameBuffer() {
        for (String child : Arrays.asList(BLAST, "RequestedLayerState{" + BLAST + " parentId=41}")) {
            List<String> candidates = FrameLayers.candidates(Arrays.asList(child, VIEW + "#41",
                    "Background for " + VIEW + "#40", "SurfaceView[com.game.beta/com.game.Play]#38",
                    "SurfaceView[com.game/com.game.Ad]#44", GAME + "#50"), GAME);
            assertEquals(2, candidates.size());
            assertEquals(BLAST, FrameLayers.singleProducer(candidates));
        }
    }

    @Test public void ambiguousSurfacesRequireOneVisibleBuffer() {
        String other = VIEW + "(BLAST)#45";
        List<String> names = Arrays.asList(BLAST, other);
        assertNull(FrameLayers.singleProducer(names));
        assertEquals(BLAST, FrameLayers.visibleProducer(names, Arrays.asList(HWC, HEADER, BLAST,
                "4 | 0 0 1080 2400 | 0", other, "5 | 0 0 0 0 | 0")));
        assertNull(FrameLayers.visibleProducer(names, Arrays.asList(HWC, HEADER, BLAST,
                "4 | 0 0 1080 2400 | 0", other, "5 | 0 0 180 240 | 0")));
        assertNull(FrameLayers.visibleProducer(names, Arrays.asList(HWC, HEADER, BLAST)));
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
            assertEquals(fps, FpsMeter.rate(frames, now), .01f);
        }
        assertEquals(-1f, FpsMeter.rate(Arrays.asList("1", "0 0 0", "0 9223372036854775807 0"), now), 0f);
        assertEquals(-1f, FpsMeter.rate(Arrays.asList("1", "0 7000000000 0", "0 7000000000 0"), now), 0f);
        assertEquals(0f, FpsMeter.rate(Arrays.asList("1", "0 6000000000 0", "0 6010000000 0"), now), 0f);
    }

    @Test public void thresholdZonesDoNotCountAsCpuTemperatures() {
        assertFalse(Thermal.isCpuSensor("cpu-hw-trip-0"));
        assertFalse(Thermal.isCpuSensor("cpu-hw-trip-1"));
        assertFalse(Thermal.isCpuSensor("gpuss-1"));
        assertTrue(Thermal.isCpuSensor("cpu-0-0-0"));
        assertTrue(Thermal.isCpuSensor("cpu-0-0-usr"));
    }
}
