package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.enums.CardType;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;

@Getter @Builder
public class CardSummaryResponse {
    private Long id;
    private String name;
    private String bankName;
    private CardType type;
    private String lastFourDigits;
    private BigDecimal currentBalance;
    private Currency currency;
    private String color;
}
