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

    /**
     * True: book the money out of a wallet in the same step — {@link #cardId}, or cash when that is
     * null — as a transaction the record then belongs to, exactly as if the owner had recorded the
     * transaction first. Null or false (the bot, which never sends it): only the record. Read on
     * create; an edit ignores it.
     */
    private Boolean moveMoney;

    /** The wallet for {@link #moveMoney}: a UZS card; null = cash. */
    private Long cardId;
}
