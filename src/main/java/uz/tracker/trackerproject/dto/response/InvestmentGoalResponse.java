package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.InvestmentType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** An investment goal with what its accounts hold now (Batch 79). */
@Getter @Builder
public class InvestmentGoalResponse {

    private Long id;
    private String name;
    private BigDecimal targetAmount;
    private Currency currency;
    private String note;
    /** Σ value (currentValue, else investedAmount) of the linked accounts. */
    private BigDecimal saved;
    /** max(0, target − saved). */
    private BigDecimal left;
    /** saved ÷ target × 100, one decimal; may be over 100. */
    private BigDecimal progressPercent;
    /** saved ≥ target. */
    private boolean reached;
    /** The linked accounts, by broker then name. */
    private List<Account> investments;
    private LocalDateTime createdAt;

    @Getter @Builder
    public static class Account {
        private Long id;
        private String name;
        private String broker;
        private InvestmentType type;
        private BigDecimal value;
    }
}
