package uz.tracker.trackerproject.enums;

import java.util.List;

/**
 * Currencies a balance may be denominated in.
 *
 * <p>UZS is the app's reporting currency: every total, the net worth figure and the whole
 * allocation engine are computed in UZS and filter by it. USD and EUR exist ONLY as
 * standalone cash pots — somewhere to record foreign notes you are holding. Nothing
 * converts between currencies anywhere in the codebase, so a foreign pot deliberately
 * does not roll up into any UZS figure; showing it as a converted amount would mean
 * inventing a rate, and a wrong rate is worse than an absent one.
 *
 * <p>Cards remain UZS-only, so a transfer never crosses currencies.
 */
public enum Currency {
    UZS,
    USD,
    EUR;

    /**
     * The currencies that roll up into the app's books — UZS alone.
     *
     * <p>Any loop that AGGREGATES money into a single figure must iterate this rather than
     * {@link #values()}. Before USD/EUR existed the two were identical, so several such loops
     * were written against values(); adding a member silently turned each of them into a
     * cross-currency sum with no exchange rate — foreign notes counted as if they were som.
     */
    public static List<Currency> reporting() {
        return List.of(UZS);
    }
}
