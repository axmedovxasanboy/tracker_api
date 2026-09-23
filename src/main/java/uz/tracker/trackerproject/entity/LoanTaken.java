package uz.tracker.trackerproject.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.enums.RepaymentType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "loans_taken")
@Getter @Setter @NoArgsConstructor
public class LoanTaken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String lenderName;

    /**
     * The lender on the owner's list of people (Counterparty, kind LENDER). The name above stays the
     * record's own copy — the bot shows it — and follows a rename of the person.
     */
    @Column(name = "lender_id")
    private Long lenderId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal paidAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Currency currency;

    @Column(nullable = false)
    private LocalDate borrowedDate;

    @Column
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecordStatus status;

    @Column
    private String description;

    /**
     * Frozen monthly contribution used by the Overview tier dashboard. Derived ONCE
     * at creation time from (totalAmount - paidAmount) / monthsUntilDue and stored
     * here. We intentionally do NOT recompute it when paidAmount changes (via /repay) —
     * otherwise paying within a month would lower the derived monthly and shift the
     * user's tier mid-month, which is misleading. Re-derives on edits via the apply
     * path only when the field is currently null.
     *
     * Nullable so existing rows under ddl-auto=update keep working; DataSeeder
     * back-fills any null values on boot. (Retained for reference; the tier asks a MONTHLY loan's
     * plan or an ASAP loan's ASAP ask, never this.)
     */
    @Column(name = "monthly_payment", precision = 19, scale = 4)
    private BigDecimal monthlyPayment;

    /**
     * Month from which a MONTHLY loan's plan starts counting toward the Overview tier / allocation
     * guidance (an ASAP loan counts from the month it was borrowed; this plays no part). Stored as
     * the first day of that month.
     * Lets the user borrow now but defer the tier impact (e.g. start next month) so
     * the current month's tier doesn't jump the moment money is borrowed.
     * Null on legacy rows → treated as "always counts" by OverviewService.
     */
    @Column(name = "payment_start_date")
    private LocalDate paymentStartDate;

    /**
     * The MONTHLY loan's repayment plan: the fixed amount the user puts toward this loan each
     * month, from {@link #paymentStartDate} — the parents' 500,000 a month on 50M.
     *
     * <p>Deliberately separate from {@link #monthlyPayment}, which is auto-derived for display
     * and back-filled on every existing row; keying the tier off that would silently turn every
     * loan already in the database into a MONTHLY one.
     *
     * <p>Null = no plan: the loan is paid back ASAP (see {@link #repaymentType}).
     */
    @Column(name = "planned_monthly_payment", precision = 19, scale = 4)
    private BigDecimal plannedMonthlyPayment;

    /**
     * MONTHLY (the plan above, from {@link #paymentStartDate}) or ASAP (as fast as possible, from
     * the month it was borrowed). A MONTHLY loan always has a plan and an ASAP loan never has one
     * (FinanceService keeps them together). Null on rows from before the type existed: see
     * {@link #effectiveRepaymentType()}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "repayment_type", length = 16)
    private RepaymentType repaymentType;

    @Column(name = "originating_transaction_id")
    private Long originatingTransactionId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (paidAmount == null) paidAmount = BigDecimal.ZERO;
        if (status == null) status = RecordStatus.PENDING;
    }

    /**
     * How this loan is paid back: MONTHLY only with a monthly plan to pay (a stored MONTHLY without
     * one has nothing to ask, so it is taken as ASAP); otherwise the stored type, else — on a row
     * from before the type existed — derived from the plan: a plan means MONTHLY.
     */
    public RepaymentType effectiveRepaymentType() {
        boolean hasPlan = plannedMonthlyPayment != null && plannedMonthlyPayment.signum() > 0;
        return hasPlan && repaymentType != RepaymentType.ASAP ? RepaymentType.MONTHLY : RepaymentType.ASAP;
    }
}
