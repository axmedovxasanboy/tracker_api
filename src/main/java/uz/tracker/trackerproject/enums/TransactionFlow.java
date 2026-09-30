package uz.tracker.trackerproject.enums;

/**
 * What a transaction is in the owner's money, whatever its type and sub-type say — the one
 * classification History, the bot and Analytics all read ({@code TransactionResponse.flow}, and the
 * sums of {@code GET /analytics}). Decided in one place:
 * {@link uz.tracker.trackerproject.service.TransactionFlows#of}.
 */
public enum TransactionFlow {
    /** Real income: salary, advance, bonus, anything else earned. */
    EARNED,
    /** Borrowed money arriving (LOAN_RECEIVED). Not income. */
    BORROWED,
    /** Money the owner lent coming back (LOAN_RETURNED_TO_ME). Not income. */
    RETURNED,
    /** Money taken out of an investment or a goal (INVESTMENT_WITHDRAWAL). Not income. */
    FROM_SAVINGS,
    /** A wallet check that found MORE than expected: it takes off the not-itemised everyday spending. */
    CORRECTION,
    /** Money lent to someone (LOAN_GIVEN). Not spending. */
    LENT,
    /** Put into the emergency fund, an investment or a goal. */
    SAVED,
    /** A donation: a saving whose allocation bucket is DONATION. */
    GIVEN,
    /** A bank installment or a repayment of borrowed money or a debt. */
    LOAN_PAYMENT,
    /** A bill paid through Pay (the row carries the bill's id). */
    BILL,
    /** Everyday spending — itemised, or found missing by a wallet check (sub-type EVERYDAY_SPENDING). */
    EVERYDAY,
    /** A move between the owner's own wallets. Counted nowhere. */
    TRANSFER
}
