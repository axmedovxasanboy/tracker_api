package uz.tracker.trackerproject.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.InvestmentType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "investments")
@Getter @Setter @NoArgsConstructor
public class Investment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvestmentType type;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal investedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Currency currency;

    @Column(nullable = false)
    private LocalDate purchaseDate;

    @Column
    private String broker;

    @Column
    private String description;

    /** When true, this investment IS the emergency fund — it counts toward the Overview
        Emergency allocation bucket instead of the Investments bucket. */
    @Column(name = "emergency_fund")
    private Boolean emergencyFund;

    /** When true, this investment is a SAVINGS GOAL (home / iPhone / gold / prize …). It is
        tracked in the separate, optional Savings area and is EXCLUDED from the mandatory
        Investments allocation bucket. Checked AFTER emergencyFund (a goal is never the
        emergency fund). Null/false = a plain investment that feeds the Investments bucket. */
    @Column(name = "savings_goal")
    private Boolean savingsGoal;

    /** Optional target for a savings goal (e.g. 200M for a home). Null = open-ended goal. */
    @Column(name = "target_amount", precision = 19, scale = 4)
    private BigDecimal targetAmount;

    /** Optional current/market value reflecting platform growth. When null it is treated as
        equal to {@link #investedAmount} (see InvestmentResponse.from + the contribute flow). */
    @Column(name = "current_value", precision = 19, scale = 4)
    private BigDecimal currentValue;

    @Column(name = "originating_transaction_id")
    private Long originatingTransactionId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
