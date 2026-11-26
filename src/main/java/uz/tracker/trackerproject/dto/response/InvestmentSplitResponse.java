package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.enums.InvestmentType;

import java.math.BigDecimal;
import java.util.List;

/**
 * Which account each part of the month's Investments money goes to (Investments page, Batch 81).
 * {@code target} is what the app asks for the Investments bucket this month (carried part included,
 * the same figure Savings shows); the rows split it by share in whole 1.000s and add up to it exactly.
 */
@Getter @Builder
public class InvestmentSplitResponse {

    /** YYYY-MM. */
    private String month;
    /** This month's Investments figure: the rule's amount + what earlier months carried. */
    private BigDecimal target;
    /** Everything counted as put into the Investments bucket this month. */
    private BigDecimal paid;
    /** The part of {@link #paid} not linked to any listed account. */
    private BigDecimal paidOutside;
    /** max(0, target − paid). */
    private BigDecimal left;
    /** True once the owner has set the shares; false = an equal split. */
    private boolean custom;
    private List<Row> rows;

    @Getter @Builder
    public static class Row {
        private Long investmentId;
        private String name;
        private String broker;
        private InvestmentType type;
        /** Share of the split, one decimal. */
        private BigDecimal percent;
        /** This month's part of {@code target}. */
        private BigDecimal amount;
        /** Put into this account this month (Investments bucket). */
        private BigDecimal paid;
        /** max(0, amount − paid). */
        private BigDecimal left;
    }
}
