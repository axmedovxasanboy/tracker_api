package uz.tracker.trackerproject.service;

import uz.tracker.trackerproject.entity.StableIncomeEntry;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * The monthly income month by month — what {@code stableIncomeFor(month)} answers from. The value for
 * month M is the entry with the latest month ≤ M; before the first entry, the first entry's; with no
 * entry at all, the single value Settings holds (null stays null). Immutable: read once per request
 * and asked as often as a loop over months needs.
 */
public final class StableIncomeSchedule {

    private final NavigableMap<YearMonth, BigDecimal> entries;
    private final BigDecimal fallback;

    private StableIncomeSchedule(NavigableMap<YearMonth, BigDecimal> entries, BigDecimal fallback) {
        this.entries = Collections.unmodifiableNavigableMap(entries);
        this.fallback = fallback;
    }

    /** From the stored entries, with Settings' single value for when there is none. */
    public static StableIncomeSchedule of(List<StableIncomeEntry> rows, BigDecimal fallback) {
        NavigableMap<YearMonth, BigDecimal> entries = new TreeMap<>();
        for (StableIncomeEntry e : rows == null ? List.<StableIncomeEntry>of() : rows) {
            if (e.getMonth() != null && e.getAmount() != null) entries.put(YearMonth.from(e.getMonth()), e.getAmount());
        }
        return new StableIncomeSchedule(entries, fallback);
    }

    /** One value for every month — how the income behaved before it had a history. */
    public static StableIncomeSchedule single(BigDecimal amount) {
        return new StableIncomeSchedule(new TreeMap<>(), amount);
    }

    /** The monthly income for {@code month}; null when none is set. */
    public BigDecimal amountFor(YearMonth month) {
        if (entries.isEmpty()) return fallback;
        Map.Entry<YearMonth, BigDecimal> e = entries.floorEntry(month);
        return e != null ? e.getValue() : entries.firstEntry().getValue();
    }

    /** Whether {@code month} has a positive income — every month-scoped figure needs one. */
    public boolean isSet(YearMonth month) {
        BigDecimal v = amountFor(month);
        return v != null && v.signum() > 0;
    }

    /** The entries, oldest first (empty when the income has no history). */
    public NavigableMap<YearMonth, BigDecimal> entries() {
        return entries;
    }
}
