package uz.tracker.trackerproject.service;

import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.TransactionFlow;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;

/**
 * The one place a transaction's {@link TransactionFlow} is decided. {@code TransactionResponse.flow}
 * and {@code GET /analytics} both call {@link #of}, so a row can never be one thing in History and
 * another in Analytics. First match wins, top to bottom:
 *
 * <ol start="0">
 *   <li>a move between the owner's own wallets → TRANSFER</li>
 *   <li>INCOME, LOAN_RECEIVED → BORROWED</li>
 *   <li>INCOME, LOAN_RETURNED_TO_ME → RETURNED</li>
 *   <li>INCOME, INVESTMENT_WITHDRAWAL → FROM_SAVINGS</li>
 *   <li>INCOME, EVERYDAY_SPENDING (a wallet check found more) → CORRECTION</li>
 *   <li>any other INCOME → EARNED</li>
 *   <li>EXPENSE, LOAN_GIVEN → LENT</li>
 *   <li>EXPENSE, DONATION / EMERGENCY_CONTRIBUTION / INVESTMENT / STOCK_PURCHASE → GIVEN when the
 *       row's allocation bucket is DONATION, else SAVED</li>
 *   <li>EXPENSE, BANK_LOAN_PAYMENT / LOAN_REPAYMENT → LOAN_PAYMENT</li>
 *   <li>EXPENSE with a monthlyPaymentId (a bill paid through Pay) → BILL</li>
 *   <li>EXPENSE, EVERYDAY_SPENDING (a wallet check found less) → EVERYDAY</li>
 *   <li>any other EXPENSE → EVERYDAY</li>
 * </ol>
 * The predicates are the ones Home's pace already uses ({@link DailyAdviceService#isEverydaySpend},
 * {@link DailyAdviceService#isSurplusFound}, {@link DailyAdviceService#isSaving}). Currency plays no
 * part: a row in a dormant foreign pot has a flow too — which sums it enters is the reader's choice.
 */
public final class TransactionFlows {

    private TransactionFlows() { }

    /** Never null. */
    public static TransactionFlow of(Transaction t) {
        if (isTransfer(t)) return TransactionFlow.TRANSFER;
        TransactionSubType st = t.getSubType();
        if (t.getType() == TransactionType.INCOME) {
            if (st == TransactionSubType.LOAN_RECEIVED) return TransactionFlow.BORROWED;
            if (st == TransactionSubType.LOAN_RETURNED_TO_ME) return TransactionFlow.RETURNED;
            if (st == TransactionSubType.INVESTMENT_WITHDRAWAL) return TransactionFlow.FROM_SAVINGS;
            if (DailyAdviceService.isSurplusFound(t)) return TransactionFlow.CORRECTION;
            return TransactionFlow.EARNED;
        }
        if (st == TransactionSubType.LOAN_GIVEN) return TransactionFlow.LENT;
        if (DailyAdviceService.isSaving(t)) return isDonation(t) ? TransactionFlow.GIVEN : TransactionFlow.SAVED;
        if (st == TransactionSubType.BANK_LOAN_PAYMENT || st == TransactionSubType.LOAN_REPAYMENT) {
            return TransactionFlow.LOAN_PAYMENT;
        }
        if (DailyAdviceService.isEverydaySpend(t)) return TransactionFlow.EVERYDAY;
        if (t.getMonthlyPaymentId() != null) return TransactionFlow.BILL;
        return TransactionFlow.EVERYDAY;
    }

    /** A half of a move between the owner's own wallets: it carries a pair id, or a transfer sub-type. */
    public static boolean isTransfer(Transaction t) {
        return t.getTransferPairId() != null || t.getSubType() == TransactionSubType.TRANSFER_IN
                || t.getSubType() == TransactionSubType.TRANSFER_OUT;
    }

    /**
     * Whether a saving is a donation: the bucket recorded on the row when it was written, else — a
     * row from before that column — the one its sub-type names (the same answer
     * {@code OverviewService.bucketOf} gives; which holding an INVESTMENT row paid into can only
     * tell a goal from an investment, never a donation).
     */
    private static boolean isDonation(Transaction t) {
        String bucket = t.getAllocationBucket() != null ? t.getAllocationBucket()
                : AllocationBucket.forSubType(t.getSubType(), false);
        return AllocationBucket.DONATION.equals(bucket);
    }
}
