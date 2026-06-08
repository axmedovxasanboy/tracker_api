package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.InvestmentType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter @Builder
public class InvestmentResponse {

    private Long id;
    private String name;
    private InvestmentType type;
    private BigDecimal investedAmount;
    private Currency currency;
    private LocalDate purchaseDate;
    private String broker;
    private String description;
    private boolean emergencyFund;
    private boolean savingsGoal;
    private BigDecimal targetAmount;
    /** Current/market value. Falls back to investedAmount when not explicitly set. */
    private BigDecimal currentValue;
    /** currentValue / targetAmount as a percentage; null when there is no target. */
    private BigDecimal progressPercent;
    /** True when recorded as an opening balance (already-owned holding; no transaction, excluded
        from the monthly allocation buckets). */
    private boolean openingBalance;
    private LocalDateTime createdAt;

    public static InvestmentResponse from(Investment i) {
        BigDecimal value = i.getCurrentValue() != null ? i.getCurrentValue() : i.getInvestedAmount();
        BigDecimal progress = null;
        if (i.getTargetAmount() != null && i.getTargetAmount().signum() > 0 && value != null) {
            progress = value.multiply(BigDecimal.valueOf(100))
                    .divide(i.getTargetAmount(), 2, RoundingMode.HALF_UP);
        }
        return InvestmentResponse.builder()
                .id(i.getId())
                .name(i.getName())
                .type(i.getType())
                .investedAmount(i.getInvestedAmount())
                .currency(i.getCurrency())
                .purchaseDate(i.getPurchaseDate())
                .broker(i.getBroker())
                .description(i.getDescription())
                .emergencyFund(Boolean.TRUE.equals(i.getEmergencyFund()))
                .savingsGoal(Boolean.TRUE.equals(i.getSavingsGoal()))
                .targetAmount(i.getTargetAmount())
                .currentValue(value)
                .progressPercent(progress)
                .openingBalance(Boolean.TRUE.equals(i.getOpeningBalance()))
                .createdAt(i.getCreatedAt())
                .build();
    }
}
