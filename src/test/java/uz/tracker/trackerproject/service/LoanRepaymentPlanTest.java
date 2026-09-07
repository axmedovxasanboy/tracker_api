package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.entity.LoanTaken;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A large loan you intend to settle in one go should not be charged 34% of the whole sum every
 * month — that is what made the app demand repayment the user never planned to make. Setting a
 * monthly plan replaces the default rule; leaving it unset must change nothing.
 */
class LoanRepaymentPlanTest {

    private LoanTaken loan(String total, String paid, String planned) {
        LoanTaken l = new LoanTaken();
        l.setTotalAmount(new BigDecimal(total));
        l.setPaidAmount(new BigDecimal(paid));
        if (planned != null) l.setPlannedMonthlyPayment(new BigDecimal(planned));
        return l;
    }

    @Test
    void withoutAPlanTheDefault34PercentRuleStillApplies() {
        LoanTaken l = loan("10000000", "0", null);
        assertThat(OverviewService.plannedOrDefaultCharge(l))
                .isEqualByComparingTo(OverviewService.debtMonthlyCharge(l.getTotalAmount(), l.getPaidAmount()));
        // Sanity: that default really is the punishing figure the plan exists to replace.
        assertThat(OverviewService.plannedOrDefaultCharge(l)).isEqualByComparingTo("3400000");
    }

    @Test
    void aPlanReplacesTheDefaultCharge() {
        assertThat(OverviewService.plannedOrDefaultCharge(loan("10000000", "0", "500000")))
                .isEqualByComparingTo("500000");
    }

    @Test
    void theFinalMonthIsCappedAtWhatIsActuallyLeft() {
        // Planned 500k but only 200k outstanding — charging the full plan would overpay.
        assertThat(OverviewService.plannedOrDefaultCharge(loan("10000000", "9800000", "500000")))
                .isEqualByComparingTo("200000");
    }

    @Test
    void aSettledLoanCostsNothing() {
        assertThat(OverviewService.plannedOrDefaultCharge(loan("10000000", "10000000", "500000")))
                .isEqualByComparingTo("0");
    }

    @Test
    void aZeroOrNegativePlanFallsBackToTheDefaultRule() {
        // Guards against a blank/0 input silently zeroing the debt charge entirely.
        assertThat(OverviewService.plannedOrDefaultCharge(loan("10000000", "0", "0")))
                .isEqualByComparingTo("3400000");
    }
}
