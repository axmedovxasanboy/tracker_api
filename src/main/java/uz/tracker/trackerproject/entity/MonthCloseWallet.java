package uz.tracker.trackerproject.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;

/**
 * Per-wallet detail of a month close: the computed balance the app derived, the real balance
 * the user entered, the resulting everyday-spend adjustment (computed − entered, in the
 * wallet's own currency), and the id of the EVERYDAY_SPENDING transaction booked to reconcile.
 */
@Entity
@Table(name = "month_close_wallets")
@Getter @Setter @NoArgsConstructor
public class MonthCloseWallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "month_close_id", nullable = false)
    private MonthClose monthClose;

    /** "CARD" or "CASH". */
    @Column(name = "wallet_type", nullable = false)
    private String walletType;

    /** Set for CARD wallets; null for the per-currency CASH pot. */
    @Column(name = "card_id")
    private Long cardId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Currency currency;

    @Column(name = "computed_balance", precision = 19, scale = 4)
    private BigDecimal computedBalance;

    @Column(name = "entered_balance", precision = 19, scale = 4)
    private BigDecimal enteredBalance;

    /** computedBalance − enteredBalance, in this wallet's currency (the everyday-spend plug). */
    @Column(name = "everyday_spend", precision = 19, scale = 4)
    private BigDecimal everydaySpend;

    /** The EVERYDAY_SPENDING transaction booked to reconcile this wallet; null when delta == 0. */
    @Column(name = "adjustment_tx_id")
    private Long adjustmentTxId;
}
