package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the web's Analytics page shows, for a range of months: where the money came from,
 * where it went, what was saved, and what the owner owns and owes. Read-only; the browser does no
 * classification. Rows count in the month of their transaction date — income marked as another
 * month's salary in that month (salaryMonth: income counts in the month it is for) — UZS only, dated
 * on or before {@link #date}, transfers between own wallets left out — see AnalyticsService for the
 * month and the class each row falls into. {@code walletChange} alone keeps every row on its date:
 * what the wallets did. Months are 'YYYY-MM'.
 */
@Getter @Builder
public class AnalyticsResponse {

    private Currency currency;
    /** The owner's local day the figures are as of. */
    private LocalDate date;
    private String from;
    private String to;
    /**
     * Earliest month with any counted row — counted in it, or (a salary marked as another month's)
     * reaching the wallets in it; null when there is none.
     */
    private String firstMonth;
    /** Months from firstMonth to the month of {@link #date} with at least one counted row. */
    private int monthsWithData;
    /** Settings' monthly income; null when unset. */
    private BigDecimal stableIncome;
    /** Rows counted inside the range (by their month, AnalyticsService.monthOf) but dated after {@link #date}: not counted anywhere else. */
    private int notYetCount;

    /** The whole range. */
    private Flow totals;
    /**
     * The same number of months immediately before {@link #from}; null when no counted row counts in
     * that range or reaches the wallets in it.
     */
    private Flow previous;
    /** One per month of the range, oldest first; months before firstMonth are left out. */
    private List<MonthFlow> months;

    private List<IncomeLine> income;
    private Everyday everyday;
    private List<BillLine> bills;
    private List<LoanLine> loanPayments;
    private List<SavingLine> savings;
    /** A picture of today; null unless {@link #to} is the month of {@link #date}. */
    private Position position;
    /** The position month by month as it was recorded, oldest first; may be empty. */
    private List<PositionMonth> positionHistory;

    /** The same object in totals, previous and every months[] entry. */
    @Getter
    public static class Flow {
        protected BigDecimal earned = BigDecimal.ZERO;
        protected BigDecimal earnedPay = BigDecimal.ZERO;
        protected BigDecimal earnedBonus = BigDecimal.ZERO;
        protected BigDecimal earnedOther = BigDecimal.ZERO;
        /** Everyday spending: itemised + not itemised − wallet-check corrections. */
        protected BigDecimal everyday = BigDecimal.ZERO;
        /** The wallet-check part of everyday: found missing − found extra. */
        protected BigDecimal everydayUnitemised = BigDecimal.ZERO;
        protected BigDecimal bills = BigDecimal.ZERO;
        protected BigDecimal loanPayments = BigDecimal.ZERO;
        /** everyday + bills + loanPayments. */
        protected BigDecimal out = BigDecimal.ZERO;
        protected BigDecimal saved = BigDecimal.ZERO;
        protected BigDecimal savedDonation = BigDecimal.ZERO;
        protected BigDecimal savedEmergency = BigDecimal.ZERO;
        protected BigDecimal savedInvestments = BigDecimal.ZERO;
        protected BigDecimal savedGoals = BigDecimal.ZERO;
        /** earned − out − saved. May be negative. */
        protected BigDecimal leftOver = BigDecimal.ZERO;
        protected BigDecimal borrowed = BigDecimal.ZERO;
        protected BigDecimal lent = BigDecimal.ZERO;
        protected BigDecimal returned = BigDecimal.ZERO;
        protected BigDecimal fromSavings = BigDecimal.ZERO;
        /**
         * Pay that reached the wallets in another month than the one it counts in (salaryMonth): +
         * what arrived in the period for a month outside it, − what counts in the period but arrived
         * outside it. Zero unless a salary or bonus crosses the period's edge — September's salary
         * paid on 2 October is −7,170,000 in September and +7,170,000 in October.
         */
        protected BigDecimal payForOtherMonths = BigDecimal.ZERO;
        /**
         * leftOver + borrowed − lent + returned + fromSavings + payForOtherMonths: the net change of
         * all UZS wallets in the period, every row on the day it happened.
         */
        protected BigDecimal walletChange = BigDecimal.ZERO;
        /** Number of counted rows (transfers excluded). */
        protected int count;

        public void addEarned(BigDecimal amount, String kind) {
            earned = earned.add(amount);
            switch (kind) {
                case "BONUS" -> earnedBonus = earnedBonus.add(amount);
                case "PAY" -> earnedPay = earnedPay.add(amount);
                default -> earnedOther = earnedOther.add(amount);
            }
        }

        public void addEverydayItemised(BigDecimal amount) {
            everyday = everyday.add(amount);
        }

        /** A wallet check's difference: positive = money gone, negative = money found. */
        public void addEverydayUnitemised(BigDecimal amount) {
            everyday = everyday.add(amount);
            everydayUnitemised = everydayUnitemised.add(amount);
        }

        public void addBill(BigDecimal amount) {
            bills = bills.add(amount);
        }

        public void addLoanPayment(BigDecimal amount) {
            loanPayments = loanPayments.add(amount);
        }

        public void addSaved(BigDecimal amount, String kind) {
            saved = saved.add(amount);
            switch (kind) {
                case "DONATION" -> savedDonation = savedDonation.add(amount);
                case "EMERGENCY" -> savedEmergency = savedEmergency.add(amount);
                case "GOAL" -> savedGoals = savedGoals.add(amount);
                default -> savedInvestments = savedInvestments.add(amount);
            }
        }

        public void addBorrowed(BigDecimal amount) {
            borrowed = borrowed.add(amount);
        }

        public void addLent(BigDecimal amount) {
            lent = lent.add(amount);
        }

        public void addReturned(BigDecimal amount) {
            returned = returned.add(amount);
        }

        public void addFromSavings(BigDecimal amount) {
            fromSavings = fromSavings.add(amount);
        }

        /** Pay crossing the period's edge: positive = arrived here for another month, negative = the reverse. */
        public void addPayForOtherMonths(BigDecimal amount) {
            payForOtherMonths = payForOtherMonths.add(amount);
        }

        public void counted() {
            count++;
        }

        /** Add another flow's figures to this one, field by field. */
        public void add(Flow o) {
            earned = earned.add(o.earned);
            earnedPay = earnedPay.add(o.earnedPay);
            earnedBonus = earnedBonus.add(o.earnedBonus);
            earnedOther = earnedOther.add(o.earnedOther);
            everyday = everyday.add(o.everyday);
            everydayUnitemised = everydayUnitemised.add(o.everydayUnitemised);
            bills = bills.add(o.bills);
            loanPayments = loanPayments.add(o.loanPayments);
            saved = saved.add(o.saved);
            savedDonation = savedDonation.add(o.savedDonation);
            savedEmergency = savedEmergency.add(o.savedEmergency);
            savedInvestments = savedInvestments.add(o.savedInvestments);
            savedGoals = savedGoals.add(o.savedGoals);
            borrowed = borrowed.add(o.borrowed);
            lent = lent.add(o.lent);
            returned = returned.add(o.returned);
            fromSavings = fromSavings.add(o.fromSavings);
            payForOtherMonths = payForOtherMonths.add(o.payForOtherMonths);
            count += o.count;
            settle();
        }

        /** Work out the derived figures — out, leftOver, walletChange — from the parts. */
        public void settle() {
            out = everyday.add(bills).add(loanPayments);
            leftOver = earned.subtract(out).subtract(saved);
            walletChange = leftOver.add(borrowed).subtract(lent).add(returned).add(fromSavings).add(payForOtherMonths);
        }
    }

    @Getter
    public static class MonthFlow extends Flow {
        /** YYYY-MM. */
        private final String month;
        /** The month ended before {@code date}. */
        private final boolean complete;
        /** The days counted in it: the whole month when complete, else the day-of-month of {@code date}. */
        private final int days;

        public MonthFlow(String month, boolean complete, int days) {
            this.month = month;
            this.complete = complete;
            this.days = days;
        }
    }

    /** Earned income by the row's own category, largest first. */
    @Getter @Builder
    public static class IncomeLine {
        /** Null for income recorded without a category (name "Uncategorized"). */
        private Long categoryId;
        private String name;
        private String nameUz;
        /** PAY (salary tree, bonus excluded) | BONUS | OTHER. */
        private String kind;
        private BigDecimal amount;
    }

    @Getter @Builder
    public static class Everyday {
        /** = totals.everyday. */
        private BigDecimal total;
        /** Σ months[].days. */
        private int days;
        /** total ÷ days, rounded to the so'm; null when days = 0. */
        private BigDecimal perDay;
        /** = totals.everydayUnitemised. */
        private BigDecimal unitemised;
        /** Itemised rows by top-level category, largest first. Σ amount + unitemised = total. */
        private List<EverydayCategory> categories;
        /** Only when from = to: one row per day from the 1st to min(month end, date); else []. */
        private List<Day> daily;
        /** Only when from = to and the month before has counted rows: every day of it; else null. */
        private List<PreviousDay> previousDaily;
        /** Only when from = to: the top 3 days by itemised everyday spending; else []. */
        private List<BiggestDay> biggestDays;
        /** The top 5 itemised rows of the range, largest first. */
        private List<BiggestRow> biggest;
    }

    @Getter @Builder
    public static class EverydayCategory {
        /** Null for rows with no category (name "Uncategorized"). */
        private Long categoryId;
        private String name;
        private String nameUz;
        private String color;
        private BigDecimal amount;
        private int count;
        /** The same category in {@code previous}; null when {@code previous} is null. */
        private BigDecimal previousAmount;
        /** Its sub-categories with itemised rows, largest first; [] when none. */
        private List<EverydayChild> children;
    }

    @Getter @Builder
    public static class EverydayChild {
        private Long categoryId;
        private String name;
        private String nameUz;
        private BigDecimal amount;
        private int count;
    }

    @Getter @Builder
    public static class Day {
        private LocalDate date;
        /** That day's everyday spending: itemised + not itemised − corrections. */
        private BigDecimal amount;
        /** The not-itemised part. */
        private BigDecimal unitemised;
        /** Running total from the 1st. */
        private BigDecimal cumulative;
    }

    @Getter @Builder
    public static class PreviousDay {
        private int day;
        private BigDecimal cumulative;
    }

    @Getter @Builder
    public static class BiggestDay {
        private LocalDate date;
        /** That day's itemised everyday spending. */
        private BigDecimal amount;
        /** The description of its largest itemised row. */
        private String topDescription;
    }

    @Getter @Builder
    public static class BiggestRow {
        private Long id;
        private LocalDate date;
        private String description;
        private BigDecimal amount;
        private Long categoryId;
        private String categoryName;
        private String categoryNameUz;
        private String color;
    }

    /** Bills paid through Pay, by bill, largest first. */
    @Getter @Builder
    public static class BillLine {
        /** The MonthlyPayment id. */
        private Long refId;
        /** The bill's current name; a bill since deleted: the row's description. */
        private String name;
        private BigDecimal paid;
        private int count;
    }

    /** Loan payments by the loan paid, largest first. */
    @Getter @Builder
    public static class LoanLine {
        /** BANK | LOAN | DEBT — the words /advisor's upcoming.kind uses. */
        private String kind;
        /** The BankLoan / LoanTaken / Debt id; null when the payment names none (or the bank loan cannot be told). */
        private Long refId;
        private String name;
        /** The loan is paid back as fast as possible: always true for a debt, false for a bank loan. */
        private boolean asap;
        private BigDecimal paid;
    }

    /** DONATION, EMERGENCY, INVESTMENTS always, in that order; then one GOAL per savings goal. */
    @Getter @Builder
    public static class SavingLine {
        /** DONATION | EMERGENCY | INVESTMENTS | GOAL. */
        private String kind;
        /** GOAL: the goal's investment id. */
        private Long refId;
        /** GOAL: the goal's name. */
        private String name;
        private BigDecimal saved;
        /**
         * Only when from = to: what that month asked — the month's target + what was carried into it
         * (a goal: its monthly payment). Null when nothing was asked, and for a multi-month range.
         */
        private BigDecimal asked;
    }

    @Getter @Builder
    public static class Position {
        private LocalDate asOf;
        /** = /advisor {@code have}. */
        private BigDecimal wallets;
        /** Emergency contributions + holdings flagged as the fund, at current value. */
        private BigDecimal emergencyFund;
        /** Holdings that are neither goals nor the emergency fund, at current value. */
        private BigDecimal investments;
        /** Savings goals, at current value. */
        private BigDecimal goals;
        /** The four above. */
        private BigDecimal own;
        /** Every loan not yet paid off, largest {@code left} first, unknown last. */
        private List<PositionLoan> loans;
        /** Σ of the known {@code left}. */
        private BigDecimal loansLeft;
        /** Money lent and not yet returned (= /advisor owedToYouTotal). */
        private BigDecimal owedToYou;
        /** own − loansLeft. */
        private BigDecimal net;
    }

    @Getter @Builder
    public static class PositionLoan {
        /** BANK | LOAN | DEBT. */
        private String kind;
        private Long refId;
        private String name;
        private boolean asap;
        private BigDecimal original;
        /** What is still owed; a bank loan: payments still to come × monthly when it has an end date, else null. */
        private BigDecimal left;
        /** The plan / the bank's monthly payment; null for an ASAP loan or a debt. */
        private BigDecimal monthly;
        /** YYYY-MM the last payment falls in; null when it cannot be known. */
        private String paidOffBy;
    }

    /** One recorded month of the position (PositionSnapshot). */
    @Getter @Builder
    public static class PositionMonth {
        /** YYYY-MM. */
        private String month;
        /** The day the figures were last taken on. */
        private LocalDate asOf;
        private BigDecimal wallets;
        private BigDecimal emergencyFund;
        private BigDecimal investments;
        private BigDecimal goals;
        private BigDecimal own;
        private BigDecimal loansLeft;
        private BigDecimal owedToYou;
        private BigDecimal net;
    }
}
