package xzr.perfmon;

/** Yalnızca son ölçümleri bellekte tutar. Activity açıldığında dış uygulamanın kaydı korunur. */
final class FrameHistory {
    FpsMeter.Reading current = FpsMeter.Reading.IDLE;
    FpsMeter.Reading lastFallback, lastApp;

    void accept(FpsMeter.Reading r, String ownPackage, boolean settingsVisible) {
        current = r;
        if (r == FpsMeter.Reading.IDLE || ownPackage.equals(r.pkg)) return;
        if (r.pkg == null && settingsVisible) return;
        if (r.isApp()) lastApp = r;
        else if (r.reason != 0) lastFallback = r;
    }
}
