package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter @Setter
public class DebtRequest {

    /** Required unless {@link #lenderId} is sent — then the person's name is used. */
    private String creditorName;

    /**
     * The creditor on the owner's list of lenders (GET /people/lenders). Optional: without it the
     * creditor is found — or added — by {@link #creditorName}, which is what the bot does.
     */
    private Long lenderId;

    @NotNull @DecimalMin("0.01")
    private BigDecimal totalAmount;

    private BigDecimal paidAmount;

    @NotNull
    private Currency currency;

    @NotNull
    private LocalDate borrowedDate;

    private LocalDate dueDate;

    /**
     * Month (any day; normalised to the 1st server-side). Stored and returned, but a debt is paid
     * back ASAP from the month it was borrowed, so the engine does not read it. Optional — defaults
     * to the month after borrowedDate when omitted.
     */
    private LocalDate paymentStartDate;

    private RecordStatus status;

    private String description;
}
