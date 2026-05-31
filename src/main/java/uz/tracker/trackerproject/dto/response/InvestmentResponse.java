package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.InvestmentType;

import java.math.BigDecimal;
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
    private LocalDateTime createdAt;

    public static InvestmentResponse from(Investment i) {
        return InvestmentResponse.builder()
                .id(i.getId())
                .name(i.getName())
                .type(i.getType())
                .investedAmount(i.getInvestedAmount())
                .currency(i.getCurrency())
                .purchaseDate(i.getPurchaseDate())
                .broker(i.getBroker())
                .description(i.getDescription())
                .createdAt(i.getCreatedAt())
                .build();
    }
}
