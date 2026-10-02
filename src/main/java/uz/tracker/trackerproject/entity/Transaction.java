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
import java.time.YearMonth;

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

    /**
     * Which allocation bucket this row credits (DONATION / EMERGENCY / INVESTMENTS / SAVINGS /
     * STOCKS), decided once when the row is written — see
     * {@link uz.tracker.trackerproject.service.AllocationBucket}. Null for a row that funds no
     * bucket, and for rows written before this column existed: `ddl-auto=update` ADDS a column
     * but never fills it, so DataSeeder back-fills the history once and the read path keeps a
     * derivation fallback for anything that escapes the back-fill.
     */
    @Column(name = "allocation_bucket", length = 32)
    private String allocationBucket;

    /**
     * Set when this transaction lends MORE to an existing LoanGiven instead of opening a new
     * one. Mirrors investmentId: it is what lets a delete/edit back the amount out again.
     */
    @Column(name = "loan_given_id")
    private Long loanGivenId;

    @Column(name = "transfer_pair_id")
    private Long transferPairId;

    /**
     * Which month's salary this is, when that is not the month it arrived in — September's salary
     * paid on 3 October. Stored as the 1st of that month (the setter moves any day there). Only
     * regular INCOME in the salary tree (the bonus included) carries it — TransactionService drops it
     * elsewhere, also when an edit moves the row out of the tree. Null: the month of
     * {@link #transactionDate}.
     * Income counts in the month it is for (2026-10-02): Analytics' monthly figures and History's
     * month list ({@code GET /transactions?accountingMonth=true}) read it too. Wallets, check-ins and
     * pace — real money on the real day — always use the actual date.
     */
    @Column(name = "salary_month")
    private LocalDate salaryMonth;

    public void setSalaryMonth(LocalDate salaryMonth) {
        this.salaryMonth = salaryMonth == null ? null : salaryMonth.withDayOfMonth(1);
    }

    /** The month this income counts in: {@link #salaryMonth}, else the month it arrived in. */
    public YearMonth accountingMonth() {
        if (salaryMonth != null) return YearMonth.from(salaryMonth);
        return transactionDate == null ? null : YearMonth.from(transactionDate);
    }

    /**
     * Portion of {@link #amount} paid in physical cash (not deducted from the linked card).
     * Null/zero unless the user explicitly split the payment. cardPortion = amount - cashAmount.
     * Kept nullable so older rows from before this column existed remain readable under ddl-auto=update.
     */
    @Column(name = "cash_amount", precision = 19, scale = 4)
    private BigDecimal cashAmount;

    // The `place`, `from_location` and `to_location` columns that used to follow here fed the
    // FOOD- and TRANSPORT-kind extras on the add form, which were removed along with category
    // kinds. They are deliberately left in the database rather than dropped (ddl-auto=update
    // could not drop them anyway): nothing maps them any more, and DataSeeder folds whatever
    // they held into the description once, so no route or place a user typed is lost.

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
