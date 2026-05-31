package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.entity.MonthlyPayment;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter @Builder
public class MonthlyPaymentResponse {

    private Long id;
    private String name;
    private BigDecimal amount;
    private Currency currency;
    private Integer dueDay;
    private Boolean active;
    private String description;
    private LocalDate nextDueDate;
    private LocalDate subscribedSince;
    private CategoryResponse category;
    private LocalDateTime createdAt;

    /** Sum of all linked payment transactions. Zero when none recorded. */
    private BigDecimal totalPaid;

    /** Number of payments recorded against this subscription. */
    private Long paymentCount;

    public static MonthlyPaymentResponse from(MonthlyPayment m) {
        return from(m, BigDecimal.ZERO, 0L);
    }

    public static MonthlyPaymentResponse from(MonthlyPayment m, BigDecimal totalPaid, long paymentCount) {
        return MonthlyPaymentResponse.builder()
                .id(m.getId())
                .name(m.getName())
                .amount(m.getAmount())
                .currency(m.getCurrency())
                .dueDay(m.getDueDay())
                .active(m.getActive())
                .description(m.getDescription())
                .nextDueDate(m.getNextDueDate())
                .subscribedSince(m.getSubscribedSince())
                .category(m.getCategory() != null ? CategoryResponse.from(m.getCategory()) : null)
                .createdAt(m.getCreatedAt())
                .totalPaid(totalPaid != null ? totalPaid : BigDecimal.ZERO)
                .paymentCount(paymentCount)
                .build();
    }
}
