package uz.tracker.trackerproject.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The monthly income from one month on. The value for a month M is the entry with the latest
 * {@link #month} ≤ M (before the first entry: the first entry's), so a change applies from the month
 * the owner picks until the next change already recorded after it — and months before it never move.
 * UZS. See {@code StableIncomeSchedule}.
 */
@Entity
@Table(name = "stable_income_entries",
        uniqueConstraints = @UniqueConstraint(name = "uk_stable_income_month", columnNames = "month"))
@Getter @Setter @NoArgsConstructor
public class StableIncomeEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The first month it applies to, as its first day. */
    @Column(name = "month", nullable = false)
    private LocalDate month;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public StableIncomeEntry(LocalDate month, BigDecimal amount) {
        this.month = month.withDayOfMonth(1);
        this.amount = amount;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
