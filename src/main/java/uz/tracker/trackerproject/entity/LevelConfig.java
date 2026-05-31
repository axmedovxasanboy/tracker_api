package uz.tracker.trackerproject.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Per-level (1–6) configuration that sits alongside the per-sub-level percentages in
 * {@link LevelAllocationRule}: the minimum-leftover buffer and an optional expiration
 * month that locks the level's rules until it passes. One row per level.
 */
@Entity
@Table(name = "level_configs")
@Getter @Setter @NoArgsConstructor
public class LevelConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Integer level;

    /** Minimum leftover money to keep at this level, in UZS. For Level 1 this is the
     *  tight/comfortable cutoff (defaults to 5M when unset). */
    @Column(name = "min_leftover", precision = 19, scale = 4)
    private BigDecimal minLeftover;

    /** First day of the month the rules are committed until. While now &lt; this month the
     *  level's rules can't be changed. Null = freely editable. */
    @Column(name = "expiration_month")
    private LocalDate expirationMonth;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void touch() {
        updatedAt = LocalDateTime.now();
    }
}
