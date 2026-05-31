package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.entity.Debt;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter @Builder
public class DebtResponse {

    private Long id;
    private String creditorName;
    private BigDecimal totalAmount;
    private BigDecimal paidAmount;
    private BigDecimal remainingAmount;
    private Currency currency;
    private LocalDate borrowedDate;
    private LocalDate dueDate;
    private LocalDate paymentStartDate;
    private RecordStatus status;
    private String description;
    private LocalDateTime createdAt;

    public static DebtResponse from(Debt d) {
        return DebtResponse.builder()
                .id(d.getId())
                .creditorName(d.getCreditorName())
                .totalAmount(d.getTotalAmount())
                .paidAmount(d.getPaidAmount())
                .remainingAmount(d.getTotalAmount().subtract(d.getPaidAmount()))
                .currency(d.getCurrency())
                .borrowedDate(d.getBorrowedDate())
                .dueDate(d.getDueDate())
                .paymentStartDate(d.getPaymentStartDate())
                .status(d.getStatus())
                .description(d.getDescription())
                .createdAt(d.getCreatedAt())
                .build();
    }
}
