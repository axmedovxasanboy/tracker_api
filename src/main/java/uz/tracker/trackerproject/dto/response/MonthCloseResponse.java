package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.entity.MonthClose;
import uz.tracker.trackerproject.entity.MonthCloseWallet;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A committed, permanent month-close record. All money figures are the UZS snapshot taken at
 * close time (immune to later FX-rate changes); {@code currency} is therefore always UZS.
 */
@Getter @Builder
public class MonthCloseResponse {

    private Long id;
    private String month;
    private LocalDateTime closedAt;
    private Currency currency;          // always UZS (snapshot anchor)

    private BigDecimal startBalance;
    private BigDecimal income;
    private BigDecimal donation;
    private BigDecimal emergency;
    private BigDecimal investments;
    private BigDecimal stocks;
    private BigDecimal savings;
    private BigDecimal everydaySpend;
    private BigDecimal totalSpent;
    private BigDecimal leftover;

    private List<WalletResult> wallets;

    @Getter @Builder
    public static class WalletResult {
        private String walletType;
        private Long cardId;
        private Currency currency;
        private BigDecimal computedBalance;
        private BigDecimal enteredBalance;
        private BigDecimal everydaySpend;
        private Long adjustmentTxId;
    }

    public static MonthCloseResponse from(MonthClose m) {
        List<WalletResult> wallets = m.getWallets().stream()
                .map(MonthCloseResponse::walletFrom).toList();
        return MonthCloseResponse.builder()
                .id(m.getId())
                .month(java.time.YearMonth.from(m.getMonth()).toString())
                .closedAt(m.getClosedAt())
                .currency(Currency.UZS)
                .startBalance(m.getStartBalanceUzs())
                .income(m.getIncomeUzs())
                .donation(m.getDonationUzs())
                .emergency(m.getEmergencyUzs())
                .investments(m.getInvestmentsUzs())
                .stocks(m.getStocksUzs())
                .savings(m.getSavingsUzs())
                .everydaySpend(m.getEverydaySpendUzs())
                .totalSpent(m.getTotalSpentUzs())
                .leftover(m.getLeftoverUzs())
                .wallets(wallets)
                .build();
    }

    private static WalletResult walletFrom(MonthCloseWallet w) {
        return WalletResult.builder()
                .walletType(w.getWalletType())
                .cardId(w.getCardId())
                .currency(w.getCurrency())
                .computedBalance(w.getComputedBalance())
                .enteredBalance(w.getEnteredBalance())
                .everydaySpend(w.getEverydaySpend())
                .adjustmentTxId(w.getAdjustmentTxId())
                .build();
    }
}
