package uz.tracker.trackerproject.service;

import uz.tracker.trackerproject.entity.Investment;
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

    /**
     * The sub-type a payment INTO AN EXISTING HOLDING is booked as: EMERGENCY_CONTRIBUTION when the
     * holding is an emergency fund, INVESTMENT when it is any other holding. Every other sub-type,
     * and a payment with no holding behind it, keeps the sub-type it was sent with.
     *
     * <p>The Investments tab and the Plan's Record button already booked a top-up this way, but the
     * Transactions page and the bot let an emergency fund be picked under "Investment" and saved the
     * row as sent — so the money was counted in the Investments bucket while the fund grew and the
     * allocation preview promised Emergency. Deciding it here, from the holding, is what makes the
     * write paths and the preview agree.
     */
    public static TransactionSubType forHolding(TransactionSubType requested, Investment holding) {
        if (holding == null) return requested;
        if (requested != TransactionSubType.INVESTMENT
                && requested != TransactionSubType.EMERGENCY_CONTRIBUTION) return requested;
        return Boolean.TRUE.equals(holding.getEmergencyFund())
                ? TransactionSubType.EMERGENCY_CONTRIBUTION : TransactionSubType.INVESTMENT;
    }
}
