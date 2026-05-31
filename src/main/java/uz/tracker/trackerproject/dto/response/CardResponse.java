package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.entity.Card;
import uz.tracker.trackerproject.enums.CardType;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter @Builder
public class CardResponse {

    private Long id;
    private String name;
    private String bankName;
    private CardType type;
    private String lastFourDigits;
    private BigDecimal initialBalance;
    private BigDecimal currentBalance;
    private Currency currency;
    private String color;
    private boolean hasFullNumber;
    private boolean hasPin;
    private LocalDateTime createdAt;

    public static CardResponse from(Card c, BigDecimal txSum) {
        BigDecimal current = c.getInitialBalance().add(txSum != null ? txSum : BigDecimal.ZERO);
        return CardResponse.builder()
                .id(c.getId())
                .name(c.getName())
                .bankName(c.getBankName())
                .type(c.getType())
                .lastFourDigits(c.getLastFourDigits())
                .initialBalance(c.getInitialBalance())
                .currentBalance(current)
                .currency(c.getCurrency())
                .color(c.getColor())
                .hasFullNumber(c.getFullNumber() != null && !c.getFullNumber().isBlank())
                .hasPin(c.getPin() != null && !c.getPin().isBlank())
                .createdAt(c.getCreatedAt())
                .build();
    }
}
