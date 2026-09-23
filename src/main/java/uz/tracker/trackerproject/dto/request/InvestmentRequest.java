package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.InvestmentType;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter @Setter
public class InvestmentRequest {

    @NotBlank
    private String name;

    @NotNull
    private InvestmentType type;

    /** 0 is allowed only for an {@link #openingBalance} — a savings goal with nothing saved yet.
        A holding funded from a wallet must be more than 0 (checked in FinanceService). */
    @NotNull @DecimalMin("0")
    private BigDecimal investedAmount;

    @NotNull
    private Currency currency;

    @NotNull
    private LocalDate purchaseDate;

    private String broker;

    private String description;

    /** When true, this investment is the emergency fund (counts toward the Emergency bucket). */
    private Boolean emergencyFund;

    /** When true, this investment is a savings goal (Savings area; excluded from the
        mandatory Investments bucket). */
    private Boolean savingsGoal;

    /** Optional savings-goal target. */
    @DecimalMin("0")
    private BigDecimal targetAmount;

    /**
     * Optional savings-goal deadline. See {@link #targetDateGiven()}: leaving it out keeps the stored
     * one, sending {@code null} clears it.
     */
    private LocalDate targetDate;

    /**
     * The savings goal's monthly payment. Optional here because the bot creates goals without it;
     * the web requires it for a goal. See {@link #monthlyContributionGiven()}: leaving it out keeps
     * the stored one, sending {@code null} clears it.
     */
    @DecimalMin("0")
    private BigDecimal monthlyContribution;

    /**
     * The savings goal's first month of payment — any day of it; it is stored as the 1st. See
     * {@link #paymentStartDateGiven()}: leaving it out keeps the stored one, sending {@code null}
     * clears it, which means the month of {@link #purchaseDate} again.
     */
    private LocalDate paymentStartDate;

    // Whether each of the three was SENT. The bot edits a holding by sending back every field of the
    // response as it knew it — a shape without these three — so an update must not read "left out" as
    // "cleared", or every bot edit would wipe a goal's deadline, monthly payment and start month.
    // Jackson calls a setter only for a property present in the JSON (an explicit null included).
    // Not bean properties, so no client can set them.
    @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private boolean targetDateSent;
    @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private boolean monthlyContributionSent;
    @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private boolean paymentStartDateSent;

    public void setTargetDate(LocalDate targetDate) {
        this.targetDate = targetDate;
        this.targetDateSent = true;
    }

    public void setMonthlyContribution(BigDecimal monthlyContribution) {
        this.monthlyContribution = monthlyContribution;
        this.monthlyContributionSent = true;
    }

    /** True when the request carried {@code targetDate} at all — null included. */
    public boolean targetDateGiven() {
        return targetDateSent;
    }

    /** True when the request carried {@code monthlyContribution} at all — null included. */
    public boolean monthlyContributionGiven() {
        return monthlyContributionSent;
    }

    public void setPaymentStartDate(LocalDate paymentStartDate) {
        this.paymentStartDate = paymentStartDate;
        this.paymentStartDateSent = true;
    }

    /** True when the request carried {@code paymentStartDate} at all — null included. */
    public boolean paymentStartDateGiven() {
        return paymentStartDateSent;
    }

    /** Optional current/market value (platform growth). Null = treated as investedAmount. */
    @DecimalMin("0")
    private BigDecimal currentValue;

    /** When true, record an already-owned investment as an OPENING BALANCE: no wallet is debited,
        no mirror transaction is created, and it is excluded from the monthly allocation buckets.
        Use this for holdings you already had before you started tracking. */
    private Boolean openingBalance;

    /** Optional — null = cash. See DonationRequest.cardId for rationale. Ignored when
        {@link #openingBalance} is true (no transaction is created). */
    private Long cardId;

    /** Optional override for the mirrored transaction's category; null = auto-pick by sub-type. */
    private Long categoryId;
}
