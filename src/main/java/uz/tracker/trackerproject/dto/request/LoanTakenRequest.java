package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.enums.RepaymentType;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter @Setter
public class LoanTakenRequest {

    /** Required unless {@link #lenderId} is sent — then the person's name is used. */
    private String lenderName;

    /**
     * The lender on the owner's list of people (GET /people/lenders). Optional: without it the
     * lender is found — or added — by {@link #lenderName}, which is what the bot does.
     */
    private Long lenderId;

    /**
     * MONTHLY (needs {@link #plannedMonthlyPayment}) or ASAP. Optional: without it the type follows
     * the plan — a plan means MONTHLY, none means ASAP — which is what the bot does.
     */
    private RepaymentType repaymentType;

    @NotNull @DecimalMin("0.01")
    private BigDecimal totalAmount;

    private BigDecimal paidAmount;

    @NotNull
    private Currency currency;

    @NotNull
    private LocalDate borrowedDate;

    private LocalDate dueDate;

    /** The MONTHLY loan's monthly payment; an ASAP loan has none (it is dropped). */
    private BigDecimal plannedMonthlyPayment;

    /**
     * Month (any day; normalised to the 1st server-side) from which a MONTHLY loan's plan starts
     * counting toward the Overview tier. Optional — defaults to the month after borrowedDate when
     * omitted. An ASAP loan counts from the month it was borrowed, whatever this says.
     */
    private LocalDate paymentStartDate;

    private RecordStatus status;

    private String description;

    /**
     * True: book the money into a wallet in the same step — {@link #cardId}, or cash when that is
     * null — as a transaction the record then belongs to, exactly as if the owner had recorded the
     * transaction first. Null or false (the bot, which never sends it): only the record. Read on
     * create; an edit ignores it.
     */
    private Boolean moveMoney;

    /** The wallet for {@link #moveMoney}: a UZS card; null = cash. */
    private Long cardId;
}
