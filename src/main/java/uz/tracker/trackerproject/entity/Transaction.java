package uz.tracker.trackerproject.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "transactions")
@Getter @Setter @NoArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Currency currency;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "card_id")
    private Card card;

    @Enumerated(EnumType.STRING)
    private TransactionSubType subType;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private LocalDate transactionDate;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private String note;

    @Column(name = "investment_id")
    private Long investmentId;

    @Column(name = "transfer_pair_id")
    private Long transferPairId;

    /**
     * Portion of {@link #amount} paid in physical cash (not deducted from the linked card).
     * Null/zero unless the user explicitly split the payment. cardPortion = amount - cashAmount.
     * Kept nullable so older rows from before this column existed remain readable under ddl-auto=update.
     */
    @Column(name = "cash_amount", precision = 19, scale = 4)
    private BigDecimal cashAmount;

    /** Free-text "place" — populated for FOOD-kind categories ("Evos", "Rayhon", ...). */
    @Column(name = "place")
    private String place;

    /** Origin location for TRANSPORT-kind categories ("Kvartira"). */
    @Column(name = "from_location")
    private String fromLocation;

    /** Destination location for TRANSPORT-kind categories ("Chilonzor metro"). */
    @Column(name = "to_location")
    private String toLocation;

    /**
     * Foreign-key columns linking a LOAN_REPAYMENT / LOAN_RETURNED_TO_ME transaction back
     * to the specific loan/debt it paid. Set by FinanceService.repay*; null when the user
     * recorded a free-standing loan-style transaction without going through the Finance
     * repay flow. Plain Long FKs (no @ManyToOne) — the loan tables don't need a back-reference
     * collection and we don't want lazy-load surprises on the transaction list endpoint.
     */
    @Column(name = "repaid_loan_taken_id")
    private Long repaidLoanTakenId;

    @Column(name = "repaid_loan_given_id")
    private Long repaidLoanGivenId;

    @Column(name = "repaid_debt_id")
    private Long repaidDebtId;

    /**
     * Plain Long FK linking this transaction to the MonthlyPayment (subscription) it
     * paid. Set by FinanceService.payMonthlyPayment; null for free-standing transactions.
     * Powers the per-subscription history panel and the totalPaid aggregate.
     */
    @Column(name = "monthly_payment_id")
    private Long monthlyPaymentId;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (cashAmount == null) cashAmount = BigDecimal.ZERO;
    }
}
