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

    @BeforeEach
    void setUp() {
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
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
        Category bonus = TransactionLedger.category("Bonus", true, null);
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

        OverviewService overview = new OverviewService(transactionRepository, monthlyPaymentRepository,
                bankLoanRepository, loanTakenRepository, debtRepository, mock(DonationRepository.class),
                investmentRepository, mock(LevelAllocationRuleRepository.class), mock(LevelConfigRepository.class),
                markPaidRepository, settingsService);
        DailyAdviceService daily = new DailyAdviceService(overview, settingsService, transactionRepository,
                monthlyPaymentRepository, bankLoanRepository, loanTakenRepository, debtRepository);
        advisor = new AdvisorService(overview, walletCheckInService, monthCloseService, transactionRepository,
                mock(LoanGivenRepository.class), investmentRepository, emergencyRepository, daily);
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
        assertThat(r.getSetAsideLeft()).isEqualByComparingTo("884000");
        // Unchanged for the bot: 9,687,000 − 884,000.
        assertThat(r.getFree()).isEqualByComparingTo("8803000");
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
        // − (884,000 donation now + 120,000 on 7 October: 15% of 7M − 5.3M − 0.4M − 0.5M, set aside
        // on payday out of the salary that funds it). November's 120,000 belongs to 7 November's
        // salary, and both are past the horizon.
        assertThat(d.getTightestOn()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(d.getBreakdown().getHave()).isEqualByComparingTo("9687000");
        assertThat(d.getBreakdown().getComingIn()).isEqualByComparingTo("7000000");
        assertThat(d.getBreakdown().getGoingOut()).isEqualByComparingTo("7100000");
        assertThat(d.getBreakdown().getSavings()).isEqualByComparingTo("1004000");
        assertThat(d.getBreakdown().getNet()).isEqualByComparingTo("8583000");
        assertThat(d.getBreakdown().getDays()).isEqualTo(45);
        // 8,583,000 ÷ 45 = 190,733 → 190,000 a day.
        assertThat(d.getSafePerDay()).isEqualByComparingTo("190000");
        assertThat(d.getShortBy()).isNull();

        // 12,098,000 of everyday spending over 1–23 September.
        assertThat(d.getPaceDaily()).isEqualByComparingTo("526000");
        assertThat(d.getPaceFrom()).isEqualTo(SEP_1);
        assertThat(d.getPaceTo()).isEqualTo(TODAY);
        // At 526,000 a day the rent on 10 October cannot be paid.
        assertThat(d.getRunsOutOn()).isEqualTo(LocalDate.of(2026, 10, 10));
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
        assertThat(r.getDaily().getRunsOutOn()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(r.getSuggestions()).extracting(Suggestion::getKind).doesNotContain("WARN");
        assertThat(r.getSuggestions()).extracting(Suggestion::getCode)
                .doesNotContain("advisor.s.extraToInvestments", "advisor.s.extraToEmergency",
                        "advisor.s.extraToGoal", "advisor.s.short", "advisor.s.paceWarning")
                .contains("advisor.s.setAside");
    }

    @Test
    void savingsThisMonthListsTheBucketsAlreadyMetToo() {
        AdvisorResponse r = advisor.advise(TODAY);

        assertThat(r.getSetAside()).extracting(AdvisorResponse.SetAside::getBucket).containsExactly("DONATION");
        assertThat(r.getSavingsThisMonth())
                .extracting(SavingsRow::getBucket, s -> s.getPercent().toPlainString(),
                        s -> s.getTarget().stripTrailingZeros().toPlainString(),
                        s -> s.getPaid().stripTrailingZeros().toPlainString(),
                        s -> s.getRemaining().stripTrailingZeros().toPlainString())
                .containsExactly(
                        tuple("DONATION", "5", "884000", "0", "884000"),
                        tuple("EMERGENCY", "2", "353600", "353600", "0"),
                        tuple("INVESTMENTS", "8", "1414400", "1414400", "0"));
        assertThat(YearMonth.from(r.getDate())).isEqualTo(YearMonth.of(2026, 9));
    }
}
