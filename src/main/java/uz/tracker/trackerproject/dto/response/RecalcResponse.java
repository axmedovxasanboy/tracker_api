package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code GET / POST /api/v1/levels/recalculate} (RECALC-SPEC §1, Batch 76): this month's savings rule
 * as it stands ({@link #current} — what its 1st fixed, or the owner's last recalculation) beside the
 * rule today's data gives ({@link #recalculated}). Percentages are whole percents of the savings base.
 */
@Getter @Builder
public class RecalcResponse {
    /** 'YYYY-MM'. */
    private String month;
    private Rule current;
    private Rule recalculated;
    /** The level or the situation differs. */
    private boolean changed;
    /** ISO local date-time (Asia/Tashkent) of the owner's last recalculation this month; null when never. */
    private String recalculatedAt;

    @Getter @Builder
    public static class Rule {
        private int level;
        /** One of {@code SavingsRules.SITUATIONS}. */
        private String situation;
        /** The tight / comfortable split line of this level's rules version in force that month; may be null. */
        private BigDecimal cutoff;
        private Percents percents;
        /** Every own saving (Batch 74) with its percentage in this rule, 0 allowed; same order in both blocks. */
        private List<Own> own;
        /** The three plus every own saving. */
        private BigDecimal total;
        /** Why the rule came out this way (RECALC-SPEC §4, Batch 77): every figure it was decided with. */
        private Why why;
    }

    /** The figures a rule was decided with — the same code path that decides it (OverviewService.traceAsOf). */
    @Getter @Builder
    public static class Why {
        /** MONTH_START | RECALCULATED | TODAY. */
        private String basis;
        /** 'YYYY-MM-DD': the day whose data decided it. */
        private String asOf;
        /** The trace reproduces the rule shown (false on {@code current} when data changed since). */
        private boolean exact;
        private BigDecimal income;
        private BigDecimal bills;
        private BigDecimal leftAfterBills;
        /** The band of left-after-bills for this level; both null on Level 5, levelTo null at the top base level. */
        private BigDecimal levelFrom;
        private BigDecimal levelTo;
        /** 'YYYY-MM' when a Level 5 period gives the level; else null. */
        private String level5Since;
        private List<BankLoanLine> bankLoans;
        private BigDecimal bankMonthly;
        private List<LoanLine> loans;
        /** Every ask in {@link #loans} — what the split base subtracts. */
        private BigDecimal asksTotal;
        /** The MONTHLY loans' asks. */
        private BigDecimal monthlyAsks;
        /** 10% of the income. */
        private BigDecimal smallMonthlyLimit;
        /** monthlyAsks > 0 and ≤ the limit → they do not count as money owed for the rule. */
        private boolean smallMonthlyIgnored;
        private BigDecimal peopleDebtForRule;
        /** (bankMonthly + asksTotal) ÷ income × 100, one decimal. */
        private BigDecimal debtShare;
        private BigDecimal heavyLine;
        /** What is left after bills and loans — what the tight / comfortable split reads. */
        private BigDecimal leftAfterLoans;
        private BigDecimal cutoff;
        private boolean hasBank;
        private boolean hasPeopleDebt;
    }

    @Getter @Builder
    public static class BankLoanLine {
        private Long id;
        private String name;
        private BigDecimal monthly;
    }

    @Getter @Builder
    public static class LoanLine {
        /** LOAN (borrowed money) | DEBT. */
        private String kind;
        private Long id;
        private String name;
        /** MONTHLY | ASAP | NASIYA (an open nasiya). */
        private String type;
        /** What was left at the cut (left now for AFTER_CUT). */
        private BigDecimal left;
        /** This month's ask the rule counted; 0 when it counted none. */
        private BigDecimal ask;
        /** null = counted as is; NOT_STARTED | NASIYA_LATER | SMALL_MONTHLY | AFTER_CUT. */
        private String note;
        /** An open nasiya's deadline ('YYYY-MM-DD'); null otherwise. */
        private String dueDate;
    }

    @Getter @Builder
    public static class Percents {
        private BigDecimal donation;
        private BigDecimal emergency;
        private BigDecimal investments;
    }

    @Getter @Builder
    public static class Own {
        private Long id;
        private String name;
        private BigDecimal percent;
    }
}
