package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** What one check-in recorded, wallet by wallet. */
@Getter @Builder
public class WalletCheckInResponse {

    private Long id;
    private LocalDate date;
    private List<Line> lines;
    /** Net everyday spending this check-in booked: untracked spending minus any surplus found. */
    private BigDecimal everydayRecorded;
    /** Net everyday spending recorded so far this month, this check-in included. */
    private BigDecimal everydaySoFar;
    /** When the next check-in is due, or null when the month close will come first. */
    private LocalDate nextDueOn;

    @Getter @Builder
    public static class Line {
        private String walletType;
        private Long cardId;
        private String label;
        private Currency currency;
        private BigDecimal computedBalance;
        private BigDecimal enteredBalance;
        /** computed − entered: positive is untracked spending, negative a surplus. */
        private BigDecimal everydaySpend;
        private Long adjustmentTxId;
    }
}
