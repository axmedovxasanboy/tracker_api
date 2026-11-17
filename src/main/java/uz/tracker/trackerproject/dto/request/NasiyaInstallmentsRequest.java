package uz.tracker.trackerproject.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** POST /finance/loans-taken/{id}/installments — a nasiya switched to installments with Uzum's figures. */
@Getter @Setter
public class NasiyaInstallmentsRequest {
    /** What is still left to pay, as Uzum shows it (fee + penalty included). */
    private BigDecimal left;
    /** Each installment. */
    private BigDecimal monthlyPayment;
    /** The first installment's date; its day is every installment's day. */
    private LocalDate firstPaymentDate;
}
