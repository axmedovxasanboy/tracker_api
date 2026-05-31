package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row in the per-bucket payment history. Normalises across Donation / Emergency /
 * Investment (with type filter for STOCKS vs non-stocks) into a common shape so the
 * frontend can render a uniform list. {@code amount} is FX-converted into the
 * requested display currency; {@code nativeAmount} + {@code nativeCurrency} retain
 * the original values so the user knows what they actually entered.
 */
@Getter @Builder
public class BucketPayment {
    private Long id;
    private String bucket;          // DONATION | EMERGENCY | INVESTMENTS | STOCKS
    private LocalDate date;
    private BigDecimal amount;       // converted into display currency
    private BigDecimal nativeAmount; // raw value as stored
    private Currency nativeCurrency;
    private String label;            // recipient name / investment name / "Emergency fund"
    private String description;
}
