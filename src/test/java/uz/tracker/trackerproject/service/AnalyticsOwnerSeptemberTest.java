package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.AdvisorResponse;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.Day;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.Everyday;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.EverydayCategory;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.Flow;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.MonthFlow;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.Position;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.SavingLine;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * A September built to the figures the live site showed on 30 September 2026 (ANALYTICS-SPEC §11.3,
 * "Check against the live data"): History "In 34 M · Out 22,3 M · Saved 4,5 M", Home "about 501,2 k
 * a day". The rows are made up to add up to those totals; the classification has to reproduce them.
 */
class AnalyticsOwnerSeptemberTest {

    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    private AnalyticsFixture f;

    private static LocalDate sep(int day) {
        return SEP.atDay(day);
    }

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    @BeforeEach
    void setUp() {
        f = new AnalyticsFixture();
        f.stableIncome("8000000", sep(1));

        // ── Categories: Salary → {Avans, Bonus}; Other income apart; three kinds of spending ──
        Category salary = f.category(10, "Salary", null);
        Category avans = f.category(11, "Avans", salary);
        Category bonus = f.category(12, "Bonus", salary);
        bonus.setBonusIncome(true);
        Category otherIncome = f.category(14, "Other Income", null);
        Category family = f.category(8, "Family & Support", null);
        family.setColor("#6366f1");
        Category parentsCat = f.category(31, "Parents", family);
        Category housing = f.category(4, "Housing", null);
        housing.setColor("#f59e0b");
        Category food = f.category(5, "Food", null);

        // ── Earned 34,034,000: pay 7,889,000 + bonus 26,045,000 + other 100,000 ──
        f.ledger.income(sep(7), "5889000", salary);
        f.ledger.income(sep(15), "2000000", avans);
        f.ledger.income(sep(18), "26045000", bonus);
        f.ledger.income(sep(18), "100000", otherIncome);

        // ── Bills 5,300,000, both through Pay ──
        f.bill(3, "Kvartira Arenda", "4200000");
        f.bill(4, "Noon Academy (Tabassum)", "1100000");
        f.ledger.billPaid(sep(8), "4200000", 3);
        f.ledger.billPaid(sep(9), "1100000", 4);

        // ── Borrowed 1,955,000 on the 14th and paid back on the 21st (loan payments 1,955,000) ──
        f.borrowed(7, "Uzum Bank", "1155000", "1155000", null, sep(14), null);
        f.borrowed(8, "Uzum Nasiya", "800000", "800000", null, sep(14), null);
        f.ledger.add(sep(14), TransactionType.INCOME, TransactionSubType.LOAN_RECEIVED, "1155000");
        f.ledger.add(sep(14), TransactionType.INCOME, TransactionSubType.LOAN_RECEIVED, "800000");
        f.ledger.add(sep(21), TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT, "1155000").setRepaidLoanTakenId(7L);
        f.ledger.add(sep(21), TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT, "800000").setRepaidLoanTakenId(8L);

        // ── Lent 1,000,000; 700,000 of it came back ──
        f.lent(2, "Do'stim", "1000000", "700000");
        f.ledger.add(sep(5), TransactionType.EXPENSE, TransactionSubType.LOAN_GIVEN, "1000000");
        f.ledger.add(sep(25), TransactionType.INCOME, TransactionSubType.LOAN_RETURNED_TO_ME, "700000");

        // ── Saved 4,520,000: 800,000 + 720,000 + 3,000,000 + 0 ──
        f.save(sep(10), TransactionSubType.DONATION, AllocationBucket.DONATION, "800000");
        f.save(sep(10), TransactionSubType.EMERGENCY_CONTRIBUTION, AllocationBucket.EMERGENCY, "720000");
        f.save(sep(19), TransactionSubType.INVESTMENT, AllocationBucket.INVESTMENTS, "3000000");

        // ── Everyday, itemised: 10,988,000 ──
        f.spend(sep(1), "200000", food, "Bozor");
        f.spend(sep(3), "1350000", family, "Ukamga");
        f.spend(sep(6), "1388000", food, "Korzinka");
        f.spend(sep(10), "600000", parentsCat, "Onamga");
        f.spend(sep(12), "1000000", family, "To'yana");
        f.spend(sep(17), "500000", parentsCat, "Otamga");
        f.spend(sep(19), "2100000", housing, "Perfectum WiFi");
        f.spend(sep(19), "350000", housing, "Lampa");
        f.spend(sep(20), "2000000", food, "Restoran");
        f.spend(sep(24), "500000", parentsCat, "Onamga");
        f.spend(sep(27), "500000", null, "Taksi");
        f.spend(sep(28), "500000", food, "Bozor");

        // ── Everyday, not itemised: 4,168,000 found missing, 120,000 put right → 4,048,000 ──
        f.foundMissing(sep(16), "2168000");
        f.foundMissing(sep(22), "2000000");
        f.foundExtra(sep(23), "120000");

        // ── A cash withdrawal: card → cash, a transfer pair that must not be seen at all ──
        Transaction out = f.ledger.add(sep(15), TransactionType.EXPENSE, TransactionSubType.TRANSFER_OUT, "1000000");
        Transaction in = f.ledger.add(sep(15), TransactionType.INCOME, TransactionSubType.TRANSFER_IN, "1000000");
        out.setTransferPairId(out.getId());
        in.setTransferPairId(out.getId());

        // ── Own and owe ──
        f.wallet("CARD", Currency.UZS, "12614000");
        f.wallet("CASH", Currency.UZS, "1000000");
        f.wallet("CASH", Currency.USD, "100");                    // a dormant foreign pot: in no total
        f.emergencyContribution(sep(10), "720000");
        f.holding(21, "Omonat", "650000", "693254", LocalDate.of(2026, 3, 1)).setEmergencyFund(true);
        f.holding(22, "Tabiiy gaz aksiyalari", "19000000", "19615974", LocalDate.of(2026, 2, 1));
        f.borrowed(5, "Ota-onam (parents)", "50000000", "0", "500000", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 10, 1));
        f.bankLoan(1, "Xalq Banki", "Talim kredit", "26000000", "400000", LocalDate.of(2026, 1, 1), null);
        f.bankMark(1, sep(1), "400000");                          // September's installment: marked, no row
    }

    @Test
    void theTotalsAreTheOnesTheLiveSiteShowed() {
        AnalyticsResponse r = f.analytics.analytics(SEP, SEP, TODAY);
        Flow t = r.getTotals();

        assertThat(t.getEarned()).isEqualByComparingTo("34034000");
        assertThat(t.getEarnedPay()).isEqualByComparingTo("7889000");
        assertThat(t.getEarnedBonus()).isEqualByComparingTo("26045000");
        assertThat(t.getEarnedOther()).isEqualByComparingTo("100000");
        assertThat(t.getEveryday()).isEqualByComparingTo("15036000");
        assertThat(t.getEverydayUnitemised()).isEqualByComparingTo("4048000");
        assertThat(t.getBills()).isEqualByComparingTo("5300000");
        assertThat(t.getLoanPayments()).isEqualByComparingTo("1955000");
        assertThat(t.getOut()).isEqualByComparingTo("22291000");
        assertThat(t.getSaved()).isEqualByComparingTo("4520000");
        assertThat(t.getSavedDonation()).isEqualByComparingTo("800000");
        assertThat(t.getSavedEmergency()).isEqualByComparingTo("720000");
        assertThat(t.getSavedInvestments()).isEqualByComparingTo("3000000");
        assertThat(t.getSavedGoals()).isEqualByComparingTo("0");
        assertThat(t.getLeftOver()).isEqualByComparingTo("7223000");
        assertThat(t.getBorrowed()).isEqualByComparingTo("1955000");
        assertThat(t.getLent()).isEqualByComparingTo("1000000");
        assertThat(t.getReturned()).isEqualByComparingTo("700000");
        assertThat(t.getFromSavings()).isEqualByComparingTo("0");
        assertThat(t.getWalletChange()).isEqualByComparingTo("8878000");
        assertThat(t.getCount()).isEqualTo(30);                   // the two halves of the transfer are not rows here
    }

    @Test
    void theEnvelopeDescribesOneOpenMonth() {
        AnalyticsResponse r = f.analytics.analytics(SEP, SEP, TODAY);

        assertThat(r.getCurrency()).isEqualTo(Currency.UZS);
        assertThat(r.getDate()).isEqualTo(TODAY);
        assertThat(r.getFrom()).isEqualTo("2026-09");
        assertThat(r.getTo()).isEqualTo("2026-09");
        assertThat(r.getFirstMonth()).isEqualTo("2026-09");
        assertThat(r.getMonthsWithData()).isEqualTo(1);
        assertThat(r.getStableIncome()).isEqualByComparingTo("8000000");
        assertThat(r.getNotYetCount()).isZero();
        assertThat(r.getPrevious()).isNull();

        assertThat(r.getMonths()).hasSize(1);
        MonthFlow m = r.getMonths().get(0);
        assertThat(m.getMonth()).isEqualTo("2026-09");
        assertThat(m.isComplete()).isFalse();
        assertThat(m.getDays()).isEqualTo(30);
        assertThat(m.getEarned()).isEqualByComparingTo("34034000");
        assertThat(m.getWalletChange()).isEqualByComparingTo("8878000");
    }

    @Test
    void incomeIsListedByCategoryLargestFirst() {
        AnalyticsResponse r = f.analytics.analytics(SEP, SEP, TODAY);

        assertThat(r.getIncome()).extracting("categoryId", "name", "kind", "amount").containsExactly(
                tuple(12L, "Bonus", "BONUS", n("26045000")),
                tuple(10L, "Salary", "PAY", n("5889000")),
                tuple(11L, "Avans", "PAY", n("2000000")),
                tuple(14L, "Other Income", "OTHER", n("100000")));
    }

    @Test
    void everydayIsHomesPaceAndItsCategoriesAddUp() {
        Everyday e = f.analytics.analytics(SEP, SEP, TODAY).getEveryday();

        assertThat(e.getTotal()).isEqualByComparingTo("15036000");
        assertThat(e.getDays()).isEqualTo(30);
        assertThat(e.getPerDay()).isEqualByComparingTo("501200");             // Home: "about 501,2 k a day"
        assertThat(e.getUnitemised()).isEqualByComparingTo("4048000");

        assertThat(e.getCategories()).extracting("categoryId", "name", "color", "amount", "count", "previousAmount")
                .containsExactly(
                        tuple(5L, "Food", null, n("4088000"), 4, null),
                        tuple(8L, "Family & Support", "#6366f1", n("3950000"), 5, null),
                        tuple(4L, "Housing", "#f59e0b", n("2450000"), 2, null),
                        tuple(null, "Uncategorized", null, n("500000"), 1, null));
        EverydayCategory family = e.getCategories().get(1);
        assertThat(family.getChildren()).extracting("categoryId", "name", "amount", "count")
                .containsExactly(tuple(31L, "Parents", n("1600000"), 3));
        assertThat(e.getCategories().get(0).getChildren()).isEmpty();

        BigDecimal categories = e.getCategories().stream().map(EverydayCategory::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(categories.add(e.getUnitemised())).isEqualByComparingTo(e.getTotal());
    }

    @Test
    void everyDayOfTheMonthIsThereWithARunningTotal() {
        Everyday e = f.analytics.analytics(SEP, SEP, TODAY).getEveryday();
        List<Day> daily = e.getDaily();

        assertThat(daily).hasSize(30);
        assertThat(daily.get(0).getDate()).isEqualTo(sep(1));
        assertThat(daily.get(0).getAmount()).isEqualByComparingTo("200000");
        assertThat(daily.get(0).getUnitemised()).isEqualByComparingTo("0");
        assertThat(daily.get(0).getCumulative()).isEqualByComparingTo("200000");
        assertThat(daily.get(1).getAmount()).isEqualByComparingTo("0");       // a day with nothing is still a row
        assertThat(daily.get(1).getCumulative()).isEqualByComparingTo("200000");
        assertThat(daily.get(15).getAmount()).isEqualByComparingTo("2168000");
        assertThat(daily.get(15).getUnitemised()).isEqualByComparingTo("2168000");
        assertThat(daily.get(22).getAmount()).isEqualByComparingTo("-120000"); // the day the correction was booked
        assertThat(daily.get(22).getUnitemised()).isEqualByComparingTo("-120000");
        assertThat(daily.get(29).getDate()).isEqualTo(sep(30));
        assertThat(daily.get(29).getCumulative()).isEqualByComparingTo("15036000");

        BigDecimal running = BigDecimal.ZERO;
        for (Day d : daily) {
            running = running.add(d.getAmount());
            assertThat(d.getCumulative()).isEqualByComparingTo(running);
        }
        assertThat(e.getPreviousDaily()).isNull();                            // August has no rows at all
    }

    @Test
    void theBiggestDaysAndRowsCountItemisedSpendingOnly() {
        Everyday e = f.analytics.analytics(SEP, SEP, TODAY).getEveryday();

        // The 16th (2,168,000 found by a wallet check) is not a "biggest day": nobody knows what it went on.
        assertThat(e.getBiggestDays()).extracting("date", "amount", "topDescription").containsExactly(
                tuple(sep(19), n("2450000"), "Perfectum WiFi"),
                tuple(sep(20), n("2000000"), "Restoran"),
                tuple(sep(6), n("1388000"), "Korzinka"));
        assertThat(e.getBiggest()).extracting("date", "description", "amount", "categoryId", "categoryName", "color")
                .containsExactly(
                        tuple(sep(19), "Perfectum WiFi", n("2100000"), 4L, "Housing", "#f59e0b"),
                        tuple(sep(20), "Restoran", n("2000000"), 5L, "Food", null),
                        tuple(sep(6), "Korzinka", n("1388000"), 5L, "Food", null),
                        tuple(sep(3), "Ukamga", n("1350000"), 8L, "Family & Support", "#6366f1"),
                        tuple(sep(12), "To'yana", n("1000000"), 8L, "Family & Support", "#6366f1"));
    }

    @Test
    void billsAndLoanPaymentsAreListedByWhatWasPaid() {
        AnalyticsResponse r = f.analytics.analytics(SEP, SEP, TODAY);

        assertThat(r.getBills()).extracting("refId", "name", "paid", "count").containsExactly(
                tuple(3L, "Kvartira Arenda", n("4200000"), 1),
                tuple(4L, "Noon Academy (Tabassum)", n("1100000"), 1));
        assertThat(r.getLoanPayments()).extracting("kind", "refId", "name", "asap", "paid").containsExactly(
                tuple("LOAN", 7L, "Uzum Bank", true, n("1155000")),
                tuple("LOAN", 8L, "Uzum Nasiya", true, n("800000")));
    }

    /**
     * What the month asked is read from the advisor, not worked out again: each bucket's target plus
     * what was carried into it. Here: a bank loan, no personal debt counted, 2,300,000 left after the
     * bills and the installment → 5 / 2 / 8 % of the base 34,045,000 (8,000,000 + the bonus).
     */
    @Test
    void savingsShowWhatWasPutByAndWhatTheAdvisorAsked() {
        AnalyticsResponse r = f.analytics.analytics(SEP, SEP, TODAY);
        AdvisorResponse home = f.advisor.advise(TODAY);

        assertThat(r.getSavings()).extracting("kind", "refId", "name", "saved").containsExactly(
                tuple("DONATION", null, null, n("800000")),
                tuple("EMERGENCY", null, null, n("720000")),
                tuple("INVESTMENTS", null, null, n("3000000")));
        assertThat(r.getSavings().get(0).getAsked()).isEqualByComparingTo("1702250");
        assertThat(r.getSavings().get(1).getAsked()).isEqualByComparingTo("680900");
        assertThat(r.getSavings().get(2).getAsked()).isEqualByComparingTo("2723600");
        assertThat(home.getSavingsThisMonth()).hasSize(3);
        for (AdvisorResponse.SavingsRow row : home.getSavingsThisMonth()) {
            SavingLine line = r.getSavings().stream().filter(l -> l.getKind().equals(row.getBucket())).findFirst().orElseThrow();
            assertThat(line.getAsked()).isEqualByComparingTo(row.getTarget().add(row.getCarried()));
        }
    }

    @Test
    void thePositionIsTodaysPictureAndAgreesWithTheAdvisor() {
        AnalyticsResponse r = f.analytics.analytics(SEP, SEP, TODAY);
        Position p = r.getPosition();
        AdvisorResponse home = f.advisor.advise(TODAY);

        assertThat(p.getAsOf()).isEqualTo(TODAY);
        assertThat(p.getWallets()).isEqualByComparingTo("13614000");
        assertThat(p.getWallets()).isEqualByComparingTo(home.getHave());
        assertThat(p.getEmergencyFund()).isEqualByComparingTo("1413254");     // 720,000 put by + the 693,254 deposit
        assertThat(p.getInvestments()).isEqualByComparingTo("19615974");
        assertThat(p.getGoals()).isEqualByComparingTo("0");
        assertThat(p.getOwn()).isEqualByComparingTo("34643228");
        assertThat(p.getLoansLeft()).isEqualByComparingTo("50000000");
        assertThat(p.getOwedToYou()).isEqualByComparingTo("300000");
        assertThat(p.getOwedToYou()).isEqualByComparingTo(home.getOwedToYouTotal());
        assertThat(p.getNet()).isEqualByComparingTo("-15356772");

        // The Uzum loans are paid off and gone; the bank loan has no end date, so what is left is unknown — last.
        assertThat(p.getLoans()).extracting("kind", "refId", "name", "asap", "original", "left", "monthly", "paidOffBy")
                .containsExactly(
                        tuple("LOAN", 5L, "Ota-onam (parents)", false, n("50000000"), n("50000000"), n("500000"), "2035-01"),
                        tuple("BANK", 1L, "Xalq Banki · Talim kredit", false, n("26000000"), null, n("400000"), null));
    }
}
