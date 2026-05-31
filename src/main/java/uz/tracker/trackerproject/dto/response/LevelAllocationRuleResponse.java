package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.entity.LevelAllocationRule;

import java.math.BigDecimal;

@Getter @Builder
public class LevelAllocationRuleResponse {

    private Integer level;
    private String subLevel;
    private BigDecimal donationPercent;
    private BigDecimal emergencyPercent;
    private BigDecimal investmentsPercent;
    private BigDecimal stocksPercent;
    private String note;

    public static LevelAllocationRuleResponse from(LevelAllocationRule r) {
        return LevelAllocationRuleResponse.builder()
                .level(r.getLevel())
                .subLevel(r.getSubLevel())
                .donationPercent(r.getDonationPercent())
                .emergencyPercent(r.getEmergencyPercent())
                .investmentsPercent(r.getInvestmentsPercent())
                .stocksPercent(r.getStocksPercent())
                .note(r.getNote())
                .build();
    }
}
