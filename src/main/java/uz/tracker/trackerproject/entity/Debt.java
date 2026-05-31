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
@Table(name = "debts")
@Getter @Setter @NoArgsConstructor
public class Debt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String creditorName;

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
     * Frozen monthly contribution used by the Overview tier dashboard. Same semantics
     * as {@code LoanTaken.monthlyPayment} — derived once at creation, then stable so
     * paying within a month doesn't shift the user's tier.
     */
    @Column(name = "monthly_payment", precision = 19, scale = 4)
    private BigDecimal monthlyPayment;

    /**
     * Month from which this debt's monthly contribution starts counting toward the
     * Overview tier / allocation guidance. Stored as the first day of that month.
     * Same semantics as {@code LoanTaken.paymentStartDate}. Null on legacy rows →
     * treated as "always counts" by OverviewService.
     */
    @Column(name = "payment_start_date")
    private LocalDate paymentStartDate;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (paidAmount == null) paidAmount = BigDecimal.ZERO;
        if (status == null) status = RecordStatus.PENDING;
    }
}
