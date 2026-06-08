package uz.tracker.trackerproject.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * An "already paid" mark — the user declares an obligation satisfied for a month WITHOUT
 * recording a money-moving transaction (e.g. they paid a subscription in cash that the app
 * doesn't track, or handled a donation outside the app). The tier engine counts these marks
 * toward the relevant "paid this month" total exactly like a real payment, but they never
 * touch a wallet balance and are deliberately excluded from the month-close reconciliation
 * (no tracked money moved, so they must not reduce everyday-spend).
 *
 * <p>For personal-loan / debt marks the originating service ALSO bumps the entity's paidAmount
 * (mirroring a real repayment), so the remaining balance and tier shift just as they would.
 */
@Entity
@Table(name = "mark_paids")
@Getter @Setter @NoArgsConstructor
public class MarkPaid {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SUBSCRIPTION | BANK | PERSONAL_LOAN | DEBT | BUCKET. Plain string — no DB CHECK to heal. */
    @Column(nullable = false)
    private String kind;

    /** monthlyPaymentId / bankLoanId / loanTakenId / debtId. Null for BUCKET marks. */
    @Column(name = "ref_id")
    private Long refId;

    /** DONATION | EMERGENCY | INVESTMENTS | STOCKS when kind == BUCKET; null otherwise. */
    @Column
    private String bucket;

    /** First day of the month this mark applies to. */
    @Column(nullable = false)
    private LocalDate month;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Currency currency;

    @Column
    private String note;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
