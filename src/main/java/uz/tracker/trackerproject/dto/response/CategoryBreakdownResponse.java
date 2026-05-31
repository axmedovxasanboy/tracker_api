package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter @Builder
public class CategoryBreakdownResponse {

    private String category;
    private String color;
    private BigDecimal amount;
    private double percentage;
}
