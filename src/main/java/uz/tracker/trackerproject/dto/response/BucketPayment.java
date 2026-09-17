package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row in the per-bucket payment history. Normalises donations, bucket transactions and
 * "already paid" marks into a common shape so the frontend can render a uniform list. Nothing
 * converts, so {@code amount} always equals {@code nativeAmount}; both are kept for the clients
 * that read either.
 */
@Getter @Builder
public class BucketPayment {
    private Long id;
    private String bucket;          // DONATION | EMERGENCY | INVESTMENTS | STOCKS | SAVINGS
    private LocalDate date;
    private BigDecimal amount;       // same as nativeAmount (no conversion)
    private BigDecimal nativeAmount; // raw value as stored
    private Currency nativeCurrency;
    private String label;            // recipient name / investment name / "Emergency fund"
    private String description;

    /**
     * True for an "already paid" mark rather than a real payment: no money left a wallet and
     * the month-close reconciliation ignores it. {@code id} is then a MarkPaid id, NOT a
     * Donation / Transaction id — the client must branch on this before offering row actions,
     * and delete such a row via {@code DELETE /api/v1/finance/mark-paid/{id}}.
     */
    private boolean marked;
}
