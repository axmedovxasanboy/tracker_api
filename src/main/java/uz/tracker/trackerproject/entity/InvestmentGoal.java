package uz.tracker.trackerproject.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * An investment goal (Investments page, Batch 79): a name and a rough target ("a car, about 1 mlrd")
 * that one or more plain investment accounts are saving for. It holds no money itself — what it has
 * saved is the sum of the values of the accounts linked to it ({@link Investment#getInvestmentGoalId}).
 * An account serves at most one goal, so nothing is counted twice. Not the Savings page's goals.
 */
@Entity
@Table(name = "investment_goals")
@Getter @Setter @NoArgsConstructor
public class InvestmentGoal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** The rough amount needed ("taxminiy"); always more than 0. */
    @Column(name = "target_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal targetAmount;

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
