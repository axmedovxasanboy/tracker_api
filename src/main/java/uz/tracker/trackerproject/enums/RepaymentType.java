package uz.tracker.trackerproject.enums;

/**
 * How money borrowed from a person is paid back (the owner's two kinds, 2026-09-23).
 *
 * <ul>
 *   <li>{@link #MONTHLY} — like a bank loan: the agreed monthly payment
 *       ({@code LoanTaken.plannedMonthlyPayment}) from the payment-start month, capped at what is
 *       left. The parents' 50M at 500,000 a month.</li>
 *   <li>{@link #ASAP} — as fast as possible, from the month it was borrowed: all of what was left at
 *       the month's start when that is at most 70% of the stable income, else 34% of it. Every
 *       {@code Debt} is ASAP.</li>
 * </ul>
 */
public enum RepaymentType {
    MONTHLY,
    ASAP
}
