package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Per-tier allocation recommendation. The bucket-level lines describe minimum
 * percentages of stable income to set aside; {@code notes} carries scenario-specific
 * action items (e.g. "pay at least 34% of personal loans this month").
 *
 * For Level 1, the rules are hard-coded server-side per the owner's spec. For
 * Levels 2-6, the user will configure their own percentages — until then the
 * service returns scenarioKey = null + a note explaining that.
 */
@Getter @Builder
public class TierAllocation {

    /**
     * Short identifier for the rule branch hit (e.g. "1.1", "1.2.1.tight",
     * "1.2.3.comfortable", "1.3"). Null when guidance hasn't been defined for
     * the tier yet (Levels 2-6 today).
     */
    private String scenarioKey;

    /** Short human-readable label for the scenario. */
    private String scenarioLabel;

    /** One line per bucket — Donation, Emergency, Investments, Stocks. */
    private List<AllocationLine> lines;

    /**
     * Scenario-specific action items. Each entry can be informational (just text) or
     * actionable — when {@code action} is non-null the frontend renders a Pay button
     * and, when {@code paid} + {@code target} are non-null, a small progress bar so
     * the user can see how much they've already paid this month.
     */
    private List<ActionItem> actions;

    /**
     * True when the user still has actionable items (bank / personal-loan payments) below
     * their recommended unlock amount. While locked, the frontend disables recording into
     * the allocation buckets — debts come first. False when there are no actionable items
     * or they've all reached their {@link ActionItem#unlockThreshold}.
     */
    private boolean allocationLocked;

    @Getter @Builder
    public static class ActionItem {
        /**
         * The sentence in English. Kept as the fallback for a client that has no entry for
         * {@link #code} — an older frontend, or the one item that deliberately has no code.
         */
        private String text;

        /**
         * Translation key for {@link #text}, e.g. "page.plan.note.heavyDebt". Every sentence this
         * service composes carries one, because the sentences are built here as English prose and
         * a client cannot translate prose. Null for exactly one item: the user's OWN note from the
         * allocation-rules editor — those are their words, in whichever language they typed them,
         * and a key would print somebody else's sentence instead.
         */
        private String code;

        /**
         * Values to interpolate into {@code code}'s sentence by name ("{amount}", "{month}").
         * Never English prose, so a client can render the whole sentence in its own language:
         * amounts arrive already grouped and with their unit ("500 000 UZS"), months as ISO
         * "YYYY-MM" for the client to spell out, names verbatim (they are the user's own data).
         * Empty rather than null when the sentence takes no values.
         */
        private Map<String, String> params;

        /** Action key for the frontend: "PAY_BANK", "PAY_PERSONAL_LOAN", or null (informational only). */
        private String action;

        /** Sum already paid this month for the action's target, in the display currency. Null when irrelevant. */
        private BigDecimal paid;

        /** Recommended amount this month for the action's target, in the display currency. Null when irrelevant. */
        private BigDecimal target;

        /**
         * Amount that must be paid this month for this action to count as "met" and stop
         * locking the allocation buckets, in the display currency. Bank installments unlock
         * at 90% of {@link #target} (the average monthly amount); personal loans require the
         * full {@link #target} (the 34% pay-down). Null for informational items.
         */
        private BigDecimal unlockThreshold;
    }

    @Getter @Builder
    public static class AllocationLine {
        /** Stable identifier for the bucket: DONATION / EMERGENCY / INVESTMENTS / STOCKS. */
        private String bucket;

        /** Human label — e.g. "Donation". */
        private String label;

        /** True → show with min %/amount; false → render as "NO NEED" (skipped at this tier). */
        private boolean recommended;

        /** Minimum percent of stable income, e.g. 10.0 for 10%. Null when not recommended. */
        private BigDecimal minPercent;

        /** Minimum amount in the requested display currency. Null when not recommended. */
        private BigDecimal minAmount;

        /**
         * Sum already paid this month for this bucket, in the requested display currency.
         * Donations / Emergencies / Investments(non-stocks) / Stocks sourced from their
         * respective entities filtered by date, PLUS any "already paid" marks for the month.
         */
        private BigDecimal paidAmount;

        /**
         * The portion of {@link #paidAmount} the user declared "already paid" with no
         * transaction behind it, so no tracked money moved. paidAmount = recorded + markedAmount.
         * Surfaced separately so the UI can say which half of the figure left a wallet — the
         * month-close snapshot only ever counts the recorded half.
         */
        private BigDecimal markedAmount;

        /** paidAmount / minAmount × 100. Can exceed 100. Null when not recommended. */
        private BigDecimal paidPercent;

        /** max(0, minAmount − paidAmount). Null when not recommended. */
        private BigDecimal remainingAmount;
    }
}
