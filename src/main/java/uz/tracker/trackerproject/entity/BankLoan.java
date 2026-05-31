package uz.tracker.trackerproject.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "bank_loans")
@Getter @Setter @NoArgsConstructor
public class BankLoan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String bankName;

    /** Loan product name — e.g. "Talim kredit", "Avtokredit", "Ipoteka / Uy kredit" */
    @Column(nullable = false)
    private String loanName;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Currency currency;

    @Column(nullable = false)
    private LocalDate takenDate;

    @Column
    private LocalDate endDate;

    /**
     * User-supplied average monthly installment. Drives the Overview tier dashboard's
     * "mandatory debt payments" calculation. Nullable for backwards compatibility with
     * rows created before this field existed — treated as 0 (no monthly obligation).
     */
    @Column(name = "monthly_payment", precision = 19, scale = 4)
    private BigDecimal monthlyPayment;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
