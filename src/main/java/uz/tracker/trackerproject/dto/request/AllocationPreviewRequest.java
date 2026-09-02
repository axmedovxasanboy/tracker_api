package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import uz.tracker.trackerproject.enums.TransactionSubType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A transaction the user is still typing. Answers "what would this do to my allocation?"
 * without persisting anything.
 */
@Getter @Setter
public class AllocationPreviewRequest {

    /** Null (or a sub-type that funds no bucket) yields a "not applicable" preview. */
    private TransactionSubType subType;

    @NotNull
    private BigDecimal amount;

    @NotNull
    private LocalDate transactionDate;

    /** Set when topping up an existing investment — decides Investments vs Savings. */
    private Long investmentId;
}
