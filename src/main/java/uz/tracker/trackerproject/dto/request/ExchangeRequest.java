package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Cross-wallet / cross-currency exchange. Either side can be a card (cardId set) or
 * "cash" (cardId null). When the two currencies differ, fromAmount and toAmount can
 * (and usually will) differ — the implied FX rate is fromAmount / toAmount.
 *
 * Validation rules enforced by TransactionService.exchange():
 *   – at least one of fromCardId / toCardId must differ from the other, OR the
 *     currencies must differ (cash-to-cash same-currency is rejected as meaningless)
 *   – each side's card currency must match its side's currency
 *   – fromAmount and toAmount strictly > 0
 */
@Getter @Setter
public class ExchangeRequest {

    /** Null = pay from cash. */
    private Long fromCardId;

    @NotNull(message = "Source currency is required")
    private Currency fromCurrency;

    @NotNull(message = "Amount sent is required")
    @DecimalMin(value = "0.0001", message = "Amount sent must be greater than 0")
    private BigDecimal fromAmount;

    /** Null = deposit into cash. */
    private Long toCardId;

    @NotNull(message = "Destination currency is required")
    private Currency toCurrency;

    @NotNull(message = "Amount received is required")
    @DecimalMin(value = "0.0001", message = "Amount received must be greater than 0")
    private BigDecimal toAmount;

    @NotNull(message = "Date is required")
    private LocalDate transactionDate;

    private String description;
}
