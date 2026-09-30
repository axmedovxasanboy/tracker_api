package uz.tracker.trackerproject.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * What the owner owned and owed in one month — Analytics' "Own and owe", kept so it can show a trend.
 * An investment's value is overwritten when it is updated, so a past month can never be rebuilt;
 * it has to be written down as it happens. One row per month: the current month's row is rewritten
 * each time Analytics computes the position, so the last write of a month is its closing picture
 * and earlier months stay as they were. UZS.
 */
@Entity
@Table(name = "position_snapshots",
        uniqueConstraints = @UniqueConstraint(name = "uk_position_snapshot_month", columnNames = "month"))
@Getter @Setter @NoArgsConstructor
public class PositionSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The month, as its first day. */
    @Column(name = "month", nullable = false)
    private LocalDate month;

    /** The day the figures were last taken on. */
    @Column(name = "as_of", nullable = false)
    private LocalDate asOf;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal wallets;

    @Column(name = "emergency_fund", nullable = false, precision = 19, scale = 4)
    private BigDecimal emergencyFund;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal investments;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal goals;

    @Column(name = "loans_left", nullable = false, precision = 19, scale = 4)
    private BigDecimal loansLeft;

    @Column(name = "owed_to_you", nullable = false, precision = 19, scale = 4)
    private BigDecimal owedToYou;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist @PreUpdate
    protected void touch() {
        updatedAt = LocalDateTime.now();
    }
}
