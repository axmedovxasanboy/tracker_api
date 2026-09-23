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

    /** Optional deadline for a savings goal. Shown with it; it does not change what is set aside. */
    @Column(name = "target_date")
    private LocalDate targetDate;

    /**
     * A savings goal's monthly payment. The advisor lists it with the month's savings and the daily
     * figure sets it aside like the plan's buckets, until the target is reached. Null or 0 = none.
     * Nullable: the bot creates goals without it, and rows from before it existed have none.
     */
    @Column(name = "monthly_contribution", precision = 19, scale = 4)
    private BigDecimal monthlyContribution;

    /** Optional current/market value reflecting platform growth. When null it is treated as
        equal to {@link #investedAmount} (see InvestmentResponse.from + the contribute flow). */
    @Column(name = "current_value", precision = 19, scale = 4)
    private BigDecimal currentValue;

    /** When true, this is an OPENING BALANCE — an investment the user already owned before
        tracking started. It is recorded for net-worth / portfolio purposes only: NO mirror
        EXPENSE transaction is created (no wallet is debited, nothing shows as spent now) and it
        is EXCLUDED from the monthly allocation buckets (it was not a contribution made this
        month). Null/false = a normal investment that debits a wallet and feeds its bucket. */
    @Column(name = "opening_balance")
    private Boolean openingBalance;

    @Column(name = "originating_transaction_id")
    private Long originatingTransactionId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
