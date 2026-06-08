package uz.tracker.trackerproject.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.util.List;

/**
 * Commit a permanent month close. {@code wallets} carries the user's real end-of-month balance
 * for each wallet (each card + each cash pot per currency); the server reconciles every current
 * wallet, defaulting any wallet not present here to its computed balance (zero adjustment).
 */
@Getter @Setter
public class MonthCloseRequest {

    /** YYYY-MM of the month being closed. */
    @NotBlank
    private String month;

    @Valid
    private List<WalletBalanceEntry> wallets;

    @Getter @Setter
    public static class WalletBalanceEntry {
        /** "CARD" or "CASH". */
        @NotBlank
        private String walletType;

        /** Required for CARD wallets; null for the per-currency CASH pot. */
        private Long cardId;

        @NotNull
        private Currency currency;

        @NotNull @DecimalMin("0")
        private BigDecimal enteredBalance;
    }
}
