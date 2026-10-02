package uz.tracker.trackerproject.service;

import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.TransactionFlow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.Objects;

/**
 * The rows that moved one holding's money, and their "linked net" (ANALYTICS-V2-SPEC §4.3) — the one
 * rule Analytics' Goals rebuild a past month's value by and {@code SnapshotScheduler} notes a month's
 * rows with, so the reader's correction compares like with like.
 *
 * <p>A row is linked to a holding by either of two links: it names the holding ({@code investmentId}),
 * or it is the row that created it ({@code Investment.originatingTransactionId}) — the creating row
 * carries no {@code investmentId}. Put in = a linked saving of any bucket (flow SAVED or GIVEN): a row
 * in another bucket still changed the holding's value. Taken out = a withdrawal (FROM_SAVINGS) naming
 * the holding. Only rows {@link AnalyticsService#counts} sees count.
 */
final class HoldingLinks {

    private HoldingLinks() { }

    /** The row names the holding, or created it. */
    static boolean linked(Transaction t, Investment h) {
        if (h.getId() == null) return false;
        return h.getId().equals(t.getInvestmentId())
                || (t.getId() != null && Objects.equals(t.getId(), h.getOriginatingTransactionId()));
    }

    /** Money put into the holding, of any bucket. */
    static boolean putIn(Transaction t, Investment h) {
        if (!AnalyticsService.counts(t)) return false;
        TransactionFlow f = TransactionFlows.of(t);
        return (f == TransactionFlow.SAVED || f == TransactionFlow.GIVEN) && linked(t, h);
    }

    /** Money taken back out of the holding. */
    static boolean takenOut(Transaction t, Investment h) {
        return AnalyticsService.counts(t) && TransactionFlows.of(t) == TransactionFlow.FROM_SAVINGS
                && h.getId() != null && h.getId().equals(t.getInvestmentId());
    }

    /** Σ put in over the rows dated after {@code after} up to {@code upTo} (either may be null: open). */
    static BigDecimal putIn(Collection<Transaction> rows, Investment h, LocalDate after, LocalDate upTo) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Transaction t : rows) if (between(t, after, upTo) && putIn(t, h)) sum = sum.add(t.getAmount());
        return sum;
    }

    /** Σ taken out over the rows dated after {@code after} up to {@code upTo} (either may be null: open). */
    static BigDecimal takenOut(Collection<Transaction> rows, Investment h, LocalDate after, LocalDate upTo) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Transaction t : rows) if (between(t, after, upTo) && takenOut(t, h)) sum = sum.add(t.getAmount());
        return sum;
    }

    /** The linked net — put in − taken out — over the rows dated after {@code after} up to {@code upTo}. */
    static BigDecimal net(Collection<Transaction> rows, Investment h, LocalDate after, LocalDate upTo) {
        return putIn(rows, h, after, upTo).subtract(takenOut(rows, h, after, upTo));
    }

    /** The linked net of the rows dated in {@code month}. */
    static BigDecimal netIn(Collection<Transaction> rows, Investment h, YearMonth month) {
        return net(rows, h, month.atDay(1).minusDays(1), month.atEndOfMonth());
    }

    /** The holding's value at the end of {@code month}: its value now less what its rows did since. */
    static BigDecimal rebuiltValue(Collection<Transaction> rows, Investment h, YearMonth month) {
        return AdvisorService.value(h).subtract(net(rows, h, month.atEndOfMonth(), null));
    }

    /** A value tracked apart from what was put in: growth, or a value typed in. */
    static boolean valueTracked(Investment h) {
        return h.getCurrentValue() != null
                && (h.getInvestedAmount() == null || h.getCurrentValue().compareTo(h.getInvestedAmount()) != 0);
    }

    /**
     * Whether every so'm of the holding has a row: no value tracked apart, and what was put in equals
     * its rows' all-time linked net. A record-only contribution or an opening amount breaks it, and a
     * past month's value can then not be rebuilt exactly from the rows.
     */
    static boolean exact(Collection<Transaction> rows, Investment h) {
        if (valueTracked(h)) return false;
        BigDecimal invested = h.getInvestedAmount() == null ? BigDecimal.ZERO : h.getInvestedAmount();
        return invested.compareTo(net(rows, h, null, null)) == 0;
    }

    /** INVESTMENT · EMERGENCY · GOAL — the Savings page's split: a goal, else the fund, else an investment. */
    static String kindOf(Investment h) {
        if (Boolean.TRUE.equals(h.getSavingsGoal())) return "GOAL";
        if (Boolean.TRUE.equals(h.getEmergencyFund())) return "EMERGENCY";
        return "INVESTMENT";
    }

    private static boolean between(Transaction t, LocalDate after, LocalDate upTo) {
        LocalDate d = t.getTransactionDate();
        if (d == null) return false;
        return (after == null || d.isAfter(after)) && (upTo == null || !d.isAfter(upTo));
    }
}
