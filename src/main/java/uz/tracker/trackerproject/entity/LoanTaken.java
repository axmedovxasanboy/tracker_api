package uz.tracker.trackerproject.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;

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
     * back-fills any null values on boot. (Retained for reference; the tier now treats
     * borrowed money as 34%-of-total debt, not a fixed installment.)
     */
    @Column(name = "monthly_payment", precision = 19, scale = 4)
    private BigDecimal monthlyPayment;

    /**
     * Month from which this loan's monthly contribution starts counting toward the
     * Overview tier / allocation guidance. Stored as the first day of that month.
     * Lets the user borrow now but defer the tier impact (e.g. start next month) so
     * the current month's tier doesn't jump the moment money is borrowed.
     * Null on legacy rows → treated as "always counts" by OverviewService.
     */
    @Column(name = "payment_start_date")
    private LocalDate paymentStartDate;

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
}
