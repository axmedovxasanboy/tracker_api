package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.AllocationLedgerResponse;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.Day;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.Everyday;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.EverydayCategory;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.Flow;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.MonthFlow;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.PreviousDay;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse.SavingLine;
import uz.tracker.trackerproject.entity.BankLoan;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.PositionSnapshot;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.service.AnalyticsService.FlowClass;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** GET /api/v1/analytics: which rows count, the class each falls into, the range, and the parts built on them. */
class AnalyticsServiceTest {

    private static final YearMonth JUL = YearMonth.of(2026, 7);
    private static final YearMonth AUG = YearMonth.of(2026, 8);
    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 20);

    private AnalyticsFixture f;

    @BeforeEach
    void setUp() {
        f = new AnalyticsFixture();
    }

    private static LocalDate sep(int day) {
        return SEP.atDay(day);
    }

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    private Transaction row(LocalDate date, TransactionType type, TransactionSubType subType, String amount) {
        return f.ledger.add(date, type, subType, amount);
    }

    private AnalyticsResponse september() {
        return f.analytics.analytics(SEP, SEP, TODAY);
    }

    // ── §11.3: one test per class ─────────────────────────────────────────────

    @Test
    void class1_moneyBorrowedIsNotIncome() {
        Transaction t = row(sep(3), TransactionType.INCOME, TransactionSubType.LOAN_RECEIVED, "1000000");

        assertThat(AnalyticsService.classify(t)).isEqualTo(FlowClass.BORROWED);
        Flow flow = september().getTotals();
        assertThat(flow.getBorrowed()).isEqualByComparingTo("1000000");
        assertThat(flow.getEarned()).isEqualByComparingTo("0");
        assertThat(flow.getWalletChange()).isEqualByComparingTo("1000000");
    }

    @Test
    void class2_moneyLentComingBackIsNotIncome() {
        Transaction t = row(sep(3), TransactionType.INCOME, TransactionSubType.LOAN_RETURNED_TO_ME, "700000");

        assertThat(AnalyticsService.classify(t)).isEqualTo(FlowClass.RETURNED);
        Flow flow = september().getTotals();
        assertThat(flow.getReturned()).isEqualByComparingTo("700000");
        assertThat(flow.getEarned()).isEqualByComparingTo("0");
    }

    @Test
    void class3_moneyTakenOutOfSavingsIsNotIncome() {
        Transaction t = row(sep(3), TransactionType.INCOME, TransactionSubType.INVESTMENT_WITHDRAWAL, "500000");

        assertThat(AnalyticsService.classify(t)).isEqualTo(FlowClass.FROM_SAVINGS);
        Flow flow = september().getTotals();
        assertThat(flow.getFromSavings()).isEqualByComparingTo("500000");
        assertThat(flow.getEarned()).isEqualByComparingTo("0");
        assertThat(flow.getSaved()).isEqualByComparingTo("0");
    }

    @Test
    void class4_aWalletCheckThatFoundMoreSubtractsFromEverydaySpending() {
        f.spend(sep(2), "900000", null, "Bozor");
        f.foundMissing(sep(5), "400000");
        Transaction surplus = f.foundExtra(sep(6), "150000");

        assertThat(AnalyticsService.classify(surplus)).isEqualTo(FlowClass.CORRECTION);
        Flow flow = september().getTotals();
        assertThat(flow.getEverydayUnitemised()).isEqualByComparingTo("250000");   // 400,000 − 150,000
        assertThat(flow.getEveryday()).isEqualByComparingTo("1150000");            // 900,000 + 250,000
        assertThat(flow.getOut()).isEqualByComparingTo("1150000");
        assertThat(flow.getEarned()).isEqualByComparingTo("0");                    // never income
        assertThat(flow.getCount()).isEqualTo(3);
    }

    @Test
    void class5_otherIncomeIsEarned_asPayBonusOrOther() {
        Category salary = f.category(10, "Salary", null);
        Category avans = f.category(11, "Avans", salary);
        Category bonus = f.category(12, "Bonus", salary);
        bonus.setBonusIncome(true);
        Category quarterly = f.category(13, "Quarterly", bonus);                   // under a flagged parent
        Category freelance = f.category(14, "Freelance", null);
        f.ledger.income(sep(1), "5000000", salary);
        f.ledger.income(sep(2), "2000000", avans);
        f.ledger.income(sep(3), "3000000", bonus);
        f.ledger.income(sep(4), "1000000", quarterly);
        f.ledger.income(sep(5), "400000", freelance);
        Transaction noSubType = row(sep(6), TransactionType.INCOME, null, "60000");  // an old row: no sub-type, no category

        assertThat(AnalyticsService.classify(noSubType)).isEqualTo(FlowClass.EARNED);
        Set<Long> tree = f.overview.salaryTree();
        assertThat(AnalyticsService.incomeKind(salary, tree)).isEqualTo("PAY");
        assertThat(AnalyticsService.incomeKind(avans, tree)).isEqualTo("PAY");
        assertThat(AnalyticsService.incomeKind(bonus, tree)).isEqualTo("BONUS");
        assertThat(AnalyticsService.incomeKind(quarterly, tree)).isEqualTo("BONUS");
        assertThat(AnalyticsService.incomeKind(freelance, tree)).isEqualTo("OTHER");
        assertThat(AnalyticsService.incomeKind(null, tree)).isEqualTo("OTHER");

        AnalyticsResponse r = september();
        Flow flow = r.getTotals();
        assertThat(flow.getEarned()).isEqualByComparingTo("11460000");
        assertThat(flow.getEarnedPay()).isEqualByComparingTo("7000000");
        assertThat(flow.getEarnedBonus()).isEqualByComparingTo("4000000");
        assertThat(flow.getEarnedOther()).isEqualByComparingTo("460000");
        assertThat(r.getIncome()).extracting("categoryId", "name", "kind", "amount").containsExactly(
                tuple(10L, "Salary", "PAY", n("5000000")),
                tuple(12L, "Bonus", "BONUS", n("3000000")),
                tuple(11L, "Avans", "PAY", n("2000000")),
                tuple(13L, "Quarterly", "BONUS", n("1000000")),
                tuple(14L, "Freelance", "OTHER", n("400000")),
                tuple(null, "Uncategorized", "OTHER", n("60000")));
    }

    // ── Income counts in the month it is for (salaryMonth, 2026-10-02) ───────────

    /**
     * August's salary paid on 3 September is August's earned, not September's — still earned pay (its
     * class does not change), and the months, the income lines, the range before, firstMonth and
     * monthsWithData all see it in August.
     */
    @Test
    void class5_aSalaryMarkedForAnotherMonthCountsInThatMonth() {
        Category salary = f.category(10, "Salary", null);
        Transaction late = f.ledger.income(sep(3), "7000000", salary);
        late.setSalaryMonth(AUG.atDay(1));
        f.spend(sep(4), "100000", null, "Bozor");

        assertThat(AnalyticsService.classify(late)).isEqualTo(FlowClass.EARNED);
        assertThat(AnalyticsService.monthOf(late)).isEqualTo(AUG);

        AnalyticsResponse september = september();
        assertThat(september.getTotals().getEarned()).isEqualByComparingTo("0");
        assertThat(september.getIncome()).isEmpty();
        assertThat(september.getTotals().getCount()).isEqualTo(1);
        // The wallets got it on 3 September: September's wallet change says so, August's does not.
        assertThat(september.getTotals().getPayForOtherMonths()).isEqualByComparingTo("7000000");
        assertThat(september.getTotals().getWalletChange()).isEqualByComparingTo("6900000");   // − 100,000 spent
        assertThat(september.getPrevious().getEarnedPay()).isEqualByComparingTo("7000000");   // August
        assertThat(september.getPrevious().getPayForOtherMonths()).isEqualByComparingTo("-7000000");
        assertThat(september.getPrevious().getWalletChange()).isEqualByComparingTo("0");
        assertThat(september.getFirstMonth()).isEqualTo("2026-08");
        assertThat(september.getMonthsWithData()).isEqualTo(2);
        assertThat(september.getEveryday().getDaily()).hasSize(20);                            // untouched

        AnalyticsResponse august = f.analytics.analytics(AUG, AUG, TODAY);
        assertThat(august.getTotals().getEarned()).isEqualByComparingTo("7000000");
        assertThat(august.getTotals().getEarnedPay()).isEqualByComparingTo("7000000");
        assertThat(august.getIncome()).extracting("categoryId", "kind", "amount")
                .containsExactly(tuple(10L, "PAY", n("7000000")));
        assertThat(august.getMonths()).extracting(MonthFlow::getMonth).containsExactly("2026-08");
        assertThat(august.getEveryday().getDaily()).extracting(Day::getAmount)
                .allSatisfy(a -> assertThat(a).isEqualByComparingTo("0"));                     // no day of it in August

        AnalyticsResponse both = f.analytics.analytics(AUG, SEP, TODAY);
        assertThat(both.getMonths()).extracting(MonthFlow::getMonth, m -> m.getEarned().intValueExact(),
                        m -> m.getWalletChange().intValueExact())
                .containsExactly(tuple("2026-08", 7_000_000, 0), tuple("2026-09", 0, 6_900_000));
        assertThat(both.getTotals().getEarned()).isEqualByComparingTo("7000000");              // once
        assertThat(both.getTotals().getPayForOtherMonths()).isEqualByComparingTo("0");         // inside the range
        assertThat(both.getTotals().getWalletChange()).isEqualByComparingTo("6900000");
        both.getMonths().forEach(AnalyticsServiceTest::assertIdentities);
        assertIdentities(both.getTotals());
        assertIdentities(september.getPrevious());
    }

    /** Only income moves: a salaryMonth on anything else (the write path drops it) is not read. */
    @Test
    void onlyIncomeMovesToItsSalaryMonth() {
        Transaction spend = f.spend(sep(3), "100000", null, "Bozor");
        spend.setSalaryMonth(AUG.atDay(1));
        Transaction paid = f.ledger.income(sep(3), "100000", null);

        assertThat(AnalyticsService.monthOf(spend)).isEqualTo(SEP);
        assertThat(AnalyticsService.monthOf(paid)).isEqualTo(SEP);
        assertThat(september().getTotals().getEveryday()).isEqualByComparingTo("100000");
    }

    /** November's salary paid on 30 October is November's: October never sees it. */
    @Test
    void nextMonthsSalaryPaidEarlyCountsInNextMonth() {
        YearMonth oct = YearMonth.of(2026, 10);
        YearMonth nov = YearMonth.of(2026, 11);
        Category salary = f.category(10, "Salary", null);
        f.ledger.income(oct.atDay(30), "7000000", salary).setSalaryMonth(nov.atDay(1));
        f.spend(oct.atDay(30), "50000", null, "Bozor");

        AnalyticsResponse october = f.analytics.analytics(oct, oct, oct.atDay(31));
        assertThat(october.getTotals().getEarned()).isEqualByComparingTo("0");
        assertThat(october.getIncome()).isEmpty();
        assertThat(october.getTotals().getPayForOtherMonths()).isEqualByComparingTo("7000000");  // came on the 30th
        assertThat(october.getTotals().getWalletChange()).isEqualByComparingTo("6950000");
        assertThat(october.getNotYetCount()).isZero();
        assertThat(october.getMonthsWithData()).isEqualTo(1);              // November has not begun
        assertThat(october.getMonths()).extracting(MonthFlow::getMonth).containsExactly("2026-10");

        AnalyticsResponse november = f.analytics.analytics(nov, nov, nov.atDay(5));
        assertThat(november.getTotals().getEarned()).isEqualByComparingTo("7000000");
        assertThat(november.getTotals().getEarnedPay()).isEqualByComparingTo("7000000");
        assertThat(november.getMonthsWithData()).isEqualTo(2);
        assertThat(november.getPrevious().getEarned()).isEqualByComparingTo("0");        // October
        assertThat(november.getPrevious().getEveryday()).isEqualByComparingTo("50000");
        assertThat(november.getPrevious().getWalletChange()).isEqualByComparingTo("6950000");
        assertThat(november.getTotals().getPayForOtherMonths()).isEqualByComparingTo("-7000000");
        assertThat(november.getTotals().getWalletChange()).isEqualByComparingTo("0");
        assertThat(november.getEveryday().getDaily()).hasSize(5);
    }

    /**
     * Being dated after the owner's day keeps a row out, salary or not: it is only in notYetCount —
     * of the range it will count in once its day comes.
     */
    @Test
    void aSalaryDatedAfterTheOwnersDayIsNotYet_inTheMonthItIsFor() {
        YearMonth oct = YearMonth.of(2026, 10);
        LocalDate oct20 = oct.atDay(20);
        Category salary = f.category(10, "Salary", null);
        f.ledger.income(oct.atDay(25), "7000000", salary).setSalaryMonth(SEP.atDay(1));
        f.ledger.income(oct.atDay(5), "1000000", salary);
        f.spend(oct.atDay(21), "300000", null, "Ertaga");

        AnalyticsResponse september = f.analytics.analytics(SEP, SEP, oct20);
        assertThat(september.getTotals().getEarned()).isEqualByComparingTo("0");
        assertThat(september.getNotYetCount()).isEqualTo(1);
        AnalyticsResponse october = f.analytics.analytics(oct, oct, oct20);
        assertThat(october.getTotals().getEarned()).isEqualByComparingTo("1000000");
        assertThat(october.getTotals().getEveryday()).isEqualByComparingTo("0");
        assertThat(october.getNotYetCount()).isEqualTo(1);                  // the spending only
        assertThat(f.analytics.analytics(SEP, oct, oct20).getNotYetCount()).isEqualTo(2);

        // Its day has come: September's.
        AnalyticsResponse later = f.analytics.analytics(SEP, SEP, oct.atDay(25));
        assertThat(later.getTotals().getEarned()).isEqualByComparingTo("7000000");
        assertThat(later.getNotYetCount()).isZero();
    }

    /**
     * The first row ever is November's salary paid on 30 October: October has nothing counted in it,
     * yet it is the first month — its wallets got 7,000,000 — and November then has it as the month before.
     */
    @Test
    void aMonthWhoseOnlyRowIsAnotherMonthsSalaryStillShowsWhatTheWalletsDid() {
        YearMonth oct = YearMonth.of(2026, 10);
        YearMonth nov = YearMonth.of(2026, 11);
        Category salary = f.category(10, "Salary", null);
        f.ledger.income(oct.atDay(30), "7000000", salary).setSalaryMonth(nov.atDay(1));

        AnalyticsResponse october = f.analytics.analytics(oct, oct, oct.atDay(31));
        assertThat(october.getFirstMonth()).isEqualTo("2026-10");
        assertThat(october.getMonthsWithData()).isZero();
        assertThat(october.getMonths()).extracting(MonthFlow::getMonth).containsExactly("2026-10");
        assertThat(october.getTotals().getCount()).isZero();
        assertThat(october.getTotals().getEarned()).isEqualByComparingTo("0");
        assertThat(october.getTotals().getWalletChange()).isEqualByComparingTo("7000000");

        AnalyticsResponse november = f.analytics.analytics(nov, nov, nov.atDay(5));
        assertThat(november.getFirstMonth()).isEqualTo("2026-10");
        assertThat(november.getTotals().getEarned()).isEqualByComparingTo("7000000");
        assertThat(november.getTotals().getWalletChange()).isEqualByComparingTo("0");
        assertThat(november.getPrevious().getCount()).isZero();
        assertThat(november.getPrevious().getWalletChange()).isEqualByComparingTo("7000000");
        assertThat(november.getEveryday().getPreviousDaily()).isNull();          // nothing counted in October
        assertIdentities(november.getPrevious());
    }

    /** February's salary paid on 31 March: no day of February's charts to put it on, and nothing breaks. */
    @Test
    void aSalaryForTheMonthBeforeHasNoDayInEitherMonthsCharts() {
        YearMonth feb = YearMonth.of(2027, 2);
        YearMonth mar = YearMonth.of(2027, 3);
        Category salary = f.category(10, "Salary", null);
        f.ledger.income(mar.atDay(31), "7000000", salary).setSalaryMonth(feb.atDay(1));
        f.spend(feb.atDay(10), "100000", null, "Bozor");
        f.spend(mar.atDay(10), "200000", null, "Bozor");

        AnalyticsResponse march = f.analytics.analytics(mar, mar, mar.atDay(31));
        assertThat(march.getTotals().getEarned()).isEqualByComparingTo("0");
        assertThat(march.getPrevious().getEarned()).isEqualByComparingTo("7000000");
        assertThat(march.getEveryday().getPreviousDaily()).hasSize(28);
        assertThat(march.getEveryday().getPreviousDaily().get(27).getCumulative()).isEqualByComparingTo("100000");
        assertThat(march.getEveryday().getDaily()).hasSize(31);

        AnalyticsResponse february = f.analytics.analytics(feb, feb, mar.atDay(31));
        assertThat(february.getTotals().getEarned()).isEqualByComparingTo("7000000");
        assertThat(february.getEveryday().getDaily()).hasSize(28);
        assertThat(february.getEveryday().getDaily().get(27).getCumulative()).isEqualByComparingTo("100000");
    }

    @Test
    void class6_moneyLentIsNotSpending() {
        Transaction t = row(sep(3), TransactionType.EXPENSE, TransactionSubType.LOAN_GIVEN, "1000000");

        assertThat(AnalyticsService.classify(t)).isEqualTo(FlowClass.LENT);
        Flow flow = september().getTotals();
        assertThat(flow.getLent()).isEqualByComparingTo("1000000");
        assertThat(flow.getOut()).isEqualByComparingTo("0");
        assertThat(flow.getWalletChange()).isEqualByComparingTo("-1000000");
    }

    @Test
    void class7_moneyPutByIsSaved_splitByTheRowsBucket() {
        f.save(sep(1), TransactionSubType.DONATION, AllocationBucket.DONATION, "100000");
        f.save(sep(2), TransactionSubType.EMERGENCY_CONTRIBUTION, AllocationBucket.EMERGENCY, "200000");
        f.save(sep(3), TransactionSubType.INVESTMENT, AllocationBucket.INVESTMENTS, "300000");
        f.save(sep(4), TransactionSubType.STOCK_PURCHASE, AllocationBucket.STOCKS, "400000");   // the legacy bucket
        Investment car = f.holding(40, "Mashina", "500000", null, sep(5));
        car.setSavingsGoal(true);
        f.save(sep(5), TransactionSubType.INVESTMENT, AllocationBucket.SAVINGS, "500000").setInvestmentId(40L);
        // A row from before the bucket was recorded: derived from the holding it paid into.
        Transaction old = row(sep(6), TransactionType.EXPENSE, TransactionSubType.INVESTMENT, "600000");
        old.setInvestmentId(40L);

        assertThat(AnalyticsService.classify(old)).isEqualTo(FlowClass.SAVED);
        AnalyticsResponse r = september();
        Flow flow = r.getTotals();
        assertThat(flow.getSaved()).isEqualByComparingTo("2100000");
        assertThat(flow.getSavedDonation()).isEqualByComparingTo("100000");
        assertThat(flow.getSavedEmergency()).isEqualByComparingTo("200000");
        assertThat(flow.getSavedInvestments()).isEqualByComparingTo("700000");     // 300,000 + the stocks' 400,000
        assertThat(flow.getSavedGoals()).isEqualByComparingTo("1100000");
        assertThat(flow.getOut()).isEqualByComparingTo("0");                       // saving is not spending
        assertThat(flow.getLeftOver()).isEqualByComparingTo("-2100000");
        assertThat(r.getSavings()).extracting("kind", "refId", "name", "saved").containsExactly(
                tuple("DONATION", null, null, n("100000")),
                tuple("EMERGENCY", null, null, n("200000")),
                tuple("INVESTMENTS", null, null, n("700000")),
                tuple("GOAL", 40L, "Mashina", n("1100000")));
    }

    @Test
    void class8_loanPaymentsAreListedByTheLoanPaid() {
        f.bankLoan(1, "Xalq Banki", "Talim kredit", "26000000", "400000", LocalDate.of(2026, 1, 1), null);
        f.bankLoan(2, "Kapitalbank", "Avtokredit", "90000000", "2500000", LocalDate.of(2026, 1, 1), null);
        f.borrowed(7, "Akam", "3000000", "1000000", "500000", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1));
        f.borrowed(8, "Uzum Nasiya", "800000", "300000", null, sep(1), null);
        f.debt(9, "Do'kon", "900000", "200000", sep(1));

        Transaction bank = row(sep(2), TransactionType.EXPENSE, TransactionSubType.BANK_LOAN_PAYMENT, "400000");
        bank.setDescription("Bank installment — Xalq Banki (Talim kredit)");       // how the web's Pay writes it
        row(sep(3), TransactionType.EXPENSE, TransactionSubType.BANK_LOAN_PAYMENT, "2500000").setDescription("kredit");
        row(sep(4), TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT, "500000").setRepaidLoanTakenId(7L);
        row(sep(5), TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT, "300000").setRepaidLoanTakenId(8L);
        row(sep(6), TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT, "200000").setRepaidDebtId(9L);
        row(sep(7), TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT, "50000").setDescription("Qo'shniga");

        assertThat(AnalyticsService.classify(bank)).isEqualTo(FlowClass.LOAN_PAYMENT);
        AnalyticsResponse r = september();
        assertThat(r.getTotals().getLoanPayments()).isEqualByComparingTo("3950000");
        assertThat(r.getTotals().getEveryday()).isEqualByComparingTo("0");
        assertThat(r.getLoanPayments()).extracting("kind", "refId", "name", "asap", "paid").containsExactly(
                tuple("BANK", null, "Bank loan", false, n("2500000")),             // two loans run: it cannot be told
                tuple("LOAN", 7L, "Akam", false, n("500000")),                     // a monthly plan
                tuple("BANK", 1L, "Xalq Banki · Talim kredit", false, n("400000")),
                tuple("LOAN", 8L, "Uzum Nasiya", true, n("300000")),               // no plan: as fast as possible
                tuple("DEBT", 9L, "Do'kon", true, n("200000")),                    // a debt always is
                tuple("LOAN", null, "Qo'shniga", false, n("50000")));              // names no loan
    }

    @Test
    void class8_theOnlyBankLoanRunningIsTheOnePaid() {
        BankLoan only = f.bankLoan(1, "Xalq Banki", "Talim kredit", "26000000", "400000", LocalDate.of(2026, 1, 1), null);
        BankLoan ended = f.bankLoan(2, "Kapitalbank", "Avtokredit", "9000000", "900000",
                LocalDate.of(2025, 1, 1), LocalDate.of(2026, 3, 1));
        Transaction bare = row(sep(2), TransactionType.EXPENSE, TransactionSubType.BANK_LOAN_PAYMENT, "400000");
        bare.setDescription("Bank installment");                                   // the bot's fallback text

        assertThat(AnalyticsService.bankLoanOf(bare, List.of(only, ended))).isSameAs(only);
    }

    @Test
    void class9_aBillPaidThroughPayIsABill_notEverydaySpending() {
        f.bill(3, "Kvartira Arenda", "4200000");
        Transaction rent = f.ledger.billPaid(sep(8), "4200000", 3);
        f.ledger.billPaid(sep(9), "90000", 99).setDescription("Eski internet");    // a bill since deleted
        f.ledger.billPaid(sep(10), "10000", 99).setDescription("Eski internet");

        assertThat(AnalyticsService.classify(rent)).isEqualTo(FlowClass.BILL);
        assertThat(DailyAdviceService.isEverydaySpend(rent)).isFalse();
        AnalyticsResponse r = september();
        assertThat(r.getTotals().getBills()).isEqualByComparingTo("4300000");
        assertThat(r.getTotals().getEveryday()).isEqualByComparingTo("0");
        assertThat(r.getEveryday().getCategories()).isEmpty();
        assertThat(r.getBills()).extracting("refId", "name", "paid", "count").containsExactly(
                tuple(3L, "Kvartira Arenda", n("4200000"), 1),
                tuple(99L, "Eski internet", n("100000"), 2));
    }

    @Test
    void class10_aWalletCheckThatFoundLessIsEverydaySpendingNobodyItemised() {
        Transaction t = f.foundMissing(sep(5), "400000");

        assertThat(AnalyticsService.classify(t)).isEqualTo(FlowClass.EVERYDAY_UNITEMISED);
        AnalyticsResponse r = september();
        assertThat(r.getTotals().getEveryday()).isEqualByComparingTo("400000");
        assertThat(r.getTotals().getEverydayUnitemised()).isEqualByComparingTo("400000");
        assertThat(r.getEveryday().getCategories()).isEmpty();
        assertThat(r.getEveryday().getBiggest()).isEmpty();
    }

    @Test
    void class11_anyOtherExpenseIsItemisedEverydaySpending() {
        Transaction regular = f.spend(sep(5), "300000", null, "Bozor");
        Transaction noSubType = row(sep(6), TransactionType.EXPENSE, null, "200000");

        assertThat(AnalyticsService.classify(regular)).isEqualTo(FlowClass.EVERYDAY);
        assertThat(AnalyticsService.classify(noSubType)).isEqualTo(FlowClass.EVERYDAY);
        Flow flow = september().getTotals();
        assertThat(flow.getEveryday()).isEqualByComparingTo("500000");
        assertThat(flow.getEverydayUnitemised()).isEqualByComparingTo("0");
    }

    // ── First match wins ──────────────────────────────────────────────────────

    @Test
    void firstMatchWins_downTheTable() {
        // A saving, a loan payment and a wallet-check row that also carry a bill's id: the earlier class takes them…
        Transaction donation = row(sep(1), TransactionType.EXPENSE, TransactionSubType.DONATION, "100000");
        donation.setMonthlyPaymentId(3L);
        Transaction repayment = row(sep(2), TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT, "200000");
        repayment.setMonthlyPaymentId(3L);
        // …and a bill (class 9) comes before the wallet-check class (10).
        Transaction billByCheck = row(sep(3), TransactionType.EXPENSE, TransactionSubType.EVERYDAY_SPENDING, "300000");
        billByCheck.setMonthlyPaymentId(3L);

        assertThat(AnalyticsService.classify(donation)).isEqualTo(FlowClass.SAVED);
        assertThat(AnalyticsService.classify(repayment)).isEqualTo(FlowClass.LOAN_PAYMENT);
        assertThat(AnalyticsService.classify(billByCheck)).isEqualTo(FlowClass.BILL);
        Flow flow = september().getTotals();
        assertThat(flow.getSaved()).isEqualByComparingTo("100000");
        assertThat(flow.getLoanPayments()).isEqualByComparingTo("200000");
        assertThat(flow.getBills()).isEqualByComparingTo("300000");
        assertThat(flow.getEveryday()).isEqualByComparingTo("0");
    }

    // ── §11.2: which rows count ───────────────────────────────────────────────

    @Test
    void transfersBetweenTheOwnersWalletsAreIgnoredEntirely() {
        Transaction out = row(sep(3), TransactionType.EXPENSE, TransactionSubType.TRANSFER_OUT, "1000000");
        Transaction in = row(sep(3), TransactionType.INCOME, TransactionSubType.TRANSFER_IN, "1000000");
        out.setTransferPairId(out.getId());
        in.setTransferPairId(out.getId());
        // A cash ↔ card move booked as a plain pair: only the pair id says so.
        Transaction pairedOut = row(sep(4), TransactionType.EXPENSE, TransactionSubType.REGULAR_EXPENSE, "500000");
        Transaction pairedIn = row(sep(4), TransactionType.INCOME, TransactionSubType.REGULAR_INCOME, "500000");
        pairedOut.setTransferPairId(pairedOut.getId());
        pairedIn.setTransferPairId(pairedOut.getId());
        // And a legacy half with the sub-type but no pair id.
        Transaction legacy = row(sep(5), TransactionType.INCOME, TransactionSubType.TRANSFER_IN, "70000");

        assertThat(List.of(out, in, pairedOut, pairedIn, legacy)).noneMatch(AnalyticsService::counts);
        AnalyticsResponse r = september();
        assertThat(r.getTotals().getCount()).isZero();
        assertThat(r.getTotals().getEarned()).isEqualByComparingTo("0");
        assertThat(r.getTotals().getEveryday()).isEqualByComparingTo("0");
        assertThat(r.getTotals().getWalletChange()).isEqualByComparingTo("0");
        assertThat(r.getFirstMonth()).isNull();
        assertThat(r.getMonthsWithData()).isZero();
        assertThat(r.getMonths()).isEmpty();
    }

    @Test
    void onlyUzsRowsCount() {
        f.spend(sep(3), "300000", null, "Bozor");
        f.spend(sep(4), "100", null, "Dollar").setCurrency(Currency.USD);
        f.spend(sep(5), "50000", null, "Eski").setCurrency(null);                  // a legacy row: no currency = UZS

        Flow flow = september().getTotals();
        assertThat(flow.getEveryday()).isEqualByComparingTo("350000");
        assertThat(flow.getCount()).isEqualTo(2);
    }

    @Test
    void aRowDatedAfterTheOwnersDayLandsOnlyInNotYetCount() {
        f.spend(sep(19), "300000", null, "Bozor");
        f.spend(sep(20), "200000", null, "Bugun");                                 // today counts
        f.spend(sep(21), "5000000", null, "Ertaga");
        f.ledger.income(sep(25), "7000000", null);
        f.spend(LocalDate.of(2026, 10, 2), "900000", null, "Oktabr");              // outside the range: nowhere
        Transaction pair = row(sep(28), TransactionType.EXPENSE, TransactionSubType.TRANSFER_OUT, "1000000");
        pair.setTransferPairId(pair.getId());                                      // a transfer is not even "not yet"

        AnalyticsResponse r = september();
        assertThat(r.getNotYetCount()).isEqualTo(2);
        assertThat(r.getTotals().getEveryday()).isEqualByComparingTo("500000");
        assertThat(r.getTotals().getEarned()).isEqualByComparingTo("0");
        assertThat(r.getTotals().getCount()).isEqualTo(2);
        assertThat(r.getEveryday().getBiggest()).extracting("description").containsExactly("Bozor", "Bugun");
        assertThat(r.getEveryday().getDaily()).hasSize(20);                        // the 1st to today
    }

    // ── Flow identities, for totals, previous and every month ─────────────────

    private void aYearInThreeMonths() {
        Category salary = f.category(10, "Salary", null);
        Category bonus = f.category(12, "Bonus", salary);
        bonus.setBonusIncome(true);
        Category food = f.category(5, "Food", null);
        for (YearMonth m : List.of(JUL, AUG, SEP)) {
            int k = m.getMonthValue();
            f.ledger.income(m.atDay(5), String.valueOf(7_000_000 + k * 1000), salary);
            f.ledger.income(m.atDay(6), String.valueOf(100_000 * k), bonus);
            f.ledger.income(m.atDay(7), String.valueOf(10_000 * k), null);
            f.spend(m.atDay(8), String.valueOf(300_000 + k), food, "Bozor");
            f.foundMissing(m.atDay(10), String.valueOf(900_000 + k));
            f.foundExtra(m.atDay(11), String.valueOf(40_000 + k));
            f.ledger.billPaid(m.atDay(9), "4200000", 3);
            row(m.atDay(12), TransactionType.EXPENSE, TransactionSubType.BANK_LOAN_PAYMENT, "400000");
            row(m.atDay(12), TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT, String.valueOf(50_000 * k));
            f.save(m.atDay(13), TransactionSubType.DONATION, AllocationBucket.DONATION, String.valueOf(10_000 * k));
            f.save(m.atDay(13), TransactionSubType.EMERGENCY_CONTRIBUTION, AllocationBucket.EMERGENCY, String.valueOf(20_000 * k));
            f.save(m.atDay(13), TransactionSubType.INVESTMENT, AllocationBucket.INVESTMENTS, String.valueOf(30_000 * k));
            f.save(m.atDay(13), TransactionSubType.INVESTMENT, AllocationBucket.SAVINGS, String.valueOf(40_000 * k));
            row(m.atDay(14), TransactionType.INCOME, TransactionSubType.LOAN_RECEIVED, String.valueOf(60_000 * k));
            row(m.atDay(15), TransactionType.EXPENSE, TransactionSubType.LOAN_GIVEN, String.valueOf(70_000 * k));
            row(m.atDay(16), TransactionType.INCOME, TransactionSubType.LOAN_RETURNED_TO_ME, String.valueOf(5_000 * k));
            row(m.atDay(17), TransactionType.INCOME, TransactionSubType.INVESTMENT_WITHDRAWAL, String.valueOf(3_000 * k));
        }
    }

    private static void assertIdentities(Flow x) {
        assertThat(x.getEarned()).isEqualByComparingTo(x.getEarnedPay().add(x.getEarnedBonus()).add(x.getEarnedOther()));
        assertThat(x.getOut()).isEqualByComparingTo(x.getEveryday().add(x.getBills()).add(x.getLoanPayments()));
        assertThat(x.getSaved()).isEqualByComparingTo(x.getSavedDonation().add(x.getSavedEmergency())
                .add(x.getSavedInvestments()).add(x.getSavedGoals()));
        assertThat(x.getLeftOver()).isEqualByComparingTo(x.getEarned().subtract(x.getOut()).subtract(x.getSaved()));
        assertThat(x.getWalletChange()).isEqualByComparingTo(x.getLeftOver().add(x.getBorrowed()).subtract(x.getLent())
                .add(x.getReturned()).add(x.getFromSavings()).add(x.getPayForOtherMonths()));
    }

    @Test
    void theIdentitiesHold_totalsAreTheSumOfTheMonths_andPreviousIsTheSameSpanBefore() {
        aYearInThreeMonths();

        AnalyticsResponse r = f.analytics.analytics(SEP, SEP, LocalDate.of(2026, 9, 30));
        AnalyticsResponse two = f.analytics.analytics(AUG, SEP, LocalDate.of(2026, 9, 30));

        // One month: previous is August.
        assertIdentities(r.getTotals());
        assertIdentities(r.getPrevious());
        assertThat(r.getPrevious().getEarnedPay()).isEqualByComparingTo("7008000");
        assertThat(r.getPrevious().getCount()).isEqualTo(17);
        assertThat(r.getTotals().getEarnedPay()).isEqualByComparingTo("7009000");

        // Two months: previous is June + July — only July has rows.
        assertIdentities(two.getTotals());
        assertIdentities(two.getPrevious());
        assertThat(two.getPrevious().getEarnedPay()).isEqualByComparingTo("7007000");
        assertThat(two.getMonths()).hasSize(2);
        Flow sum = new Flow();
        for (MonthFlow m : two.getMonths()) {
            assertIdentities(m);
            sum.add(m);
        }
        Flow t = two.getTotals();
        assertThat(t.getEarned()).isEqualByComparingTo(sum.getEarned());
        assertThat(t.getEarnedPay()).isEqualByComparingTo(sum.getEarnedPay());
        assertThat(t.getEarnedBonus()).isEqualByComparingTo(sum.getEarnedBonus());
        assertThat(t.getEarnedOther()).isEqualByComparingTo(sum.getEarnedOther());
        assertThat(t.getEveryday()).isEqualByComparingTo(sum.getEveryday());
        assertThat(t.getEverydayUnitemised()).isEqualByComparingTo(sum.getEverydayUnitemised());
        assertThat(t.getBills()).isEqualByComparingTo(sum.getBills());
        assertThat(t.getLoanPayments()).isEqualByComparingTo(sum.getLoanPayments());
        assertThat(t.getOut()).isEqualByComparingTo(sum.getOut());
        assertThat(t.getSaved()).isEqualByComparingTo(sum.getSaved());
        assertThat(t.getSavedDonation()).isEqualByComparingTo(sum.getSavedDonation());
        assertThat(t.getSavedEmergency()).isEqualByComparingTo(sum.getSavedEmergency());
        assertThat(t.getSavedInvestments()).isEqualByComparingTo(sum.getSavedInvestments());
        assertThat(t.getSavedGoals()).isEqualByComparingTo(sum.getSavedGoals());
        assertThat(t.getLeftOver()).isEqualByComparingTo(sum.getLeftOver());
        assertThat(t.getBorrowed()).isEqualByComparingTo(sum.getBorrowed());
        assertThat(t.getLent()).isEqualByComparingTo(sum.getLent());
        assertThat(t.getReturned()).isEqualByComparingTo(sum.getReturned());
        assertThat(t.getFromSavings()).isEqualByComparingTo(sum.getFromSavings());
        assertThat(t.getPayForOtherMonths()).isEqualByComparingTo(sum.getPayForOtherMonths());
        assertThat(t.getWalletChange()).isEqualByComparingTo(sum.getWalletChange());
        assertThat(t.getCount()).isEqualTo(sum.getCount()).isEqualTo(34);

        // And a month's own figures, by hand (September, k = 9).
        MonthFlow sep = two.getMonths().get(1);
        assertThat(sep.getEarned()).isEqualByComparingTo("7999000");               // 7,009,000 + 900,000 + 90,000
        assertThat(sep.getEverydayUnitemised()).isEqualByComparingTo("860000");    // 900,009 − 40,009
        assertThat(sep.getEveryday()).isEqualByComparingTo("1160009");
        assertThat(sep.getLoanPayments()).isEqualByComparingTo("850000");
        assertThat(sep.getOut()).isEqualByComparingTo("6210009");
        assertThat(sep.getSaved()).isEqualByComparingTo("900000");
        assertThat(sep.getLeftOver()).isEqualByComparingTo("888991");
        assertThat(sep.getWalletChange()).isEqualByComparingTo("870991");          // + 540,000 − 630,000 + 45,000 + 27,000
    }

    // ── The range ─────────────────────────────────────────────────────────────

    @Test
    void monthsBeforeTheFirstWithDataAreLeftOut_andAnEmptyOneAfterItIsNot() {
        f.spend(JUL.atDay(12), "300000", null, "Iyul");
        f.spend(sep(3), "200000", null, "Sentabr");

        AnalyticsResponse r = f.analytics.analytics(YearMonth.of(2026, 4), SEP, TODAY);

        assertThat(r.getFirstMonth()).isEqualTo("2026-07");
        assertThat(r.getMonthsWithData()).isEqualTo(2);
        assertThat(r.getMonths()).extracting("month", "complete", "days", "count").containsExactly(
                tuple("2026-07", true, 31, 1),
                tuple("2026-08", true, 31, 0),          // nothing recorded, but after the first month: a real zero
                tuple("2026-09", false, 20, 1));        // open: counted up to the owner's day
        assertThat(r.getEveryday().getDays()).isEqualTo(82);
        assertThat(r.getEveryday().getTotal()).isEqualByComparingTo("500000");
        assertThat(r.getEveryday().getPerDay()).isEqualByComparingTo("6098");      // 500,000 ÷ 82, to the so'm
        assertThat(r.getEveryday().getDaily()).isEmpty();                          // per-day figures: one month only
        assertThat(r.getEveryday().getPreviousDaily()).isNull();
        assertThat(r.getEveryday().getBiggestDays()).isEmpty();
    }

    @Test
    void aRangeWithNoDataAtAllIsEmptyNotAnError() {
        AnalyticsResponse r = september();

        assertThat(r.getFirstMonth()).isNull();
        assertThat(r.getMonths()).isEmpty();
        assertThat(r.getPrevious()).isNull();
        assertThat(r.getStableIncome()).isNull();                                  // Settings has none yet
        assertThat(r.getTotals().getEarned()).isEqualByComparingTo("0");
        assertThat(r.getEveryday().getDays()).isZero();
        assertThat(r.getEveryday().getPerDay()).isNull();
        assertThat(r.getIncome()).isEmpty();
        assertThat(r.getBills()).isEmpty();
        assertThat(r.getLoanPayments()).isEmpty();
        assertThat(r.getSavings()).extracting("kind", "saved", "asked").containsExactly(
                tuple("DONATION", n("0"), null), tuple("EMERGENCY", n("0"), null), tuple("INVESTMENTS", n("0"), null));
    }

    @Test
    void theRangeDefaultsToTheMonthOfTheDate() {
        f.spend(sep(3), "200000", null, "Sentabr");
        f.spend(AUG.atDay(3), "100000", null, "Avgust");

        AnalyticsResponse both = f.analytics.analytics(null, null, TODAY);
        assertThat(both.getFrom()).isEqualTo("2026-09");
        assertThat(both.getTo()).isEqualTo("2026-09");

        AnalyticsResponse fromOnly = f.analytics.analytics(AUG, null, TODAY);      // to = the month of date
        assertThat(fromOnly.getFrom()).isEqualTo("2026-08");
        assertThat(fromOnly.getTo()).isEqualTo("2026-09");

        AnalyticsResponse toOnly = f.analytics.analytics(null, AUG, TODAY);        // from = to: that one month
        assertThat(toOnly.getFrom()).isEqualTo("2026-08");
        assertThat(toOnly.getTo()).isEqualTo("2026-08");
        assertThat(toOnly.getTotals().getEveryday()).isEqualByComparingTo("100000");
    }

    @Test
    void thePositionIsSentOnlyWhenTheRangeEndsInTheMonthOfTheDate() {
        f.wallet("CASH", Currency.UZS, "1000000");
        f.spend(AUG.atDay(3), "100000", null, "Avgust");

        assertThat(f.analytics.analytics(AUG, AUG, TODAY).getPosition()).isNull();
        assertThat(f.analytics.analytics(JUL, AUG, TODAY).getPosition()).isNull();
        assertThat(f.analytics.analytics(AUG, SEP, TODAY).getPosition()).isNotNull();
        assertThat(september().getPosition().getWallets()).isEqualByComparingTo("1000000");
    }

    @Test
    void aPastMonthIsCompleteAndCountsAllItsDays() {
        f.spend(AUG.atDay(3), "310000", null, "Avgust");

        AnalyticsResponse r = f.analytics.analytics(AUG, AUG, TODAY);

        assertThat(r.getMonths()).extracting("month", "complete", "days").containsExactly(tuple("2026-08", true, 31));
        assertThat(r.getEveryday().getPerDay()).isEqualByComparingTo("10000");
        assertThat(r.getEveryday().getDaily()).hasSize(31);
    }

    @Test
    void aBadRangeIsRefused() {
        assertThatThrownBy(() -> f.analytics.analytics(SEP, YearMonth.of(2026, 10), TODAY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("to can't be after the month of date");
        assertThatThrownBy(() -> f.analytics.analytics(SEP, AUG, TODAY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("from can't be after to");
        assertThatThrownBy(() -> f.analytics.analytics(YearMonth.of(2024, 9), SEP, TODAY))   // 25 months
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("24 months");
        assertThatThrownBy(() -> f.analytics.analytics(SEP, SEP, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("date is required");

        // Exactly 24 months is fine.
        assertThat(f.analytics.analytics(YearMonth.of(2024, 10), SEP, TODAY).getFrom()).isEqualTo("2024-10");
    }

    // ── Everyday: the month before ────────────────────────────────────────────

    @Test
    void previousDailyHasEveryDayOfTheMonthBefore_andCategoriesCarryItsAmounts() {
        Category food = f.category(5, "Food", null);
        Category snacks = f.category(51, "Snacks", food);
        Category housing = f.category(4, "Housing", null);
        f.spend(AUG.atDay(2), "100000", snacks, "Avgust");                          // counted under Food, its top
        f.spend(AUG.atDay(2), "50000", food, "Avgust");
        f.foundMissing(AUG.atDay(30), "400000");
        f.foundExtra(AUG.atDay(31), "30000");
        f.spend(sep(4), "250000", food, "Sentabr");
        f.spend(sep(5), "900000", housing, "Ijara");

        Everyday e = september().getEveryday();

        List<PreviousDay> before = e.getPreviousDaily();
        assertThat(before).hasSize(31);
        assertThat(before.get(0).getDay()).isEqualTo(1);
        assertThat(before.get(0).getCumulative()).isEqualByComparingTo("0");
        assertThat(before.get(1).getCumulative()).isEqualByComparingTo("150000");
        assertThat(before.get(28).getCumulative()).isEqualByComparingTo("150000");
        assertThat(before.get(29).getCumulative()).isEqualByComparingTo("550000");
        assertThat(before.get(30).getDay()).isEqualTo(31);
        assertThat(before.get(30).getCumulative()).isEqualByComparingTo("520000"); // = August's everyday

        assertThat(e.getCategories()).extracting("name", "amount").containsExactly(
                tuple("Housing", n("900000")), tuple("Food", n("250000")));
        assertThat(e.getCategories().get(0).getPreviousAmount()).isEqualByComparingTo("0");       // nothing in August
        assertThat(e.getCategories().get(1).getPreviousAmount()).isEqualByComparingTo("150000");
    }

    @Test
    void previousDailyIsNullWhenTheMonthBeforeIsEmpty() {
        f.spend(JUL.atDay(4), "250000", null, "Iyul");                             // two months back does not count
        f.spend(sep(4), "250000", null, "Sentabr");

        AnalyticsResponse r = september();

        assertThat(r.getPrevious()).isNull();
        assertThat(r.getEveryday().getPreviousDaily()).isNull();
        assertThat(r.getEveryday().getCategories().get(0).getPreviousAmount()).isNull();
    }

    @Test
    void dailyRunsFromTheFirstToTheOwnersDay() {
        f.spend(sep(1), "100000", null, "Bir");
        f.spend(sep(1), "50000", null, "Bir-b");
        f.foundMissing(sep(3), "70000");
        f.spend(sep(20), "30000", null, "Yigirma");

        List<Day> daily = september().getEveryday().getDaily();

        assertThat(daily).hasSize(20);
        assertThat(daily).extracting(Day::getDate).first().isEqualTo(sep(1));
        assertThat(daily).extracting(Day::getDate).last().isEqualTo(sep(20));
        assertThat(daily.get(0).getAmount()).isEqualByComparingTo("150000");
        assertThat(daily.get(2).getAmount()).isEqualByComparingTo("70000");
        assertThat(daily.get(2).getUnitemised()).isEqualByComparingTo("70000");
        assertThat(daily.get(2).getCumulative()).isEqualByComparingTo("220000");
        assertThat(daily.get(18).getCumulative()).isEqualByComparingTo("220000");
        assertThat(daily.get(19).getCumulative()).isEqualByComparingTo("250000");
    }

    @Test
    void categoriesPlusUnitemisedIsTheTotal_overSeveralMonths() {
        aYearInThreeMonths();

        Everyday e = f.analytics.analytics(JUL, SEP, TODAY).getEveryday();

        BigDecimal categories = e.getCategories().stream().map(EverydayCategory::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(categories).isEqualByComparingTo("900024");                     // 300,007 + 300,008 + 300,009
        assertThat(categories.add(e.getUnitemised())).isEqualByComparingTo(e.getTotal());
    }

    // ── Savings: asked ────────────────────────────────────────────────────────

    /** A past month's ask is the ledger's recommended + carried — read from it, never worked out here. */
    @Test
    void aPastMonthsAskIsTheLedgers() {
        f.stableIncome("7000000", JUL.atDay(1));
        f.save(JUL.atDay(10), TransactionSubType.INVESTMENT, AllocationBucket.INVESTMENTS, "400000");
        f.save(AUG.atDay(10), TransactionSubType.DONATION, AllocationBucket.DONATION, "100000");

        AnalyticsResponse r = f.analytics.analytics(AUG, AUG, TODAY);
        AllocationLedgerResponse ledger = f.overview.getAllocationLedger(AUG, Currency.UZS);

        assertThat(ledger.getBuckets()).hasSize(3);
        for (AllocationLedgerResponse.BucketLedger b : ledger.getBuckets()) {
            SavingLine line = r.getSavings().stream().filter(l -> l.getKind().equals(b.getBucket())).findFirst().orElseThrow();
            assertThat(line.getAsked()).isEqualByComparingTo(b.getRecommended().add(b.getCarried()));
        }
        // No debts: 10 / 5 / 15 % of 7,000,000 — and July's unpaid 700,000 + 350,000 + 650,000 carried in.
        assertThat(r.getSavings()).extracting("kind", "saved").containsExactly(
                tuple("DONATION", n("100000")), tuple("EMERGENCY", n("0")), tuple("INVESTMENTS", n("0")));
        assertThat(r.getSavings().get(0).getAsked()).isEqualByComparingTo("1400000");
        assertThat(r.getSavings().get(1).getAsked()).isEqualByComparingTo("700000");
        assertThat(r.getSavings().get(2).getAsked()).isEqualByComparingTo("1700000");
    }

    @Test
    void aRangeOfSeveralMonthsAsksNothing() {
        f.stableIncome("7000000", JUL.atDay(1));
        f.save(AUG.atDay(10), TransactionSubType.DONATION, AllocationBucket.DONATION, "100000");

        AnalyticsResponse r = f.analytics.analytics(AUG, SEP, TODAY);

        assertThat(r.getSavings()).extracting("kind", "saved", "asked").containsExactly(
                tuple("DONATION", n("100000"), null), tuple("EMERGENCY", n("0"), null), tuple("INVESTMENTS", n("0"), null));
    }

    @Test
    void aGoalIsListedWhenItGotMoneyOrAsksForSomeThisMonth() {
        f.stableIncome("7000000", sep(1));
        Investment car = f.holding(40, "Mashina", "2000000", null, LocalDate.of(2026, 5, 1));
        car.setSavingsGoal(true);
        car.setMonthlyContribution(n("500000"));
        Investment phone = f.holding(41, "Telefon", "300000", null, LocalDate.of(2026, 5, 1));
        phone.setSavingsGoal(true);                                                // no monthly payment: asks nothing
        Investment house = f.holding(42, "Uy", "0", null, LocalDate.of(2026, 5, 1));
        house.setSavingsGoal(true);
        house.setMonthlyContribution(n("900000"));
        house.setPaymentStartDate(LocalDate.of(2026, 11, 1));                      // not started yet
        f.save(sep(5), TransactionSubType.INVESTMENT, AllocationBucket.SAVINGS, "300000").setInvestmentId(41L);

        List<SavingLine> goals = september().getSavings().stream().filter(l -> "GOAL".equals(l.getKind())).toList();

        assertThat(goals).extracting("refId", "name", "saved", "asked").containsExactly(
                tuple(41L, "Telefon", n("300000"), null),
                tuple(40L, "Mashina", n("0"), n("500000")));
    }

    // ── Position ──────────────────────────────────────────────────────────────

    @Test
    void whatIsLeftOnABankLoanIsKnownOnlyWithAnEndDate() {
        // Ends 15 December: September's installment is marked paid, so October, November and December are left.
        f.bankLoan(1, "Xalq Banki", "Talim kredit", "4800000", "400000", LocalDate.of(2026, 1, 15), LocalDate.of(2026, 12, 15));
        f.bankMark(1, sep(1), "400000");
        // Same, nothing paid this month: four left.
        f.bankLoan(2, "Kapitalbank", "Avtokredit", "12000000", "1000000", LocalDate.of(2026, 1, 20), LocalDate.of(2026, 12, 20));
        // Ended in August: gone.
        f.bankLoan(3, "Ipoteka", "Eski", "9000000", "900000", LocalDate.of(2025, 1, 1), LocalDate.of(2026, 8, 1));
        // No end date: what is left cannot be known.
        f.bankLoan(4, "Hamkor", "Mikroqarz", "3000000", "300000", LocalDate.of(2026, 2, 25), null);

        AnalyticsResponse.Position p = september().getPosition();

        assertThat(p.getLoans()).extracting("kind", "refId", "left", "monthly", "paidOffBy").containsExactly(
                tuple("BANK", 2L, n("4000000"), n("1000000"), "2026-12"),
                tuple("BANK", 1L, n("1200000"), n("400000"), "2026-12"),
                tuple("BANK", 4L, null, n("300000"), null));
        assertThat(p.getLoansLeft()).isEqualByComparingTo("5200000");
        assertThat(p.getNet()).isEqualByComparingTo("-5200000");
    }

    @Test
    void aMonthlyLoanIsPaidOffWhenItsPlanRunsOut_andAsapMoneyHasNoSuchMonth() {
        f.stableIncome("7000000", sep(1));
        // 2,300,000 left at 500,000 a month, nothing paid this month: Sept, Oct, Nov, Dec, and 300,000 in January.
        f.borrowed(1, "Akam", "3000000", "700000", "500000", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1));
        // This month's 500,000 is paid (1,500,000 left): October, November, December.
        f.borrowed(2, "Opam", "3000000", "1500000", "500000", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1));
        row(sep(5), TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT, "500000").setRepaidLoanTakenId(2L);
        // The plan starts in November: 1,000,000 in two payments — November and December.
        f.borrowed(3, "Tog'am", "1000000", "0", "500000", sep(1), LocalDate.of(2026, 11, 1));
        f.borrowed(4, "Uzum Nasiya", "800000", "0", null, sep(1), null);           // ASAP
        f.debt(5, "Do'kon", "600000", "100000", sep(1));
        f.borrowed(6, "Qaytarilgan", "900000", "900000", null, sep(1), null);      // paid off: not listed

        AnalyticsResponse.Position p = september().getPosition();

        assertThat(p.getLoans()).extracting("kind", "refId", "asap", "original", "left", "monthly", "paidOffBy")
                .containsExactly(
                        tuple("LOAN", 1L, false, n("3000000"), n("2300000"), n("500000"), "2027-01"),
                        tuple("LOAN", 2L, false, n("3000000"), n("1500000"), n("500000"), "2026-12"),
                        tuple("LOAN", 3L, false, n("1000000"), n("1000000"), n("500000"), "2026-12"),
                        tuple("LOAN", 4L, true, n("800000"), n("800000"), null, null),
                        tuple("DEBT", 5L, true, n("600000"), n("500000"), null, null));
        assertThat(p.getLoansLeft()).isEqualByComparingTo("6100000");
    }

    @Test
    void whatIsOwnedIsSplitTheWayTheSavingsPageSplitsIt() {
        f.wallet("CARD", Currency.UZS, "2000000");
        f.wallet("CASH", Currency.UZS, "500000");
        f.wallet("CASH", Currency.EUR, "40");
        f.holding(1, "Aksiya", "1000000", "1200000", sep(1));
        f.holding(2, "Depozit", "700000", null, sep(1));                           // no value set: what was put in
        f.holding(3, "Zaxira", "300000", "310000", sep(1)).setEmergencyFund(true);
        f.holding(4, "Mashina", "900000", "950000", sep(1)).setSavingsGoal(true);
        f.holding(5, "Dollar", "100", null, sep(1)).setCurrency(Currency.USD);     // not UZS: in no total
        f.emergencyContribution(sep(2), "150000");
        f.emergencyContribution(sep(25), "999000");                                // dated after today: not held yet
        f.lent(1, "Do'stim", "1000000", "700000");
        f.lent(2, "Qaytargan", "500000", "500000");

        AnalyticsResponse.Position p = september().getPosition();

        assertThat(p.getWallets()).isEqualByComparingTo("2500000");
        assertThat(p.getEmergencyFund()).isEqualByComparingTo("460000");
        assertThat(p.getInvestments()).isEqualByComparingTo("1900000");
        assertThat(p.getGoals()).isEqualByComparingTo("950000");
        assertThat(p.getOwn()).isEqualByComparingTo("5810000");
        assertThat(p.getLoans()).isEmpty();
        assertThat(p.getLoansLeft()).isEqualByComparingTo("0");
        assertThat(p.getOwedToYou()).isEqualByComparingTo("300000");
        assertThat(p.getNet()).isEqualByComparingTo("5810000");                    // what is owed to the owner is not counted in
    }

    // ── The monthly record of the position ────────────────────────────────────

    @Test
    void todaysPositionIsRecordedAsThisMonths() {
        LocalDate today = LocalDate.now();
        f.wallet("CASH", Currency.UZS, "500000");
        f.holding(1, "Aksiya", "1000000", "1200000", today.minusMonths(3));
        f.lent(1, "Do'stim", "1000000", "700000");

        f.analytics.analytics(null, null, today);

        verify(f.snapshots).record(eq(YearMonth.from(today)), eq(today), eq(n("500000")), eq(BigDecimal.ZERO),
                eq(n("1200000")), eq(BigDecimal.ZERO), eq(BigDecimal.ZERO), eq(n("300000")));
    }

    @Test
    void aPageAskedForAnotherDayNeverRewritesTheRecord() {
        LocalDate longAgo = LocalDate.now().minusDays(40);

        f.analytics.analytics(null, null, longAgo);                                           // a position, but not today's
        f.analytics.analytics(YearMonth.from(longAgo), YearMonth.from(longAgo), LocalDate.now().plusMonths(2));

        verify(f.snapshots, never()).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aRecordThatCannotBeWrittenDoesNotFailThePage() {
        doThrow(new IllegalStateException("two requests raced for the month's row"))
                .when(f.snapshots).record(any(), any(), any(), any(), any(), any(), any(), any());

        AnalyticsResponse r = f.analytics.analytics(null, null, LocalDate.now());

        assertThat(r.getPosition()).isNotNull();
    }

    @Test
    void theRecordedMonthsAreServedOldestFirst() {
        PositionSnapshot aug = snapshot(AUG, "1000000", "200000", "3000000", "400000", "5000000", "60000");
        PositionSnapshot sep = snapshot(SEP, "2000000", "300000", "3500000", "500000", "4500000", "0");
        when(f.snapshots.history()).thenReturn(List.of(aug, sep));

        AnalyticsResponse r = f.analytics.analytics(AUG, AUG, TODAY);                // served even without a position

        assertThat(r.getPosition()).isNull();
        assertThat(r.getPositionHistory()).extracting("month", "wallets", "emergencyFund", "investments", "goals",
                "own", "loansLeft", "owedToYou", "net").containsExactly(
                tuple("2026-08", n("1000000"), n("200000"), n("3000000"), n("400000"), n("4600000"), n("5000000"), n("60000"), n("-400000")),
                tuple("2026-09", n("2000000"), n("300000"), n("3500000"), n("500000"), n("6300000"), n("4500000"), n("0"), n("1800000")));
        assertThat(september().getPositionHistory()).hasSize(2);
    }

    private static PositionSnapshot snapshot(YearMonth month, String wallets, String emergency, String investments,
                                             String goals, String loansLeft, String owed) {
        PositionSnapshot s = new PositionSnapshot();
        s.setMonth(month.atDay(1));
        s.setAsOf(month.atEndOfMonth());
        s.setWallets(n(wallets));
        s.setEmergencyFund(n(emergency));
        s.setInvestments(n(investments));
        s.setGoals(n(goals));
        s.setLoansLeft(n(loansLeft));
        s.setOwedToYou(n(owed));
        return s;
    }

    // ── The wire ──────────────────────────────────────────────────────────────

    @Test
    void theJsonCarriesTheContractsFieldNames() throws Exception {
        f.stableIncome("7000000", sep(1));
        Category food = f.category(5, "Food", null);
        f.category(10, "Salary", null);
        f.spend(sep(4), "250000", food, "Bozor");
        f.bill(3, "Ijara", "1000000");
        f.ledger.billPaid(sep(5), "1000000", 3);
        f.wallet("CASH", Currency.UZS, "500000");

        tools.jackson.databind.JsonNode json = tools.jackson.databind.json.JsonMapper.builder().build()
                .valueToTree(september());

        assertThat(json.propertyNames()).containsExactlyInAnyOrder("currency", "date", "from", "to", "firstMonth",
                "monthsWithData", "stableIncome", "notYetCount", "totals", "previous", "months", "income",
                "everyday", "bills", "loanPayments", "savings", "position", "positionHistory");
        String[] flow = {"earned", "earnedPay", "earnedBonus", "earnedOther", "everyday", "everydayUnitemised",
                "bills", "loanPayments", "out", "saved", "savedDonation", "savedEmergency", "savedInvestments",
                "savedGoals", "leftOver", "borrowed", "lent", "returned", "fromSavings", "payForOtherMonths",
                "walletChange", "count"};
        assertThat(json.get("totals").propertyNames()).containsExactlyInAnyOrder(flow);
        assertThat(json.get("months").get(0).propertyNames()).contains(flow).contains("month", "complete", "days")
                .hasSize(flow.length + 3);
        assertThat(json.get("date").asString()).isEqualTo("2026-09-20");
        assertThat(json.get("months").get(0).get("month").asString()).isEqualTo("2026-09");
        assertThat(json.get("previous").isNull()).isTrue();
        assertThat(json.get("everyday").propertyNames()).containsExactlyInAnyOrder("total", "days", "perDay",
                "unitemised", "categories", "daily", "previousDaily", "biggestDays", "biggest");
        assertThat(json.get("everyday").get("categories").get(0).propertyNames()).containsExactlyInAnyOrder(
                "categoryId", "name", "nameUz", "color", "amount", "count", "previousAmount", "children");
        assertThat(json.get("everyday").get("daily").get(0).propertyNames()).containsExactlyInAnyOrder(
                "date", "amount", "unitemised", "cumulative");
        assertThat(json.get("everyday").get("biggest").get(0).propertyNames()).containsExactlyInAnyOrder(
                "id", "date", "description", "amount", "categoryId", "categoryName", "categoryNameUz", "color");
        assertThat(json.get("everyday").get("biggestDays").get(0).propertyNames()).containsExactlyInAnyOrder(
                "date", "amount", "topDescription");
        assertThat(json.get("bills").get(0).propertyNames()).containsExactlyInAnyOrder("refId", "name", "paid", "count");
        assertThat(json.get("savings").get(0).propertyNames()).containsExactlyInAnyOrder(
                "kind", "refId", "name", "saved", "asked");
        assertThat(json.get("position").propertyNames()).containsExactlyInAnyOrder("asOf", "wallets", "emergencyFund",
                "investments", "goals", "own", "loans", "loansLeft", "owedToYou", "net");
    }
}
