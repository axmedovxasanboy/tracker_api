package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;

@Getter @Builder
public class DashboardSummaryResponse {

    private Currency currency;
    private BigDecimal totalIncome;
    private BigDecimal totalExpense;
    private BigDecimal netBalance;
    private long transactionCount;

    /**
     * Sum of every wallet's CURRENT balance in this currency: each card's
     * (initialBalance + tx delta) plus the CashBalance row for the currency
     * (initialBalance + cardless tx delta). Reflects "money I actually have right now",
     * which differs from netBalance because it includes starting balances.
     */
    private BigDecimal availableBalance;

    /**
     * SPENDABLE balance — money sitting in wallets you can spend (same value as
     * {@link #availableBalance}, kept as a distinct field for the envelope model so the
     * frontend can label it clearly against {@link #netWorth}).
     */
    private BigDecimal spendableBalance;

    /**
     * NET WORTH in this currency: spendable wallet money across ALL currencies plus the
     * current value of every investment / savings goal, FX-converted into this currency.
     * Money parked in investments/savings is still yours — just not spending-money.
     */
    private BigDecimal netWorth;
}
