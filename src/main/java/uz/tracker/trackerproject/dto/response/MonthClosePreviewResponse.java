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
    private BigDecimal taggedTotal;    // donation+emergency+investments+stocks+savings, marks included
    /**
     * The recorded-only half of {@link #taggedTotal} — the figure this close will freeze, stated
     * outright so the dialog never has to derive it by subtraction.
     * Always {@code taggedTotal − markedNotMoved}.
     */
    private BigDecimal taggedRecorded;
    private BigDecimal spendableNow;   // current total wallet balance (display currency)

    // "Already paid" marks folded into the bucket figures above so the preview quotes the same
    // number the plan does. The close itself books only the recorded part — everyday spending is
    // derived from real wallet movement — so the UI must label this share before the user commits.
    private BigDecimal markedDonation;
    private BigDecimal markedEmergency;
    private BigDecimal markedInvestments;
    /**
     * Σ of every BUCKET mark for this month. It is kept out of the snapshot's everyday-spend
     * arithmetic (see {@link #taggedRecorded}), but the bucket figures above still count it — the
     * close changes no number the user is reading, only what the frozen arithmetic is built on.
     */
    private BigDecimal markedNotMoved;

    @Getter @Builder
    public static class WalletLine {
        private String walletType;        // CARD | CASH
        private Long cardId;              // null for CASH
        private String label;            // card name / "Cash UZS"
        private Currency currency;
        private BigDecimal computedBalance; // in this wallet's own currency
    }
}
