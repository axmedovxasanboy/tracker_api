package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;

@Getter @Builder
public class OverviewIncomeResponse {

    /** ISO-style identifier for the requested period — "YYYY-MM". */
    private String month;

    /** Currency the values are expressed in. */
    private Currency currency;

    /**
     * Sum of INCOME-type transactions in the requested month, in UZS. Transfers and
     * everyday-spending reconciliation rows are excluded.
     */
    private BigDecimal actualIncome;

    /**
     * The user's Settings.monthlyStableIncome. Drives the tier dashboard math (see
     * /overview/tier). Null when not configured.
     */
    private BigDecimal stableIncome;

}
