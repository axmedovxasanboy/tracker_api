package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.util.List;

/**
 * Month-close preview: the per-wallet computed balances (in each wallet's own currency) the
 * user will reconcile against, plus this month's envelope figures in the display currency.
 * Everyday spend is NOT computed here — it's derived per wallet on commit from the entered
 * balances.
 */
@Getter @Builder
public class MonthClosePreviewResponse {

    private String month;
    private Currency currency;

    private boolean alreadyClosed;
    /** True when this month may be closed now (not already closed, not future, prior month closed). */
    private boolean closeable;
    /** Human-readable reason when {@code closeable} is false; null otherwise. */
    private String blockedReason;

    private List<WalletLine> wallets;

    // Month figures, in the display currency.
    private BigDecimal startBalance;   // carried in from the previous month
    private BigDecimal income;         // earned this month
    private BigDecimal donation;
    private BigDecimal emergency;
    private BigDecimal investments;
    private BigDecimal stocks;
    private BigDecimal savings;
    private BigDecimal taggedTotal;    // donation+emergency+investments+stocks+savings
    private BigDecimal spendableNow;   // current total wallet balance (display currency)

    private boolean fxRatesUsingDefaults;

    @Getter @Builder
    public static class WalletLine {
        private String walletType;        // CARD | CASH
        private Long cardId;              // null for CASH
        private String label;            // card name / "Cash UZS"
        private Currency currency;
        private BigDecimal computedBalance; // in this wallet's own currency
    }
}
