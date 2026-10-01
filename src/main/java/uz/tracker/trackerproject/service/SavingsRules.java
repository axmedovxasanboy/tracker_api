package uz.tracker.trackerproject.service;

import uz.tracker.trackerproject.entity.LevelRuleVersion;
import uz.tracker.trackerproject.entity.SituationPercents;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * The savings rules of every level, version by version (LEVELS-ALLOCATION-SPEC §1.2): which of the
 * seven situations a month is in, and the three percentages each level's version in force asks for
 * it. Pure — no repository — so the engine, the Levels page and the tests read one definition.
 *
 * <p>The situation is picked exactly as Level 1's scenario always was (ALLOCATION-EXPLAINED §4, §7):
 * no loan and no debt that counts → NO_DEBT; debt payments over 70% of the income → HEAVY_DEBT; a
 * bank loan and debt → BANK_AND_DEBTS; a bank loan alone or debt alone → its COMFORTABLE or TIGHT row
 * by what is left after bills and loans against the version's cutoff. Only the numbers come from the
 * version now — at every level.
 */
public final class SavingsRules {

    public static final String NO_DEBT = "NO_DEBT";
    public static final String BANK_LOAN_COMFORTABLE = "BANK_LOAN_COMFORTABLE";
    public static final String BANK_LOAN_TIGHT = "BANK_LOAN_TIGHT";
    public static final String DEBTS_COMFORTABLE = "DEBTS_COMFORTABLE";
    public static final String DEBTS_TIGHT = "DEBTS_TIGHT";
    public static final String BANK_AND_DEBTS = "BANK_AND_DEBTS";
    public static final String HEAVY_DEBT = "HEAVY_DEBT";

    /** The seven situations, in the order every screen lists them. */
    public static final List<String> SITUATIONS = List.of(NO_DEBT, BANK_LOAN_COMFORTABLE, BANK_LOAN_TIGHT,
            DEBTS_COMFORTABLE, DEBTS_TIGHT, BANK_AND_DEBTS, HEAVY_DEBT);

    /** Levels 1–4 come from income − bills; Level 5 is earned by pay. */
    public static final int TOP_LEVEL = 5;

    /** Level 1's split line before anyone changed it: 5,000,000 left after bills and loans. */
    public static final BigDecimal DEFAULT_CUTOFF = new BigDecimal("5000000");

    private static final BigDecimal DEBT_RATIO_THRESHOLD = new BigDecimal("0.70");

    /** One situation's three percentages; 0 = not asked. */
    public record Percents(BigDecimal donation, BigDecimal emergency, BigDecimal investments) {
        static Percents of(String donation, String emergency, String investments) {
            return new Percents(new BigDecimal(donation), new BigDecimal(emergency), new BigDecimal(investments));
        }

        public BigDecimal total() {
            return nz(donation).add(nz(emergency)).add(nz(investments));
        }

        /** As the engine's bucket strings: DONATION, EMERGENCY, INVESTMENTS, (stocks); 0 → null = not asked. */
        String[] strings() {
            return new String[]{str(donation), str(emergency), str(investments), null};
        }

        private static String str(BigDecimal v) {
            return v == null || v.signum() == 0 ? null : v.stripTrailingZeros().toPlainString();
        }
    }

    /** Level 1's table before it was stored: today's hard-coded numbers. Levels 2–5 start as copies. */
    public static final Map<String, Percents> LEVEL1_DEFAULT;

    static {
        Map<String, Percents> m = new LinkedHashMap<>();
        m.put(NO_DEBT, Percents.of("10", "5", "15"));
        m.put(BANK_LOAN_COMFORTABLE, Percents.of("7", "3", "10"));
        m.put(BANK_LOAN_TIGHT, Percents.of("5", "2", "8"));
        m.put(DEBTS_COMFORTABLE, Percents.of("7", "3", "10"));
        m.put(DEBTS_TIGHT, Percents.of("5", "2", "8"));
        m.put(BANK_AND_DEBTS, Percents.of("5", "0", "5"));
        m.put(HEAVY_DEBT, Percents.of("2", "0", "0"));
        LEVEL1_DEFAULT = Collections.unmodifiableMap(m);
    }

    /** One version of a level's rules, from a month on. */
    public record Version(int level, YearMonth from, BigDecimal cutoff, Map<String, Percents> rules) {
        public Percents percents(String situation) {
            Percents p = rules.get(situation);
            return p != null ? p : LEVEL1_DEFAULT.get(situation);
        }

        static Version of(LevelRuleVersion v) {
            Map<String, Percents> rules = new LinkedHashMap<>();
            for (String s : SITUATIONS) {
                SituationPercents p = v.getRules() == null ? null : v.getRules().get(s);
                rules.put(s, p == null ? LEVEL1_DEFAULT.get(s)
                        : new Percents(nz(p.getDonation()), nz(p.getEmergency()), nz(p.getInvestments())));
            }
            return new Version(v.getLevel(), YearMonth.from(v.getFromMonth()), v.getCutoff(), rules);
        }
    }

    /** Every level's versions, oldest first. */
    public static final class Book {
        private final Map<Integer, NavigableMap<YearMonth, Version>> levels;

        Book(Map<Integer, NavigableMap<YearMonth, Version>> levels) {
            this.levels = levels;
        }

        /** Level {@code level}'s version with the latest {@code from} ≤ {@code month}; before the first, the first. */
        public Version version(int level, YearMonth month) {
            NavigableMap<YearMonth, Version> versions = levels.get(level);
            if (versions == null || versions.isEmpty()) {
                return new Version(level, month, DEFAULT_CUTOFF, LEVEL1_DEFAULT);
            }
            Map.Entry<YearMonth, Version> e = versions.floorEntry(month);
            return e != null ? e.getValue() : versions.firstEntry().getValue();
        }

        /** Level {@code level}'s versions, oldest first. */
        public List<Version> versions(int level) {
            NavigableMap<YearMonth, Version> versions = levels.get(level);
            return versions == null ? List.of() : List.copyOf(versions.values());
        }
    }

    static Book book(Map<Integer, NavigableMap<YearMonth, Version>> levels) {
        return new Book(levels);
    }

    static NavigableMap<YearMonth, Version> single(Version v) {
        NavigableMap<YearMonth, Version> m = new TreeMap<>();
        m.put(v.from(), v);
        return m;
    }

    /**
     * The situation a month is in and what it leaves to split on.
     *
     * @param calcBase what is left after bills and loans — what the tight / comfortable split reads
     */
    public record Pick(String situation, BigDecimal calcBase, boolean hasLoan, boolean hasDebt) {}

    /**
     * Today's Level 1 scenario choice, for every level (unchanged — see the class comment).
     *
     * @param debt34Uzs  every ask of the month on borrowed money and debts — what the split base subtracts
     * @param ruleDebtUzs the personal debt that counts for the rule (the 10% small-monthly-loan rule applied)
     */
    static Pick pick(BigDecimal incomeUzs, BigDecimal mandatoryUzs, BigDecimal bankMonthlyUzs,
                     BigDecimal loanTakenUzs, BigDecimal debt34Uzs, BigDecimal ruleDebtUzs,
                     BigDecimal debtRatio, BigDecimal cutoffUzs) {
        BigDecimal leftBalance = nz(incomeUzs).subtract(nz(mandatoryUzs));
        BigDecimal loanInstallments = nz(bankMonthlyUzs).add(nz(loanTakenUzs));
        BigDecimal debt34 = nz(debt34Uzs);
        boolean hasLoan = loanInstallments.signum() > 0;
        boolean hasDebt = nz(ruleDebtUzs).signum() > 0;

        if (!hasLoan && !hasDebt) return new Pick(NO_DEBT, clampZero(leftBalance), false, false);
        boolean heavy = debtRatio != null && debtRatio.compareTo(DEBT_RATIO_THRESHOLD) > 0; // strict > 70%
        if (heavy) {
            return new Pick(HEAVY_DEBT, clampZero(leftBalance.subtract(loanInstallments).subtract(debt34)), hasLoan, hasDebt);
        }
        if (hasLoan && hasDebt) {
            return new Pick(BANK_AND_DEBTS, clampZero(leftBalance.subtract(loanInstallments).subtract(debt34)), true, true);
        }
        if (hasLoan) {
            // Every actual payment comes off: the installments, and any small MONTHLY plan beside them.
            BigDecimal base = clampZero(leftBalance.subtract(loanInstallments).subtract(debt34));
            return new Pick(base.compareTo(nz(cutoffUzs)) < 0 ? BANK_LOAN_TIGHT : BANK_LOAN_COMFORTABLE, base, true, false);
        }
        BigDecimal base = clampZero(leftBalance.subtract(debt34));
        return new Pick(base.compareTo(nz(cutoffUzs)) < 0 ? DEBTS_TIGHT : DEBTS_COMFORTABLE, base, false, true);
    }

    /**
     * The tier's {@code scenarioKey} for a situation on {@code level}: Level 1's keys as always
     * ("1.1", "1.2.1.tight", …, "1.3"), and the same shape on every other level ("3.2.1.tight").
     */
    public static String scenarioKey(int level, String situation) {
        String rest = switch (situation) {
            case NO_DEBT -> "1";
            case BANK_LOAN_TIGHT -> "2.1.tight";
            case BANK_LOAN_COMFORTABLE -> "2.1.comfortable";
            case DEBTS_TIGHT -> "2.2.tight";
            case DEBTS_COMFORTABLE -> "2.2.comfortable";
            case BANK_AND_DEBTS -> "2.3";
            case HEAVY_DEBT -> "3";
            default -> throw new IllegalArgumentException("Unknown situation: " + situation);
        };
        return level + "." + rest;
    }

    /** The situation behind a tier's {@code scenarioKey} — the inverse of {@link #scenarioKey}; null when it is none. */
    public static String situationOf(String scenarioKey) {
        if (scenarioKey == null) return null;
        int dot = scenarioKey.indexOf('.');
        if (dot < 0) return null;
        return switch (scenarioKey.substring(dot + 1)) {
            case "1" -> NO_DEBT;
            case "2.1.tight" -> BANK_LOAN_TIGHT;
            case "2.1.comfortable" -> BANK_LOAN_COMFORTABLE;
            case "2.2.tight" -> DEBTS_TIGHT;
            case "2.2.comfortable" -> DEBTS_COMFORTABLE;
            case "2.3" -> BANK_AND_DEBTS;
            case "3" -> HEAVY_DEBT;
            default -> null;
        };
    }

    /** Whether a situation is one of the four split at the cutoff. */
    public static boolean isSplit(String situation) {
        return BANK_LOAN_COMFORTABLE.equals(situation) || BANK_LOAN_TIGHT.equals(situation)
                || DEBTS_COMFORTABLE.equals(situation) || DEBTS_TIGHT.equals(situation);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal clampZero(BigDecimal v) {
        return v.signum() < 0 ? BigDecimal.ZERO : v;
    }

    private SavingsRules() { }
}
