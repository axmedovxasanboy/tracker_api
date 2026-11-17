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

    /** A nasiya loan (Batch 75); false for every other loan. */
    private boolean nasiya;
    /** The money that came into the wallet; null unless set (then it equals totalAmount). */
    private BigDecimal principalAmount;
    /** A nasiya's cost: max(0, total − principal) — its fee, later fee + penalty. Null unless nasiya. */
    private BigDecimal extraCost;
    /** The day a nasiya was switched to installments; null unless switched. */
    private LocalDate installmentsSince;

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
                .nasiya(l.isNasiyaLoan())
                .principalAmount(l.getPrincipalAmount())
                .extraCost(l.isNasiyaLoan() ? extraCost(l) : null)
                .installmentsSince(l.getInstallmentsSince())
                .build();
    }

    private static BigDecimal extraCost(LoanTaken l) {
        BigDecimal principal = l.receivedPrincipal();
        if (principal == null || l.getTotalAmount() == null) return BigDecimal.ZERO;
        BigDecimal cost = l.getTotalAmount().subtract(principal);
        return cost.signum() > 0 ? cost : BigDecimal.ZERO;
    }
}
