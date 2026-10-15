package uz.tracker.trackerproject.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A month's savings rule as its first day decided it (the owner, 2026-10-15): the level and the
 * situation are checked once, when the month begins, and never move after — money borrowed, repaid
 * or a bill added mid-month counts from the next month's first day. The percentages still come from
 * the level's rules version in force that month. Written by {@code MonthRuleRecorder}; months before
 * the first row are worked out from today's data, as they always were.
 */
@Entity
@Table(name = "month_rules",
        uniqueConstraints = @UniqueConstraint(name = "uk_month_rule_month", columnNames = "month"))
@Getter @Setter @NoArgsConstructor
public class MonthRule {
    // One row per month (uk_month_rule_month): two reads fixing the month at once both see none — the
    // second insert fails, and LevelService.refreshQuietly swallows it.

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The month, as its first day. */
    @Column(name = "month", nullable = false)
    private LocalDate month;

    /** 1..5. */
    @Column(nullable = false)
    private Integer level;

    /** One of {@code SavingsRules.SITUATIONS}. A plain string: no CHECK constraint to migrate. */
    @Column(nullable = false, length = 32)
    private String situation;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
