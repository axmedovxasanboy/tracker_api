package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.entity.BankLoan;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter @Builder
public class BankLoanResponse {

    private Long id;
    private String bankName;
    private String loanName;
    private BigDecimal totalAmount;
    private Currency currency;
    private LocalDate takenDate;
    private LocalDate endDate;
    private BigDecimal monthlyPayment;
    private LocalDateTime createdAt;

    public static BankLoanResponse from(BankLoan b) {
        return BankLoanResponse.builder()
                .id(b.getId())
                .bankName(b.getBankName())
                .loanName(b.getLoanName())
                .totalAmount(b.getTotalAmount())
                .currency(b.getCurrency())
                .takenDate(b.getTakenDate())
                .endDate(b.getEndDate())
                .monthlyPayment(b.getMonthlyPayment())
                .createdAt(b.getCreatedAt())
                .build();
    }
}
