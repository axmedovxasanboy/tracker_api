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
public class LoanGivenRequest {

    /** Required unless {@link #borrowerId} is sent — then the person's name is used. */
    private String debtorName;

    /**
     * The borrower on the owner's list of people (GET /people/borrowers). Optional: without it the
     * borrower is found — or added — by {@link #debtorName}, which is what the bot does.
     */
    private Long borrowerId;

    @NotNull @DecimalMin("0.01")
    private BigDecimal totalAmount;

    private BigDecimal receivedAmount;

    @NotNull
    private Currency currency;

    @NotNull
    private LocalDate lentDate;

    private LocalDate expectedReturnDate;

    private RecordStatus status;

    private String description;
}
