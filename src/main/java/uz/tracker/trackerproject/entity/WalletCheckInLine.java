package uz.tracker.trackerproject.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;

/** One wallet of a {@link WalletCheckIn} — the same shape as {@link MonthCloseWallet}. */
@Entity
@Table(name = "wallet_check_in_lines")
@Getter @Setter @NoArgsConstructor
public class WalletCheckInLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "check_in_id", nullable = false)
    private WalletCheckIn checkIn;

    /** "CARD" or "CASH". */
    @Column(name = "wallet_type", nullable = false)
    private String walletType;

    /** Set for CARD wallets; null for the cash pot. */
    @Column(name = "card_id")
    private Long cardId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Currency currency;

    @Column(name = "computed_balance", precision = 19, scale = 4)
    private BigDecimal computedBalance;

    @Column(name = "entered_balance", precision = 19, scale = 4)
    private BigDecimal enteredBalance;

    /** computedBalance − enteredBalance: positive is untracked spending, negative a surplus. */
    @Column(name = "everyday_spend", precision = 19, scale = 4)
    private BigDecimal everydaySpend;

    /** The EVERYDAY_SPENDING transaction booked for this wallet; null when nothing differed. */
    @Column(name = "adjustment_tx_id")
    private Long adjustmentTxId;
}
