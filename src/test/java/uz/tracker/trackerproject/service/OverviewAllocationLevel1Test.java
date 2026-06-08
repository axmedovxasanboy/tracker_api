package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.service.OverviewService.Level1Plan;

import java.math.BigDecimal;
import java.math.MathContext;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-scenario tests for the corrected Level-1 allocation engine (PR-1).
 * Exercises the PURE static core ({@link OverviewService#computeLevel1Plan} and
 * {@link OverviewService#debtMonthlyCharge}) — no Spring context, no mocks. All amounts UZS.
 *
 * Model (owner spec + decisions D1–D4 / confirms C1–C4):
 *   leftBalance = income − mandatory ; loanInstallments = bank + LoanTaken ; debt34 = Debt 34% of original (capped).
 *   calcBase per case ; percentages × calcBase ; tight/comfortable on calcBase at the 5M cutoff ; >70% strict → Case C.
 */
class OverviewAllocationLevel1Test {

    private static final BigDecimal CUTOFF = new BigDecimal("5000000"); // 5M default
    private static final BigDecimal Z = BigDecimal.ZERO;

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private static BigDecimal ratio(String payments, String income) {
        return bd(payments).divide(bd(income), MathContext.DECIMAL64);
    }

    /** amount = pct% × calcBase. */
    private static BigDecimal amt(Level1Plan p, int idx) {
        return p.calcBaseUzs().multiply(bd(p.pct()[idx])).movePointLeft(2);
    }

    private static void assertPct(Level1Plan p, String d, String e, String i, String s) {
        assertArrayEquals(new String[]{d, e, i, s}, p.pct(), "pct tuple");
    }

    private static void assertAmount(BigDecimal actual, String expected) {
        assertEquals(0, actual.compareTo(bd(expected)), "expected " + expected + " got " + actual);
    }

    @Test
    void t1_caseA_noDebt() {
        Level1Plan p = OverviewService.computeLevel1Plan(bd("12000000"), bd("2000000"), Z, Z, Z, Z, CUTOFF);
        assertEquals("1.1", p.scenarioKey());
        assertAmount(p.calcBaseUzs(), "10000000");
        assertPct(p, "10", "5", "15", "5");
        assertAmount(amt(p, 0), "1000000");   // donation 10%
        assertAmount(amt(p, 1), "500000");    // emergency 5%
        assertAmount(amt(p, 2), "1500000");   // investment 15%
        assertAmount(amt(p, 3), "500000");    // stocks 5%
    }

    @Test
    void t2_b1_loanOnly_comfortable() {
        // income 12M, mandatory 2M, loanTaken 3M → base 7M (≥5M comfortable)
        Level1Plan p = OverviewService.computeLevel1Plan(bd("12000000"), bd("2000000"),
                Z, bd("3000000"), Z, ratio("3000000", "12000000"), CUTOFF);
        assertEquals("1.2.1.comfortable", p.scenarioKey());
        assertAmount(p.calcBaseUzs(), "7000000");
        assertPct(p, "7", "3", "10", "3");
        assertAmount(amt(p, 0), "490000");
        assertAmount(amt(p, 2), "700000");
        assertAmount(amt(p, 3), "210000");    // stocks 3%
    }

    @Test
    void t3_b1_loanOnly_tight() {
        // income 9M, mandatory 1M, bank 4M → base 4M (<5M tight)
        Level1Plan p = OverviewService.computeLevel1Plan(bd("9000000"), bd("1000000"),
                bd("4000000"), Z, Z, ratio("4000000", "9000000"), CUTOFF);
        assertEquals("1.2.1.tight", p.scenarioKey());
        assertAmount(p.calcBaseUzs(), "4000000");
        assertPct(p, "5", "2", "8", null);
        assertAmount(amt(p, 0), "200000");
        assertAmount(amt(p, 2), "320000");
    }

    @Test
    void t4_b2_debtOnly_comfortable() {
        // income 12M, mandatory 1M, debt34 2.04M → base 8.96M
        Level1Plan p = OverviewService.computeLevel1Plan(bd("12000000"), bd("1000000"),
                Z, Z, bd("2040000"), ratio("2040000", "12000000"), CUTOFF);
        assertEquals("1.2.2.comfortable", p.scenarioKey());
        assertAmount(p.calcBaseUzs(), "8960000");
        assertPct(p, "7", "3", "10", "3");
        assertAmount(amt(p, 2), "896000");
        assertAmount(amt(p, 3), "268800");    // stocks 3%
    }

    @Test
    void t5_b2_debtOnly_tight() {
        // income 8M, mandatory 1M, debt34 4.08M → base 2.92M
        Level1Plan p = OverviewService.computeLevel1Plan(bd("8000000"), bd("1000000"),
                Z, Z, bd("4080000"), ratio("4080000", "8000000"), CUTOFF);
        assertEquals("1.2.2.tight", p.scenarioKey());
        assertAmount(p.calcBaseUzs(), "2920000");
        assertPct(p, "5", "2", "8", null);
        assertAmount(amt(p, 1), "58400");
    }

    @Test
    void t6_b3_loanAndDebt_noSplit() {
        // income 14M, mandatory 1M, loan 3M, debt34 1.7M → base 8.3M; flat 5/0/5/0
        Level1Plan p = OverviewService.computeLevel1Plan(bd("14000000"), bd("1000000"),
                bd("3000000"), Z, bd("1700000"), ratio("4700000", "14000000"), CUTOFF);
        assertEquals("1.2.3", p.scenarioKey());
        assertAmount(p.calcBaseUzs(), "8300000");
        assertPct(p, "5", null, "5", null);
        assertAmount(amt(p, 0), "415000");
        assertAmount(amt(p, 2), "415000");
    }

    @Test
    void t7_caseC_heavy() {
        // income 10M, loan 5M, debt34 3M → ratio 0.8 > 0.70 → Case C; base 2M; 2/0/0/0
        Level1Plan p = OverviewService.computeLevel1Plan(bd("10000000"), Z,
                bd("5000000"), Z, bd("3000000"), ratio("8000000", "10000000"), CUTOFF);
        assertEquals("1.3", p.scenarioKey());
        assertAmount(p.calcBaseUzs(), "2000000");
        assertPct(p, "2", null, null, null);
        assertAmount(amt(p, 0), "40000");
    }

    @Test
    void t8_ratioExactly70_staysCaseB() {
        // income 10M, loan 7M (=70%), no debt → strict '>' means NOT heavy → Case B1 (base 3M tight)
        Level1Plan p = OverviewService.computeLevel1Plan(bd("10000000"), Z,
                bd("7000000"), Z, Z, ratio("7000000", "10000000"), CUTOFF);
        assertEquals("1.2.1.tight", p.scenarioKey());
    }

    @Test
    void t9_baseExactly5M_comfortable() {
        // income 8M, loan 3M → base 5M → comfortable (≥ cutoff)
        Level1Plan p = OverviewService.computeLevel1Plan(bd("8000000"), Z,
                bd("3000000"), Z, Z, ratio("3000000", "8000000"), CUTOFF);
        assertEquals("1.2.1.comfortable", p.scenarioKey());
        assertAmount(p.calcBaseUzs(), "5000000");
    }

    @Test
    void t10_debtCharge_capsAtResidual_andTerminates() {
        // 34% of original 6M = 2.04M, but capped at the 1M residual (final month)
        assertAmount(OverviewService.debtMonthlyCharge(bd("6000000"), bd("5000000")), "1000000");
        // fresh debt → full 34% of original
        assertAmount(OverviewService.debtMonthlyCharge(bd("6000000"), Z), "2040000");
        // cleared → drops out
        assertEquals(0, OverviewService.debtMonthlyCharge(bd("6000000"), bd("6000000")).signum());
    }

    @Test
    void overIndebted_baseClampsToZero() {
        // income 5M, loan 6M → ratio 1.2 > 0.70 (Case C); base would be −1M → clamp to 0
        Level1Plan p = OverviewService.computeLevel1Plan(bd("5000000"), Z,
                bd("6000000"), Z, Z, ratio("6000000", "5000000"), CUTOFF);
        assertEquals("1.3", p.scenarioKey());
        assertEquals(0, p.calcBaseUzs().signum());
    }
}
