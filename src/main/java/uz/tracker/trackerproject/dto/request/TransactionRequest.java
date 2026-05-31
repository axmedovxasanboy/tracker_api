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

    /**
     * For LOAN_RECEIVED: month from which repayments start counting toward the Overview
     * tier (passed through to the auto-created LoanTaken). Null → backend defaults to the
     * month after the transaction date.
     */
    private java.time.LocalDate paymentStartDate;

    /** Portion of {@link #amount} paid in physical cash. Service enforces 0 <= cashAmount <= amount. */
    @DecimalMin(value = "0.0", message = "Cash amount cannot be negative")
    private BigDecimal cashAmount;

    /** Free-text "place" — surfaces for FOOD-kind categories. */
    private String place;

    /** Optional origin for TRANSPORT-kind categories. */
    private String fromLocation;

    /** Optional destination for TRANSPORT-kind categories. */
    private String toLocation;
}
