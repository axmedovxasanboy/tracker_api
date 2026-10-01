package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.request.LevelRulesRequest;
import uz.tracker.trackerproject.dto.response.AllocationLedgerResponse;
import uz.tracker.trackerproject.dto.response.AllocationRulesViewResponse;
import uz.tracker.trackerproject.dto.response.LevelsResponse;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse;
import uz.tracker.trackerproject.dto.response.TierAllocation;
import uz.tracker.trackerproject.entity.LevelRuleVersion;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.exception.ResourceNotFoundException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * Each level's savings rules as versions "from which month" (LEVELS-ALLOCATION-SPEC §1.3, §3.1–3.3):
 * seeded from what the engine always read, so nothing past moves; a change holds from its month until
 * the level's next version; removing one falls back; and the split line is part of the version.
 * 7,000,000 a month from September 2026 — Level 1 — and today is 15 October.
 */
class LevelRulesTest {

    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final YearMonth OCT = YearMonth.of(2026, 10);
    private static final YearMonth NOV = YearMonth.of(2026, 11);
    private static final YearMonth DEC = YearMonth.of(2026, 12);
    private static final LocalDate TODAY = OCT.atDay(15);

    private LevelsFixture lf;

    @BeforeEach
    void setUp() {
        lf = new LevelsFixture();
        lf.incomeFrom(SEP, "7000000");
        lf.f.save(SEP.atDay(10), TransactionSubType.INVESTMENT, AllocationBucket.INVESTMENTS, "400000");
        lf.f.save(OCT.atDay(10), TransactionSubType.DONATION, AllocationBucket.DONATION, "300000");
    }

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    private static LevelRulesRequest change(String from, String cutoff, Object... rules) {
        LevelRulesRequest r = new LevelRulesRequest();
        r.setFrom(from);
        r.setCutoff(cutoff == null ? null : n(cutoff));
        Map<String, LevelRulesRequest.Percents> m = new LinkedHashMap<>();
        for (int i = 0; i < rules.length; i += 4) {
            LevelRulesRequest.Percents p = new LevelRulesRequest.Percents();
            p.setDonation(rules[i + 1] == null ? null : n((String) rules[i + 1]));
            p.setEmergency(rules[i + 2] == null ? null : n((String) rules[i + 2]));
            p.setInvestments(rules[i + 3] == null ? null : n((String) rules[i + 3]));
            m.put((String) rules[i], p);
        }
        r.setRules(m);
        return r;
    }

    /** A month's rule and targets as plain text, so two readings compare exactly. */
    private String tier(YearMonth month) {
        OverviewTierResponse t = lf.f.overview.getTierIgnoringSubscriptions(month, Currency.UZS, month.atDay(20));
        StringBuilder sb = new StringBuilder("L").append(t.getLevel()).append(" ").append(t.getSubLevel())
                .append(" ").append(t.getAllocation().getScenarioKey());
        for (TierAllocation.AllocationLine l : t.getAllocation().getLines()) {
            sb.append(" | ").append(l.getBucket()).append(" ").append(l.getMinPercent()).append("% ")
                    .append(l.getMinAmount() == null ? "-" : l.getMinAmount().stripTrailingZeros().toPlainString());
        }
        return sb.toString();
    }

    private String ledger(YearMonth month) {
        AllocationLedgerResponse r = lf.f.overview.getAllocationLedger(month, Currency.UZS);
        StringBuilder sb = new StringBuilder();
        for (AllocationLedgerResponse.BucketLedger b : r.getBuckets()) {
            sb.append(b.getBucket()).append(" ").append(plain(b.getRecommended())).append(" carried ")
                    .append(plain(b.getCarried())).append(" owed ").append(plain(b.getOutstanding())).append(" | ");
        }
        for (AllocationLedgerResponse.MonthBreakdown m : r.getMonths()) {
            sb.append(m.getMonth()).append(" L").append(m.getLevel()).append(" ").append(m.getSubLevel());
            m.getLines().forEach(l -> sb.append(" ").append(l.getBucket()).append(" ").append(plain(l.getRecommended())));
            sb.append(" || ");
        }
        return sb.toString();
    }

    private static String plain(BigDecimal v) {
        return v == null ? "-" : v.stripTrailingZeros().toPlainString();
    }

    // ── Seeding: nothing past moves ───────────────────────────────────────────

    @Test
    void theSeedingStoresWhatTheEngineAlwaysRead_once() {
        String before = tier(SEP) + " / " + tier(OCT) + " / " + ledger(OCT) + " / " + lf.f.overview.carriedInto(NOV);

        assertThat(lf.levels.seedVersions()).isEqualTo(5);
        assertThat(lf.levels.seedVersions()).isZero();                // a second boot changes nothing

        assertThat(lf.versions).extracting(LevelRuleVersion::getLevel, v -> YearMonth.from(v.getFromMonth()),
                        v -> plain(v.getCutoff()))
                .containsExactlyInAnyOrder(tuple(1, SEP, "5000000"), tuple(2, SEP, "5000000"), tuple(3, SEP, "5000000"),
                        tuple(4, SEP, "5000000"), tuple(5, SEP, "5000000"));
        assertThat(lf.versions.getFirst().getRules()).hasSize(7);
        assertThat(lf.versions.getFirst().getRules().get("BANK_LOAN_TIGHT").getInvestments()).isEqualByComparingTo("8");
        assertThat(lf.versions.getFirst().getRules().get("HEAVY_DEBT").getEmergency()).isEqualByComparingTo("0");
        // Every month's level, percentages, targets and carry: as they were.
        String after = tier(SEP) + " / " + tier(OCT) + " / " + ledger(OCT) + " / " + lf.f.overview.carriedInto(NOV);
        assertThat(after).isEqualTo(before);
        assertThat(tier(OCT)).isEqualTo("L1 1.1 1.1 | DONATION 10% 700000 | EMERGENCY 5% 350000 | INVESTMENTS 15% 1050000");
    }

    // ── A change from a month on ──────────────────────────────────────────────

    @Test
    void aChangeFromNovemberLeavesOctoberAlone() {
        lf.levels.seedVersions();
        String october = tier(OCT) + " / " + ledger(OCT);
        Map<String, BigDecimal> carriedIntoOctober = lf.f.overview.carriedInto(OCT);

        LevelsResponse r = lf.levels.saveRules("1", change("2026-11", null, "NO_DEBT", "20", "10", "20"), TODAY);

        assertThat(tier(OCT) + " / " + ledger(OCT)).isEqualTo(october);
        assertThat(lf.f.overview.carriedInto(OCT)).isEqualTo(carriedIntoOctober);
        assertThat(tier(NOV)).isEqualTo("L1 1.1 1.1 | DONATION 20% 1400000 | EMERGENCY 10% 700000 | INVESTMENTS 20% 1400000");
        // The page: two versions of Level 1, the one in force this month still September's.
        LevelsResponse.Level one = r.getLevels().getFirst();
        assertThat(one.getVersions()).extracting(LevelsResponse.Version::getFrom).containsExactly("2026-09", "2026-11");
        assertThat(one.getInForce()).isEqualTo("2026-09");
        assertThat(one.getVersions().get(1).getRules().get("NO_DEBT").getDonation()).isEqualByComparingTo("20");
        assertThat(one.getVersions().get(1).getRules().get("BANK_LOAN_TIGHT").getDonation()).isEqualByComparingTo("5");
    }

    /** A new version copies the one in force at its month; a later version is kept and inherits nothing. */
    @Test
    void aVersionStartsAsACopyOfTheOneInForce_andLaterVersionsDoNotInherit() {
        lf.levels.saveRules("1", change("2026-12", "3000000", "NO_DEBT", "12", "6", "18"), TODAY);
        lf.levels.saveRules("1", change("2026-11", null, "HEAVY_DEBT", "3", null, null), TODAY);

        LevelsResponse.Level one = lf.levels.levels(TODAY).getLevels().getFirst();
        assertThat(one.getVersions()).extracting(LevelsResponse.Version::getFrom, v -> plain(v.getCutoff()))
                .containsExactly(tuple("2026-09", "5000000"), tuple("2026-11", "5000000"), tuple("2026-12", "3000000"));
        LevelsResponse.Version nov = one.getVersions().get(1);
        assertThat(nov.getRules().get("HEAVY_DEBT")).extracting(p -> plain(p.getDonation()), p -> plain(p.getEmergency()),
                p -> plain(p.getInvestments())).containsExactly("3", "0", "0");   // one percentage sent: the others kept
        assertThat(nov.getRules().get("NO_DEBT").getDonation()).isEqualByComparingTo("10");
        LevelsResponse.Version dec = one.getVersions().get(2);
        assertThat(dec.getRules().get("HEAVY_DEBT").getDonation()).isEqualByComparingTo("2");   // did not inherit November's
        assertThat(dec.getRules().get("NO_DEBT").getDonation()).isEqualByComparingTo("12");
        // The same month again updates that version.
        lf.levels.saveRules("1", change("2026-11", null, "HEAVY_DEBT", "4", null, null), TODAY);
        assertThat(lf.levels.levels(TODAY).getLevels().getFirst().getVersions()).hasSize(3);
    }

    /** The split line is saved with the percentages, in the same request and from the same month. */
    @Test
    void theCutoffIsSavedWithThePercentages() {
        // A bank loan of 3,000,000 a month leaves 4,000,000: under 5,000,000 — the tight row.
        lf.f.bankLoan(1, "Xalq Banki", "Talim kredit", "36000000", "3000000", LocalDate.of(2026, 1, 1), null);
        assertThat(tier(OCT)).startsWith("L1 1.2 1.2.1.tight | DONATION 5%");

        lf.levels.saveRules("1", change("2026-10", "3000000",
                "BANK_LOAN_COMFORTABLE", "8", "3", "10", "BANK_LOAN_TIGHT", "6", "2", "8"), TODAY);

        LevelRuleVersion oct = lf.versions.stream().filter(v -> v.getLevel() == 1
                && YearMonth.from(v.getFromMonth()).equals(OCT)).findFirst().orElseThrow();
        assertThat(oct.getCutoff()).isEqualByComparingTo("3000000");
        assertThat(oct.getRules().get("BANK_LOAN_COMFORTABLE").getDonation()).isEqualByComparingTo("8");
        // 4,000,000 is now at or above the line: the "or more left" row, with its new numbers.
        assertThat(tier(OCT)).startsWith("L1 1.2 1.2.1.comfortable | DONATION 8%");
        assertThat(tier(SEP)).startsWith("L1 1.2 1.2.1.tight | DONATION 5%");                    // September kept its line
        assertThat(lf.levels.levels(TODAY).getSituation()).isEqualTo("BANK_LOAN_COMFORTABLE");
    }

    // ── Removing a version ────────────────────────────────────────────────────

    @Test
    void deletingAVersionFallsBack_andTheOnlyOneCannotGo() {
        lf.levels.saveRules("1", change("2026-11", null, "NO_DEBT", "20", "10", "20"), TODAY);
        assertThat(tier(NOV)).contains("DONATION 20%");

        LevelsResponse r = lf.levels.deleteRules("1", "2026-11", TODAY);

        assertThat(r.getLevels().getFirst().getVersions()).extracting(LevelsResponse.Version::getFrom).containsExactly("2026-09");
        assertThat(tier(NOV)).contains("DONATION 10%");
        assertThatThrownBy(() -> lf.levels.deleteRules("1", "2026-09", TODAY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("only rules version");
        assertThatThrownBy(() -> lf.levels.deleteRules("1", "2026-07", TODAY)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> lf.levels.deleteRules("1", "July", TODAY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("YYYY-MM");
        assertThatThrownBy(() -> lf.levels.deleteRules("6", "2026-09", TODAY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("level must be 1 to 5");
    }

    /**
     * A level's EARLIEST version cannot go even when there are others: the months before the next one
     * read it, and through "before the first: the first" they would take November's numbers.
     */
    @Test
    void theEarliestVersionCannotBeRemoved_soThePastNeverMoves() {
        lf.levels.saveRules("1", change("2026-11", null, "NO_DEBT", "20", "10", "20"), TODAY);
        String october = tier(OCT) + " / " + ledger(OCT);

        assertThatThrownBy(() -> lf.levels.deleteRules("1", "2026-09", TODAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This is Level 1's first rules version — the months before the next change read it. "
                        + "Change it instead.");

        assertThat(lf.versions).filteredOn(v -> v.getLevel() == 1).hasSize(2);
        assertThat(tier(OCT) + " / " + ledger(OCT)).isEqualTo(october);
    }

    // ── §3.2's 400s ───────────────────────────────────────────────────────────

    @Test
    void theBadRequestsAreRefused() {
        assertBad("0", change("2026-11", null), "level must be 1 to 5");
        assertBad("6", change("2026-11", null), "level must be 1 to 5");
        assertBad("two", change("2026-11", null), "level must be 1 to 5");
        assertBad("1", change("2026-11", null, "SOME_DEBT", "1", "1", "1"), "Unknown situation");
        assertBad("1", change("2026-11", null, "NO_DEBT", "101", "0", "0"), "0 to 100");
        assertBad("1", change("2026-11", null, "NO_DEBT", "-1", "0", "0"), "0 to 100");
        assertBad("1", change("2026-11", null, "NO_DEBT", "10.25", "0", "0"), "one decimal");
        assertBad("1", change("2026-11", null, "NO_DEBT", "60", "30", "20"), "at most 100%");
        assertBad("1", change("2026-11", null, "NO_DEBT", "90", null, null), "at most 100%");   // 90 + the 5 and 15 kept
        assertBad("1", change("2026-11", "-1"), "cutoff can't be negative");
        assertBad("1", change("2026-08", null), "2026-09 at the earliest");
        assertBad("1", change("2027-11", null), "up to 2027-10");
        assertBad("1", change("2026/11", null), "from must be YYYY-MM");
        assertThat(lf.versions).allMatch(v -> YearMonth.from(v.getFromMonth()).equals(SEP));   // nothing written but the seeding

        // The edges are fine; one decimal is fine; 100 together is fine.
        lf.levels.saveRules("1", change("2026-09", null, "NO_DEBT", "10.5", "4.5", "85"), TODAY);
        lf.levels.saveRules("1", change("2027-10", null), TODAY);
        lf.levels.saveRules("1", change(null, null, "HEAVY_DEBT", "0", "0", "0"), TODAY);      // absent from: this month
        assertThat(lf.levels.levels(TODAY).getLevels().getFirst().getVersions()).extracting(LevelsResponse.Version::getFrom)
                .containsExactly("2026-09", "2026-10", "2027-10");
    }

    private void assertBad(String level, LevelRulesRequest req, String message) {
        assertThatThrownBy(() -> lf.levels.saveRules(level, req, TODAY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }

    // ── The page ──────────────────────────────────────────────────────────────

    @Test
    void levelsListsEveryLevelWithItsBandAndVersions() {
        LevelsResponse r = lf.levels.levels(TODAY);

        assertThat(r.getMonth()).isEqualTo("2026-10");
        assertThat(r.getLevel()).isEqualTo(1);
        assertThat(r.getBaseLevel()).isEqualTo(1);
        assertThat(r.getLeftAfterBills()).isEqualByComparingTo("7000000");
        assertThat(r.getSituation()).isEqualTo("NO_DEBT");
        assertThat(r.getPercents()).extracting(p -> plain(p.getDonation()), p -> plain(p.getEmergency()),
                p -> plain(p.getInvestments())).containsExactly("10", "5", "15");
        assertThat(r.getLevel5Since()).isNull();
        assertThat(r.getRoad()).isNull();
        assertThat(r.getFirstMonth()).isEqualTo("2026-09");
        assertThat(r.getLevels()).extracting(LevelsResponse.Level::getLevel, l -> plain(l.getLeftFrom()), l -> plain(l.getLeftTo()),
                        LevelsResponse.Level::getInForce)
                .containsExactly(tuple(1, "0", "15000000", "2026-09"), tuple(2, "15000000", "30000000", "2026-09"),
                        tuple(3, "30000000", "45000000", "2026-09"), tuple(4, "45000000", "-", "2026-09"),
                        tuple(5, "-", "-", "2026-09"));
        assertThat(r.getLevels().get(4).getVersions().getFirst().getRules().keySet()).containsExactly("NO_DEBT",
                "BANK_LOAN_COMFORTABLE", "BANK_LOAN_TIGHT", "DEBTS_COMFORTABLE", "DEBTS_TIGHT", "BANK_AND_DEBTS", "HEAVY_DEBT");
    }

    /** The old Rules view stays, read-only, mapped from the store: five levels, nothing locked or editable. */
    @Test
    void theOldRulesViewIsMappedFromTheStore() {
        AllocationRulesViewResponse v = lf.f.overview.getAllocationRules();

        assertThat(v.getLevels()).hasSize(5);
        assertThat(v.getLevels()).allMatch(l -> !l.isLocked() && !l.isEditable());
        AllocationRulesViewResponse.LevelView one = v.getLevels().getFirst();
        assertThat(one.getSubLevels()).extracting(s -> s.getSubLevel(), s -> plain(s.getDonationPercent()),
                        s -> plain(s.getEmergencyPercent()), s -> plain(s.getInvestmentsPercent()))
                .containsExactly(tuple("1.1", "10", "5", "15"), tuple("1.2", "7", "3", "10"), tuple("1.3", "2", "-", "-"));
        assertThat(v.getLevels().get(3).getIncomeHigh()).isNull();
        assertThat(v.getLevels().get(4).getIncomeLow()).isNull();
    }
}
