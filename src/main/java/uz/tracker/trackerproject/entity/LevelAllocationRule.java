package uz.tracker.trackerproject.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * User-defined allocation rule for a Level 2–6 sub-level (e.g. "3.2"). Level 1 stays
 * hard-coded in OverviewService per the owner's spec; everything above is configured by
 * the user as they reach each tier. One row per sub-level. A null percent means the bucket
 * is "not recommended" at that sub-level; a row with all-null percents is treated as unset
 * (the service deletes it).
 */
@Entity
@Table(name = "level_allocation_rules")
@Getter @Setter @NoArgsConstructor
public class LevelAllocationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Parent level (2–6) — stored for grouping/filtering; derivable from subLevel too. */
    @Column(nullable = false)
    private Integer level;

    /** "{level}.{1|2|3}" — 1 = no debt, 2 = manageable (<70%), 3 = heavy (≥70%). Unique. */
    @Column(name = "sub_level", nullable = false, unique = true)
    private String subLevel;

    @Column(name = "donation_percent", precision = 6, scale = 2)
    private BigDecimal donationPercent;

    @Column(name = "emergency_percent", precision = 6, scale = 2)
    private BigDecimal emergencyPercent;

    @Column(name = "investments_percent", precision = 6, scale = 2)
    private BigDecimal investmentsPercent;

    @Column(name = "stocks_percent", precision = 6, scale = 2)
    private BigDecimal stocksPercent;

    @Column(name = "note")
    private String note;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void touch() {
        updatedAt = LocalDateTime.now();
    }
}
