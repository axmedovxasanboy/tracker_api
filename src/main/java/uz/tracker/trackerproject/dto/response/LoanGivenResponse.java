package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.entity.LoanGiven;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter @Builder
public class LoanGivenResponse {

    private Long id;
    private String debtorName;
    private BigDecimal totalAmount;
    private BigDecimal receivedAmount;
    private BigDecimal pendingAmount;
    private Currency currency;
    private LocalDate lentDate;
    private LocalDate expectedReturnDate;
    private RecordStatus status;
    private String description;
    private LocalDateTime createdAt;

    public static LoanGivenResponse from(LoanGiven l) {
        return LoanGivenResponse.builder()
                .id(l.getId())
                .debtorName(l.getDebtorName())
                .totalAmount(l.getTotalAmount())
                .receivedAmount(l.getReceivedAmount())
                .pendingAmount(l.getTotalAmount().subtract(l.getReceivedAmount()))
                .currency(l.getCurrency())
                .lentDate(l.getLentDate())
                .expectedReturnDate(l.getExpectedReturnDate())
                .status(l.getStatus())
                .description(l.getDescription())
                .createdAt(l.getCreatedAt())
                .build();
    }
}
