package uz.tracker.trackerproject.service;

import uz.tracker.trackerproject.enums.TransactionSubType;

/**
 * The allocation bucket a transaction credits.
 *
 * <p>Which bucket a payment funds is decided ONCE — when the row is written — and stored on the
 * row itself ({@code Transaction.allocationBucket}). It used to be re-derived on every read from
 * the target investment's {@code savingsGoal} flag, but an Investment is a long-lived holding
 * spanning many months and that flag stays editable for as long as the holding exists. Ticking
 * "savings goal" on a holding funded in an already-CLOSED month therefore emptied that month's
 * Investments bucket everywhere it is computed live (Plan, the Investments tab, Home) while
 * Months kept quoting the frozen month-close snapshot — the two disagreed about a month nobody
 * had touched. Recording the split at write time is what makes the answer stable; the flag now
 * only steers money added from that point on.
 *
 * <p>The values are plain strings rather than an enum on purpose: an {@code @Enumerated} column
 * makes Hibernate generate a CHECK constraint that {@code ddl-auto=update} never migrates when
 * the enum changes, and DataSeeder already carries two hand-written rebuilds for exactly that.
 * They match the bucket keys the API already speaks (see {@code OverviewService.getBucketPayments}).
 */
public final class AllocationBucket {

    public static final String DONATION = "DONATION";
    public static final String EMERGENCY = "EMERGENCY";
    public static final String INVESTMENTS = "INVESTMENTS";
    public static final String SAVINGS = "SAVINGS";
    public static final String STOCKS = "STOCKS";

    private AllocationBucket() { }

    /**
     * The bucket a row with this sub-type credits, or null when it funds no bucket.
     *
     * <p>This mirrors {@code OverviewService.computePaidThisMonth}'s routing exactly, so a
     * recorded bucket can never credit a different total than the live sum it replaces.
     *
     * @param savingsGoalTarget whether an INVESTMENT row funds a savings goal rather than a plain
     *        investment. Ignored for every other sub-type: an emergency-fund holding books
     *        EMERGENCY_CONTRIBUTION at write time, so its bucket follows from the sub-type alone.
     */
    public static String forSubType(TransactionSubType subType, boolean savingsGoalTarget) {
        if (subType == null) return null;
        return switch (subType) {
            case DONATION -> AllocationBucket.DONATION;
            case EMERGENCY_CONTRIBUTION -> AllocationBucket.EMERGENCY;
            case STOCK_PURCHASE -> AllocationBucket.STOCKS;
            case INVESTMENT -> savingsGoalTarget ? AllocationBucket.SAVINGS : AllocationBucket.INVESTMENTS;
            default -> null;
        };
    }
}
