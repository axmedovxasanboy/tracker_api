package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter @Builder
public class MonthlyDataResponse {

    private int month;
    private String monthName;
    private BigDecimal income;
    private BigDecimal expense;
    private BigDecimal net;
}
