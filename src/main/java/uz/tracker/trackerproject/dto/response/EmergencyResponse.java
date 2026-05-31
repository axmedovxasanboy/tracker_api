package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.entity.Emergency;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter @Builder
public class EmergencyResponse {

    private Long id;
    private BigDecimal amount;
    private Currency currency;
    private LocalDate date;
    private String description;
    private LocalDateTime createdAt;

    public static EmergencyResponse from(Emergency e) {
        return EmergencyResponse.builder()
                .id(e.getId())
                .amount(e.getAmount())
                .currency(e.getCurrency())
                .date(e.getDate())
                .description(e.getDescription())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
