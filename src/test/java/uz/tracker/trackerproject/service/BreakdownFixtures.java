package uz.tracker.trackerproject.service;

/** {@link OwnerLiveFixture} for tests outside this package (the controller's, over HTTP). */
public final class BreakdownFixtures {

    private BreakdownFixtures() { }

    /** The V2 breakdown over the owner's live data of 2 October 2026. */
    public static AnalyticsBreakdownService ownerLive() {
        return new OwnerLiveFixture().breakdown;
    }
}
