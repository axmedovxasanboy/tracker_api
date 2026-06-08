package uz.tracker.trackerproject.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Contribute additional money to an existing investment / savings goal. */
@Getter @Setter
public class InvestmentContributeRequest {

    @NotNull @DecimalMin("0.01")
    private BigDecimal amount;

    /** Must match the investment's currency. */
    @NotNull
    private Currency currency;

    @NotNull
    private LocalDate date;

    /** Optional — null = cash. Ignored when {@link #noWallet} is true. */
    private Long cardId;

    /** When true, record the contribution WITHOUT moving money: no transaction is created and no
        wallet is debited; only the invested total is bumped. Use when the funds came from outside
        your tracked wallets (e.g. money already sitting in the investment account). */
    private Boolean noWallet;

    /** Optional override for the mirrored transaction's category; null = auto-pick by sub-type. */
    private Long categoryId;

    private String description;
}
