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
 * Money set aside for emergencies (medical, sudden repairs, etc). Treated as a
 * savings bucket — each row is a contribution to the emergency fund, not a
 * spend FROM it.
 *
 * <p>The row is a companion to a real EMERGENCY_CONTRIBUTION transaction, which is what the
 * Overview "Emergency" bucket actually sums — this table is never the money, only the tab's
 * list. {@link #originatingTransactionId} ties the two together so editing or deleting one
 * moves the other; rows created before that link exists carry null and are edited list-only.
 */
@Entity
@Table(name = "emergencies")
@Getter @Setter @NoArgsConstructor
public class Emergency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Currency currency;

    @Column(name = "contribution_date", nullable = false)
    private LocalDate date;

    @Column
    private String description;

    /** The mirrored EMERGENCY_CONTRIBUTION transaction. Null for rows created before the link. */
    @Column(name = "originating_transaction_id")
    private Long originatingTransactionId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
