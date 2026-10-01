package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.InvestmentType;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter @Setter
public class TransactionRequest {

    @NotNull(message = "Type is required")
    private TransactionType type;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.0001", message = "Amount must be greater than 0")
    private BigDecimal amount;

    @NotNull(message = "Currency is required")
    private Currency currency;

    private Long categoryId;

    private Long cardId;

    /**
     * Optional at the wire level — the service auto-fills from the category name +
     * place / route if missing, and the per-category descriptionRequired flag governs
     * whether the client should have asked for one.
     */
    private String description;

    @NotNull(message = "Transaction date is required")
    private LocalDate transactionDate;

    private String note;

    // Finance auto-creation fields
    private TransactionSubType subType;

    // Name of the counterparty (lender, borrower, donor recipient, etc.)
    private String counterpartyName;

    // For INVESTMENT sub-type
    private InvestmentType investmentType;

    // When adding funds to an existing investment instead of creating a new one
    private Long investmentId;

    /** Top up an existing loan given instead of creating a new borrower record. */
    private Long loanGivenId;

    /**
     * For LOAN_RECEIVED: month from which repayments start counting toward the Overview
     * tier (passed through to the auto-created LoanTaken). Null → backend defaults to the
     * month after the transaction date.
     */
    private java.time.LocalDate paymentStartDate;

    /**
     * Which month's salary this is ('YYYY-MM') when it arrives in another month — September's salary
     * paid on 3 October, October's paid early on 30 September: the month before, of, or after the
     * date (anything further is a 400). Regular income only; ignored (stored as null) on any other
     * transaction. On
     * an update a request without the key keeps the stored month (the bot never sends it); an
     * explicit null clears it.
     */
    @lombok.Getter(lombok.AccessLevel.NONE) @lombok.Setter(lombok.AccessLevel.NONE)
    private java.time.YearMonth salaryMonth;
    @lombok.Getter(lombok.AccessLevel.NONE) @lombok.Setter(lombok.AccessLevel.NONE)
    private boolean salaryMonthSent;

    public java.time.YearMonth getSalaryMonth() {
        return salaryMonth;
    }

    public void setSalaryMonth(java.time.YearMonth salaryMonth) {
        this.salaryMonth = salaryMonth;
        this.salaryMonthSent = true;
    }

    /** True when the request carried {@code salaryMonth} at all — null included. */
    public boolean salaryMonthGiven() {
        return salaryMonthSent;
    }

    // ── The loan a payment pays (optional). A LOAN_REPAYMENT names a borrowed loan OR a debt; a
    // LOAN_RETURNED_TO_ME a loan given. The loan's paid / received figure moves with the row when it
    // is created, edited or deleted. On an update a key left out keeps the stored link (the bot never
    // sends them); an explicit null removes it. A row that is not that kind of payment names none.
    @lombok.Getter(lombok.AccessLevel.NONE) @lombok.Setter(lombok.AccessLevel.NONE)
    private Long repaidLoanTakenId;
    @lombok.Getter(lombok.AccessLevel.NONE) @lombok.Setter(lombok.AccessLevel.NONE)
    private boolean repaidLoanTakenIdSent;
    @lombok.Getter(lombok.AccessLevel.NONE) @lombok.Setter(lombok.AccessLevel.NONE)
    private Long repaidDebtId;
    @lombok.Getter(lombok.AccessLevel.NONE) @lombok.Setter(lombok.AccessLevel.NONE)
    private boolean repaidDebtIdSent;
    @lombok.Getter(lombok.AccessLevel.NONE) @lombok.Setter(lombok.AccessLevel.NONE)
    private Long repaidLoanGivenId;
    @lombok.Getter(lombok.AccessLevel.NONE) @lombok.Setter(lombok.AccessLevel.NONE)
    private boolean repaidLoanGivenIdSent;

    public Long getRepaidLoanTakenId() {
        return repaidLoanTakenId;
    }

    public void setRepaidLoanTakenId(Long repaidLoanTakenId) {
        this.repaidLoanTakenId = repaidLoanTakenId;
        this.repaidLoanTakenIdSent = true;
    }

    public boolean repaidLoanTakenIdGiven() {
        return repaidLoanTakenIdSent;
    }

    public Long getRepaidDebtId() {
        return repaidDebtId;
    }

    public void setRepaidDebtId(Long repaidDebtId) {
        this.repaidDebtId = repaidDebtId;
        this.repaidDebtIdSent = true;
    }

    public boolean repaidDebtIdGiven() {
        return repaidDebtIdSent;
    }

    public Long getRepaidLoanGivenId() {
        return repaidLoanGivenId;
    }

    public void setRepaidLoanGivenId(Long repaidLoanGivenId) {
        this.repaidLoanGivenId = repaidLoanGivenId;
        this.repaidLoanGivenIdSent = true;
    }

    public boolean repaidLoanGivenIdGiven() {
        return repaidLoanGivenIdSent;
    }

    /** Portion of {@link #amount} paid in physical cash. Service enforces 0 <= cashAmount <= amount. */
    @DecimalMin(value = "0.0", message = "Cash amount cannot be negative")
    private BigDecimal cashAmount;
}
