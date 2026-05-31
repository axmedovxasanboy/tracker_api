package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.entity.CashBalance;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter @Builder
public class CashBalanceResponse {

    private Long id;
    private Currency currency;
    private BigDecimal initialBalance;
    private BigDecimal currentBalance; // initial + net cardless tx
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static CashBalanceResponse from(CashBalance c, BigDecimal txNet) {
        BigDecimal current = c.getInitialBalance().add(txNet != null ? txNet : BigDecimal.ZERO);
        return CashBalanceResponse.builder()
                .id(c.getId())
                .currency(c.getCurrency())
                .initialBalance(c.getInitialBalance())
                .currentBalance(current)
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}
