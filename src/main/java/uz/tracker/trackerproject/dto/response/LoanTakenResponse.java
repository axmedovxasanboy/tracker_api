package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.enums.RepaymentType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter @Builder
public class LoanTakenResponse {

    private Long id;
    private String lenderName;
    /** The lender on the owner's list of people; null until linked. */
    private Long lenderId;
    /** MONTHLY (plannedMonthlyPayment from paymentStartDate) or ASAP (as fast as possible). */
    private RepaymentType repaymentType;
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
    /** Frozen monthly installment (D3: a LoanTaken is a "loan", paid as a full installment). */
    private BigDecimal monthlyPayment;
    private BigDecimal plannedMonthlyPayment;

    public static LoanTakenResponse from(LoanTaken l) {
        return LoanTakenResponse.builder()
                .id(l.getId())
                .lenderName(l.getLenderName())
                .lenderId(l.getLenderId())
                .repaymentType(l.effectiveRepaymentType())
                .totalAmount(l.getTotalAmount())
                .paidAmount(l.getPaidAmount())
                .remainingAmount(l.getTotalAmount().subtract(l.getPaidAmount()))
                .currency(l.getCurrency())
                .borrowedDate(l.getBorrowedDate())
                .dueDate(l.getDueDate())
                .paymentStartDate(l.getPaymentStartDate())
                .status(l.getStatus())
                .description(l.getDescription())
                .createdAt(l.getCreatedAt())
                .monthlyPayment(l.getMonthlyPayment())
                .plannedMonthlyPayment(l.getPlannedMonthlyPayment())
                .build();
    }
}
