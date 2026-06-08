package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;

/**
 * Mark an obligation "already paid" for a month, recording NO transaction and moving no money.
 *
 * <ul>
 *   <li>{@code kind} = SUBSCRIPTION | BANK | PERSONAL_LOAN | DEBT | BUCKET</li>
 *   <li>{@code refId} = the subscription / bank-loan / loan-taken / debt id (omit for BUCKET)</li>
 *   <li>{@code bucket} = DONATION | EMERGENCY | INVESTMENTS | STOCKS (only when kind = BUCKET)</li>
 *   <li>{@code month} = YYYY-MM the mark applies to (defaults to the current month when blank)</li>
 * </ul>
 */
@Getter @Setter
public class MarkPaidRequest {

    @NotBlank(message = "kind is required")
    private String kind;

    private Long refId;

    private String bucket;

    /** YYYY-MM; blank → current month. */
    private String month;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.0001", message = "Amount must be greater than 0")
    private BigDecimal amount;

    @NotNull(message = "Currency is required")
    private Currency currency;

    private String note;
}
