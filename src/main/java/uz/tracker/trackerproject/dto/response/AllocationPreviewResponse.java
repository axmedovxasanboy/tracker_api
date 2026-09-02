package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * What a draft transaction would do to one allocation bucket. Every figure is in the
 * display currency and reflects the month the transaction is dated in — not today.
 */
@Getter @Builder
public class AllocationPreviewResponse {

    /** False when this sub-type funds no bucket; every amount below is then null. */
    private boolean applicable;

    /** DONATION | EMERGENCY | INVESTMENTS | STOCKS | SAVINGS — null when not applicable. */
    private String bucket;
    private String label;

    /** Why no bucket applies, or a note about the effect. Always safe to show. */
    private String message;

    /** True when the bucket is not recommended at the user's current tier. */
    private boolean bucketNotRecommended;

    private BigDecimal recommended;     // this month's target for the bucket
    private BigDecimal paidBefore;      // already recorded this month
    private BigDecimal amount;          // the draft transaction
    private BigDecimal paidAfter;       // paidBefore + amount
    private BigDecimal remainingBefore;
    private BigDecimal remainingAfter;
    /** True when this transaction alone takes the bucket to (or past) its target. */
    private boolean completesBucket;
}
