package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.AdvisorResponse;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Daily;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.IncomePart;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.SavingsRow;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Suggestion;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Upcoming;
import uz.tracker.trackerproject.dto.response.MonthClosePreviewResponse.WalletLine;
import uz.tracker.trackerproject.dto.response.ProfileResponse;
import uz.tracker.trackerproject.dto.response.WalletCheckInStatusResponse;
import uz.tracker.trackerproject.entity.BankLoan;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.entity.MarkPaid;
import uz.tracker.trackerproject.entity.MonthlyPayment;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The owner's real September, on 23 September 2026 — the day Home said "Free after that
 * 8,803,000 — yours to spend or save" and "put 4,250,000 into investments?" while the salary
 * (about 7M) barely covers rent 4.2M, school 1.1M, the bank's 400K and, from 1 October, 500K a
 * month back to the parents; while 12.1M had gone on everyday spending in 23 days.
 *
 * <p>Everything real runs: the Plan (OverviewService), the per-day walk and the advisor. Only the
 * repositories and the wallet balances are stand-ins, and one ledger of transactions feeds them all.
 */
class AdvisorOwnerSeptemberTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 23);
    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);

    private AdvisorService advisor;
    private TransactionLedger ledger;
    private Category salary;
    private OverviewService overview;
    private TransactionRepository transactionRepository;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        MonthlyPaymentRepository monthlyPaymentRepository = mock(MonthlyPaymentRepository.class);
        BankLoanRepository bankLoanRepository = mock(BankLoanRepository.class);
        LoanTakenRepository loanTakenRepository = mock(LoanTakenRepository.class);
        DebtRepository debtRepository = mock(DebtRepository.class);
        MarkPaidRepository markPaidRepository = mock(MarkPaidRepository.class);
        InvestmentRepository investmentRepository = mock(InvestmentRepository.class);
        EmergencyRepository emergencyRepository = mock(EmergencyRepository.class);
        SettingsService settingsService = mock(SettingsService.class);
        WalletCheckInService walletCheckInService = mock(WalletCheckInService.class);
        MonthCloseService monthCloseService = mock(MonthCloseService.class);

        Settings settings = new Settings();
        settings.setMonthlyStableIncome(new BigDecimal("7000000"));
        settings.setMonthlyStableIncomeCurrency(Currency.UZS);
        settings.setAllocationTrackingStartMonth(SEP_1);
        when(settingsService.getOrCreate()).thenReturn(settings);

        // ── Bills, both paid through Pay in September ──
        MonthlyPayment rent = bill(1L, "Kvartira Arenda", "4200000", 10);
        MonthlyPayment noon = bill(2L, "Noon", "1100000", 9);
        when(monthlyPaymentRepository.findAll()).thenReturn(List.of(rent, noon));

        // ── The bank loan, September marked as already paid ──
        BankLoan bank = new BankLoan();
        bank.setId(5L);
        bank.setBankName("Kapitalbank");
        bank.setLoanName("Talim kredit");
        bank.setTotalAmount(new BigDecimal("4800000"));
        bank.setMonthlyPayment(new BigDecimal("400000"));
        bank.setCurrency(Currency.UZS);
        bank.setTakenDate(LocalDate.of(2026, 1, 1));
        when(bankLoanRepository.findAll()).thenReturn(List.of(bank));
        MarkPaid bankMark = new MarkPaid();
        bankMark.setKind("BANK");
        bankMark.setRefId(5L);
        bankMark.setMonth(SEP_1);
        bankMark.setAmount(new BigDecimal("400000"));
        bankMark.setCurrency(Currency.UZS);
        when(markPaidRepository.findByMonth(SEP_1)).thenReturn(List.of(bankMark));
        when(markPaidRepository.findByKindAndRefIdAndMonth(any(), any(), any())).thenReturn(List.of());

        // ── The parents: 50M borrowed, 500K a month from 1 October. The legacy monthlyPayment
        //    column holds the whole 50M; the plan is what must be asked. ──
        LoanTaken parents = new LoanTaken();
        parents.setId(7L);
        parents.setLenderName("Ota-onam");
        parents.setTotalAmount(new BigDecimal("50000000"));
        parents.setPaidAmount(BigDecimal.ZERO);
        parents.setMonthlyPayment(new BigDecimal("50000000"));
        parents.setPlannedMonthlyPayment(new BigDecimal("500000"));
        parents.setPaymentStartDate(LocalDate.of(2026, 10, 1));
        parents.setCurrency(Currency.UZS);
        parents.setStatus(RecordStatus.PENDING);
        when(loanTakenRepository.findAll()).thenReturn(List.of(parents));
        when(debtRepository.findAll()).thenReturn(List.of());

        // ── September's transactions ──
        ledger = new TransactionLedger(transactionRepository);
        salary = TransactionLedger.category("Salary", false, null);
        Category avans = TransactionLedger.category("Avans", false, salary);
        Category other = TransactionLedger.category("Other income", false, null);
        Category bonus = TransactionLedger.category("Bonus", true, salary);   // Salary → {Avans, Bonus}
        salary.setId(11L);
        avans.setId(12L);
        other.setId(13L);
        bonus.setId(14L);
        ledger.income(LocalDate.of(2026, 9, 7), "5889000", salary);
        ledger.income(LocalDate.of(2026, 9, 15), "2000000", avans);
        ledger.income(LocalDate.of(2026, 9, 18), "100000", other);
        ledger.income(LocalDate.of(2026, 9, 18), "16380000", bonus);
        ledger.billPaid(LocalDate.of(2026, 9, 8), "4200000", 1L);
        ledger.billPaid(LocalDate.of(2026, 9, 9), "1100000", 2L);
        ledger.add(LocalDate.of(2026, 9, 10), TransactionType.EXPENSE, TransactionSubType.EMERGENCY_CONTRIBUTION, "353600");
        Transaction invested = ledger.add(LocalDate.of(2026, 9, 19), TransactionType.EXPENSE,
                TransactionSubType.INVESTMENT, "1414400");
        invested.setAllocationBucket(AllocationBucket.INVESTMENTS);
        // A cash withdrawal: card → cash, a transfer pair.
        Transaction out = ledger.add(LocalDate.of(2026, 9, 15), TransactionType.EXPENSE, TransactionSubType.TRANSFER_OUT, "1000000");
        Transaction in = ledger.add(LocalDate.of(2026, 9, 15), TransactionType.INCOME, TransactionSubType.TRANSFER_IN, "1000000");
        out.setTransferPairId(out.getId());
        in.setTransferPairId(out.getId());
        // Everyday spending, 12,098,000 in all: two recorded, two found by wallet check-ins.
        ledger.expense(LocalDate.of(2026, 9, 12), "2500000");
        ledger.add(LocalDate.of(2026, 9, 16), TransactionType.EXPENSE, TransactionSubType.EVERYDAY_SPENDING, "3000000");
        ledger.expense(LocalDate.of(2026, 9, 20), "4598000");
        ledger.add(LocalDate.of(2026, 9, 22), TransactionType.EXPENSE, TransactionSubType.EVERYDAY_SPENDING, "2000000");

        // ── Wallets: 9,687,000, checked yesterday ──
        when(walletCheckInService.status(any())).thenReturn(WalletCheckInStatusResponse.builder()
                .date(TODAY).due(false).daysSinceLastReconciled(1).lastReconciledOn(TODAY.minusDays(1))
                .wallets(List.of(
                        WalletLine.builder().walletType("CARD").cardId(3L).label("Uzcard")
                                .currency(Currency.UZS).computedBalance(new BigDecimal("8687000")).build(),
                        WalletLine.builder().walletType("CASH").label("Cash")
                                .currency(Currency.UZS).computedBalance(new BigDecimal("1000000")).build()))
                .build());
        when(monthCloseService.latestClosedMonth()).thenReturn(null);
        when(investmentRepository.findAll()).thenReturn(List.of());
        when(emergencyRepository.count()).thenReturn(1L);

        overview = new OverviewService(transactionRepository, monthlyPaymentRepository,
                bankLoanRepository, loanTakenRepository, debtRepository, mock(DonationRepository.class),
                investmentRepository, mock(LevelAllocationRuleRepository.class), mock(LevelConfigRepository.class),
                markPaidRepository, settingsService, categories(salary, avans, other, bonus));
        DailyAdviceService daily = new DailyAdviceService(overview, settingsService, transactionRepository,
                monthlyPaymentRepository, bankLoanRepository, loanTakenRepository, debtRepository);
        advisor = new AdvisorService(overview, walletCheckInService, monthCloseService, transactionRepository,
                mock(LoanGivenRepository.class), investmentRepository, emergencyRepository, daily);
    }

    /** The owner's categories: the salary tree is Salary with its Avans and Bonus; Other income stands apart. */
    private static CategoryRepository categories(Category... all) {
        CategoryRepository repo = mock(CategoryRepository.class);
        when(repo.findAll()).thenReturn(List.of(all));
        return repo;
    }

    private static MonthlyPayment bill(Long id, String name, String amount, int dueDay) {
        MonthlyPayment m = new MonthlyPayment();
        m.setId(id);
        m.setName(name);
        m.setAmount(new BigDecimal(amount));
        m.setCurrency(Currency.UZS);
        m.setDueDay(dueDay);
        m.setActive(true);
        return m;
    }

    @Test
    void theMonthScopedFiguresAreWhatHomeShowedThatDay() {
        AdvisorResponse r = advisor.advise(TODAY);

        assertThat(r.getHave()).isEqualByComparingTo("9687000");
        assertThat(r.getSalaryReceived()).isEqualByComparingTo("7989000");
        assertThat(r.getSalaryComing()).isEqualByComparingTo("0");
        assertThat(r.getBonusReceived()).isEqualByComparingTo("16380000");
        assertThat(r.getBillsLeft()).isEqualByComparingTo("0");
        // The targets are 5 / 2 / 8 % of what the owner earned (2026-09-23): the 7,889,000 salary
        // (above the 7M in Settings) + the 16,380,000 bonus = 24,269,000. Still to set aside:
        // 1,213,450 + (485,380 − 353,600) + (1,941,520 − 1,414,400).
        assertThat(r.getSetAsideLeft()).isEqualByComparingTo("1872350");
        // The month-only figure the bot shows: 9,687,000 − 1,872,350.
        assertThat(r.getFree()).isEqualByComparingTo("7814650");
    }

    @Test
    void theDailyAnswerSeesTheRentAfterNextPayday() {
        Daily d = advisor.advise(TODAY).getDaily();

        assertThat(d.getUntil()).isEqualTo(LocalDate.of(2026, 11, 6));
        // 5,889,000 on the 7th and 2,000,000 on the 15th scaled to the 7M of Settings; the 100,000
        // "Other income" is too small to be a salary part and the bonus is not salary.
        assertThat(d.getIncomes())
                .extracting(IncomePart::getDate, i -> i.getAmount().toPlainString())
                .containsExactly(
                        tuple(LocalDate.of(2026, 10, 7), "5225000"),
                        tuple(LocalDate.of(2026, 10, 15), "1775000"));
        assertThat(d.getUpcoming())
                .extracting(Upcoming::getDate, Upcoming::getKind, Upcoming::getRefId, Upcoming::getName,
                        u -> u.getAmount().stripTrailingZeros().toPlainString(), Upcoming::isOverdue)
                .containsExactly(
                        tuple(LocalDate.of(2026, 10, 1), "LOAN", 7L, "Ota-onam", "500000", false),
                        tuple(LocalDate.of(2026, 10, 1), "BANK", 5L, "Kapitalbank · Talim kredit", "400000", false),
                        tuple(LocalDate.of(2026, 10, 9), "BILL", 2L, "Noon", "1100000", false),
                        tuple(LocalDate.of(2026, 10, 10), "BILL", 1L, "Kvartira Arenda", "4200000", false));

        // By 6 November: + 7,000,000 salary − (1,800,000 bank and parents ×2 + 5,300,000 bills)
        // − (1,872,350 still to set aside this month, now + 700,000 on 7 October: October's rule —
        // bank AND debts, 5 / 0 / 5 % — of its projected 7M salary, set aside on payday out of it).
        // November's belongs to 7 November's salary, and both are past the horizon.
        assertThat(d.getTightestOn()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(d.getBreakdown().getHave()).isEqualByComparingTo("9687000");
        assertThat(d.getBreakdown().getComingIn()).isEqualByComparingTo("7000000");
        assertThat(d.getBreakdown().getGoingOut()).isEqualByComparingTo("7100000");
        assertThat(d.getBreakdown().getSavings()).isEqualByComparingTo("2572350");
        assertThat(d.getBreakdown().getNet()).isEqualByComparingTo("7014650");
        assertThat(d.getBreakdown().getDays()).isEqualTo(45);
        // 7,014,650 ÷ 45 = 155,881 → 155,000 a day (190,000 on the old, left-over base).
        assertThat(d.getSafePerDay()).isEqualByComparingTo("155000");
        assertThat(d.getShortBy()).isNull();

        // 12,098,000 of everyday spending over 1–23 September.
        assertThat(d.getPaceDaily()).isEqualByComparingTo("526000");
        assertThat(d.getPaceFrom()).isEqualTo(SEP_1);
        assertThat(d.getPaceTo()).isEqualTo(TODAY);
        // At 526,000 a day, with this month's savings set aside, the money runs out on 6 October —
        // the eve of payday (10 October on the old base).
        assertThat(d.getRunsOutOn()).isEqualTo(LocalDate.of(2026, 10, 6));
    }

    /**
     * 8 October: the 7th's 5,889,000 is in, the 15th's part not yet. The salary's shape still comes
     * from September — a complete month — so the 15th is expected this month AND next. This month it
     * brings only what is left of the 7M in Settings (the 7th alone brought more than its share).
     * Taking October's own shape, a month in progress, had dropped 15 November and put the missing
     * 1,111,000 on 31 October.
     */
    @Test
    void onTheEighthOfOctoberThe15thIsStillExpectedThisMonthAndNext() {
        ledger.income(LocalDate.of(2026, 10, 7), "5889000", salary);

        AdvisorResponse r = advisor.advise(LocalDate.of(2026, 10, 8));

        assertThat(r.getSalaryComing()).isEqualByComparingTo("1111000");
        assertThat(r.getDaily().getUntil()).isEqualTo(LocalDate.of(2026, 12, 6));
        assertThat(r.getDaily().getIncomes())
                .extracting(IncomePart::getDate, i -> i.getAmount().toPlainString())
                .containsExactly(
                        tuple(LocalDate.of(2026, 10, 15), "1111000"),
                        tuple(LocalDate.of(2026, 11, 7), "5225000"),
                        tuple(LocalDate.of(2026, 11, 15), "1775000"));
    }

    @Test
    void theWarningStaysInDailyAndInvestingTheRentIsNoLongerSuggested() {
        AdvisorResponse r = advisor.advise(TODAY);

        // The heads-up is the web's to show (daily.runsOutOn); the bot, kept as it is, gets no
        // sentence it has no translation for.
        assertThat(r.getDaily().getRunsOutOn()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(r.getSuggestions()).extracting(Suggestion::getKind).doesNotContain("WARN");
        assertThat(r.getSuggestions()).extracting(Suggestion::getCode)
                .doesNotContain("advisor.s.extraToInvestments", "advisor.s.extraToEmergency",
                        "advisor.s.extraToGoal", "advisor.s.short", "advisor.s.paceWarning")
                .contains("advisor.s.setAside");
    }

    /** 5 / 2 / 8 % of 24,269,000 against what went in: the emergency and investment money is short now. */
    @Test
    void savingsThisMonthAreThePercentagesOfWhatTheOwnerEarned() {
        AdvisorResponse r = advisor.advise(TODAY);

        assertThat(r.getSetAside()).extracting(AdvisorResponse.SetAside::getBucket)
                .containsExactly("DONATION", "EMERGENCY", "INVESTMENTS");
        assertThat(r.getSavingsThisMonth())
                .extracting(SavingsRow::getBucket, s -> s.getPercent().toPlainString(),
                        s -> s.getTarget().stripTrailingZeros().toPlainString(),
                        s -> s.getPaid().stripTrailingZeros().toPlainString(),
                        s -> s.getRemaining().stripTrailingZeros().toPlainString())
                .containsExactly(
                        tuple("DONATION", "5", "1213450", "0", "1213450"),
                        tuple("EMERGENCY", "2", "485380", "353600", "131780"),
                        tuple("INVESTMENTS", "8", "1941520", "1414400", "527120"));
        assertThat(YearMonth.from(r.getDate())).isEqualTo(YearMonth.of(2026, 9));
    }

    // ── The profile ───────────────────────────────────────────────────────────

    /**
     * The Profile page's answer, exactly as the web receives it. Level 1 (not "1.2") from 7M − 5.3M
     * bills = 1.7M; with the bank's 400,000 the bank-loan-only rule is tight under the 5M cutoff, so
     * 5 / 2 / 8 % — of what the owner earned: the salary tree's 7,889,000 (Salary + Avans, above
     * the 7M in Settings) + the 16,380,000 bonus = 24,269,000; of 7,889,000 in a month without a
     * bonus. "Other income" is outside the salary tree. From October the parents' 500,000 plan
     * starts: bank AND debts, 5 / 0 / 5 % of the 7M stable income until the salary is in.
     *
     * <p>Income so far is earned money only — 24,369,000. Borrowed money (the real September's two
     * Uzum loans) and a loan paid back are named beside it; a check-in's surplus and the cash
     * withdrawal (a transfer) are left out. Set aside: 1,768,000 — 7.3% of it, and of the base.
     */
    @Test
    void theProfileExplainsTheLevelAndWhereEachPercentComesFrom() throws Exception {
        ledger.add(LocalDate.of(2026, 9, 14), TransactionType.INCOME, TransactionSubType.LOAN_RECEIVED, "1155000")
                .setDescription("Uzum Bank");
        ledger.add(LocalDate.of(2026, 9, 14), TransactionType.INCOME, TransactionSubType.LOAN_RECEIVED, "800000")
                .setDescription("Uzum Nasiya");
        ledger.add(LocalDate.of(2026, 9, 20), TransactionType.INCOME, TransactionSubType.LOAN_RETURNED_TO_ME, "300000");
        ledger.add(LocalDate.of(2026, 9, 22), TransactionType.INCOME, TransactionSubType.EVERYDAY_SPENDING, "120000");

        ProfileResponse profile = new ProfileService(overview, transactionRepository).profile(TODAY, "owner");

        String json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(profile);
        org.skyscreamer.jsonassert.JSONAssert.assertEquals("""
                {"username":"owner","month":"2026-09","missingStableIncome":false,
                 "level":1,"aboveCeiling":false,"levelFrom":0,"nextLevelAt":15000000,
                 "stableIncome":7000000,"monthlyBills":5300000,"leftAfterBills":1700000,
                 "loanPayments":400000,"leftForSavings":1300000,"bonusThisMonth":16380000,"savingsBase":24269000,
                 "baseParts":{"salaryReceived":7889000,"stableIncome":7000000,"usesStableIncome":false,
                   "bonus":16380000,"lines":[
                     {"categoryId":14,"name":"Bonus","nameUz":null,"amount":16380000},
                     {"categoryId":11,"name":"Salary","nameUz":null,"amount":5889000},
                     {"categoryId":12,"name":"Avans","nameUz":null,"amount":2000000}]},
                 "rule":{"reason":"BANK_LOAN_TIGHT","cutoff":5000000},
                 "buckets":[
                   {"bucket":"DONATION","percent":5,"amount":1213450,"normalMonthAmount":394450},
                   {"bucket":"EMERGENCY","percent":2,"amount":485380,"normalMonthAmount":157780},
                   {"bucket":"INVESTMENTS","percent":8,"amount":1941520,"normalMonthAmount":631120}],
                 "totalPercent":15,"totalAmount":3640350,"normalMonthTotal":1183350,
                 "nextMonth":{"month":"2026-10","reason":"BANK_AND_DEBTS","loanPayments":900000,
                   "leftForSavings":800000,"buckets":[
                     {"bucket":"DONATION","percent":5,"normalMonthAmount":350000},
                     {"bucket":"EMERGENCY","percent":0,"normalMonthAmount":0},
                     {"bucket":"INVESTMENTS","percent":5,"normalMonthAmount":350000}]},
                 "incomeThisMonth":{"total":24369000,"lines":[
                     {"categoryId":14,"name":"Bonus","nameUz":null,"amount":16380000,"inBase":true},
                     {"categoryId":11,"name":"Salary","nameUz":null,"amount":5889000,"inBase":true},
                     {"categoryId":12,"name":"Avans","nameUz":null,"amount":2000000,"inBase":true},
                     {"categoryId":13,"name":"Other income","nameUz":null,"amount":100000,"inBase":false}],
                   "excludedBorrowed":1955000,"excludedReturned":300000},
                 "allocatedThisMonth":{"total":1768000,"percentOfIncome":7.3,"percentOfBase":7.3,"lines":[
                     {"bucket":"DONATION","amount":0,"percentOfIncome":0.0,"percentOfBase":0.0,
                      "target":1213450,"over":0},
                     {"bucket":"EMERGENCY","amount":353600,"percentOfIncome":1.5,"percentOfBase":1.5,
                      "target":485380,"over":0},
                     {"bucket":"INVESTMENTS","amount":1414400,"percentOfIncome":5.8,"percentOfBase":5.8,
                      "target":1941520,"over":0}]}}
                """, json, org.skyscreamer.jsonassert.JSONCompareMode.STRICT);
    }

    /** Each bucket's amount is the very figure the advisor asks for this month, and paid what it calls paid. */
    @Test
    void theProfilesAmountsAreTheAdvisorsTargets() {
        ProfileResponse profile = new ProfileService(overview, transactionRepository).profile(TODAY, "owner");
        AdvisorResponse advice = advisor.advise(TODAY);

        for (ProfileResponse.Bucket b : profile.getBuckets()) {
            SavingsRow row = advice.getSavingsThisMonth().stream()
                    .filter(s -> b.getBucket().equals(s.getBucket())).findFirst().orElseThrow();
            assertThat(b.getAmount()).as(b.getBucket()).isEqualByComparingTo(row.getTarget());
        }
        // And what has been set aside is exactly what the advisor calls paid.
        for (ProfileResponse.AllocatedLine a : profile.getAllocatedThisMonth().getLines()) {
            SavingsRow row = advice.getSavingsThisMonth().stream()
                    .filter(s -> a.getBucket().equals(s.getBucket())).findFirst().orElseThrow();
            assertThat(a.getAmount()).as(a.getBucket()).isEqualByComparingTo(row.getPaid());
            assertThat(a.getTarget()).as(a.getBucket()).isEqualByComparingTo(row.getTarget());
        }
    }
}
