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
}
