package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.AdvisorResponse;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.Daily;
import uz.tracker.trackerproject.dto.response.AdvisorResponse.IncomePart;
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
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The five findings of the 2026-09-23 review of the per-day advice, each rebuilt from the
 * reviewer's probe and pinned to the corrected answer. Everything real runs — the Plan, the walk
 * and the advisor — over mocked repositories fed by one ledger.
 */
class DailyAdviceReviewRegressionTest {

    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEP_23 = LocalDate.of(2026, 9, 23);

    private final List<MarkPaid> marks = new ArrayList<>();
    private final List<MonthlyPayment> bills = new ArrayList<>();
    private final List<BankLoan> banks = new ArrayList<>();
    private final List<LoanTaken> loans = new ArrayList<>();
    private final Category salary = TransactionLedger.category("Salary", false, null);
    private TransactionRepository transactionRepository;
    private TransactionLedger ledger;
    private Settings settings;
    private OverviewService overview;
    private DailyAdviceService daily;
    private WalletCheckInService walletCheckInService;
    private MonthCloseService monthCloseService;
    private InvestmentRepository investmentRepository;
    private EmergencyRepository emergencyRepository;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        MonthlyPaymentRepository monthlyPaymentRepository = mock(MonthlyPaymentRepository.class);
        BankLoanRepository bankLoanRepository = mock(BankLoanRepository.class);
        LoanTakenRepository loanTakenRepository = mock(LoanTakenRepository.class);
        DebtRepository debtRepository = mock(DebtRepository.class);
        MarkPaidRepository markPaidRepository = mock(MarkPaidRepository.class);
        investmentRepository = mock(InvestmentRepository.class);
        emergencyRepository = mock(EmergencyRepository.class);
        SettingsService settingsService = mock(SettingsService.class);
        walletCheckInService = mock(WalletCheckInService.class);
        monthCloseService = mock(MonthCloseService.class);

        settings = new Settings();
        settings.setMonthlyStableIncome(new BigDecimal("7000000"));
        settings.setMonthlyStableIncomeCurrency(Currency.UZS);
        settings.setAllocationTrackingStartMonth(SEP_1);
        when(settingsService.getOrCreate()).thenReturn(settings);

        when(monthlyPaymentRepository.findAll()).thenReturn(bills);
        when(bankLoanRepository.findAll()).thenReturn(banks);
        when(loanTakenRepository.findAll()).thenReturn(loans);
        when(debtRepository.findAll()).thenReturn(List.of());
        when(markPaidRepository.findByMonth(any())).thenAnswer(inv -> marks.stream()
                .filter(m -> m.getMonth().equals(inv.getArgument(0))).toList());
        when(markPaidRepository.findByKindAndRefIdAndMonth(any(), any(), any())).thenReturn(List.of());
        when(monthCloseService.latestClosedMonth()).thenReturn(null);
        when(investmentRepository.findAll()).thenReturn(List.of());
        when(emergencyRepository.count()).thenReturn(1L);

        ledger = new TransactionLedger(transactionRepository);
        overview = new OverviewService(transactionRepository, monthlyPaymentRepository,
                bankLoanRepository, loanTakenRepository, debtRepository, mock(DonationRepository.class),
                investmentRepository, mock(LevelAllocationRuleRepository.class), mock(LevelConfigRepository.class),
                markPaidRepository, settingsService, mock(CategoryRepository.class));
        daily = new DailyAdviceService(overview, settingsService, transactionRepository,
                monthlyPaymentRepository, bankLoanRepository, loanTakenRepository, debtRepository);
    }

    private MonthlyPayment bill(long id, String name, String amount, int dueDay) {
        MonthlyPayment m = new MonthlyPayment();
        m.setId(id);
        m.setName(name);
        m.setAmount(new BigDecimal(amount));
        m.setCurrency(Currency.UZS);
        m.setDueDay(dueDay);
        m.setActive(true);
        bills.add(m);
        return m;
    }

    private void wallets(LocalDate today, String have) {
        when(walletCheckInService.status(any())).thenReturn(WalletCheckInStatusResponse.builder()
                .date(today).due(false).daysSinceLastReconciled(1).lastReconciledOn(today.minusDays(1))
                .wallets(List.of(WalletLine.builder().walletType("CARD").cardId(3L).label("Uzcard")
                        .currency(Currency.UZS).computedBalance(new BigDecimal(have)).build()))
                .build());
    }

    private AdvisorService advisor() {
        return new AdvisorService(overview, walletCheckInService, monthCloseService, transactionRepository,
                mock(LoanGivenRepository.class), investmentRepository, emergencyRepository, daily);
    }

    private DailyAdviceService.Result walk(LocalDate today, String have, String stable, String salaryComing,
                                           String setAsideLeft) {
        return daily.compute(new DailyAdviceService.Inputs(today, new BigDecimal(have), new BigDecimal(stable),
                new BigDecimal(salaryComing), new BigDecimal(setAsideLeft)));
    }

    // ── 1. HIGH: no "spare money" while the walk runs short before payday ────────

    /**
     * 500,000 left, the salary (14M) in on the 7th of each month and the 2M rent due on the 5th:
     * October's rent comes two days before October's salary. Measured on the horizon's last day
     * alone, the money looked spare and "put it into investments?" was offered — more than the
     * wallets held. Measured on every day it is 1,780,000 short of the owner's pace on 6 October.
     */
    @Test
    void noSpareMoneyIdeaWhileTheWalkRunsShortBeforePayday() {
        settings.setMonthlyStableIncome(new BigDecimal("14000000"));
        bill(1, "Rent", "2000000", 5);
        ledger.billPaid(LocalDate.of(2026, 9, 5), "2000000", 1);   // September's rent paid
        ledger.income(LocalDate.of(2026, 9, 7), "14000000", salary); // the salary is in
        ledger.expense(LocalDate.of(2026, 9, 12), "460000");         // 20,000 a day
        wallets(SEP_23, "500000");
        // September's set-asides are done (10/5/15 % of the 14M salary), as "already paid" marks.
        for (String[] b : new String[][]{{"DONATION", "1400000"}, {"EMERGENCY", "700000"}, {"INVESTMENTS", "2100000"}}) {
            MarkPaid m = new MarkPaid();
            m.setKind("BUCKET");
            m.setBucket(b[0]);
            m.setMonth(SEP_1);
            m.setAmount(new BigDecimal(b[1]));
            m.setCurrency(Currency.UZS);
            marks.add(m);
        }

        AdvisorResponse r = advisor().advise(SEP_23);

        Daily d = r.getDaily();
        assertThat(d.getShortBy().getDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(d.getShortBy().getAmount()).isEqualByComparingTo("1500000");
        assertThat(d.getRunsOutOn()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(r.getSuggestions()).extracting(Suggestion::getCode)
                .noneMatch(code -> code.startsWith("advisor.s.extraTo"));
        // The surplus is the least left over at the pace on ANY day: −1.5M − 14 × 20,000 on the 6th.
        assertThat(walk(SEP_23, "500000", "14000000", "0", "0").surplus()).isEqualByComparingTo("-1780000");
    }

    // ── 2. MEDIUM: a part that arrived is not expected again ─────────────────────

    /**
     * Parts: 3.9M on the 7th and 4.1M on the 15th. On 7 October the 3.9M came in that morning. The
     * walk used to book another 3.9M "today" out of the 4.1M still coming — and the rent on the 10th
     * then looked payable. Only the 15th's 4.1M is still to come.
     */
    @Test
    void aPartThatArrivedThisMorningIsNotExpectedAgain() {
        settings.setMonthlyStableIncome(new BigDecimal("8000000"));
        ledger.income(LocalDate.of(2026, 9, 7), "3900000", salary);
        ledger.income(LocalDate.of(2026, 9, 15), "4100000", salary);
        ledger.income(LocalDate.of(2026, 10, 7), "3900000", salary);
        bill(1, "Rent", "4200000", 10);

        Daily payday = walk(LocalDate.of(2026, 10, 7), "4400000", "8000000", "4100000", "0").daily();

        assertThat(payday.getIncomes())
                .extracting(IncomePart::getDate, i -> i.getAmount().toPlainString())
                .containsExactly(
                        tuple(LocalDate.of(2026, 10, 15), "4100000"),
                        tuple(LocalDate.of(2026, 11, 7), "3900000"));
        // 4.4M − the 4.2M rent on the 10th leaves 200,000 for the 8 days to the 15th.
        assertThat(payday.getSafePerDay()).isEqualByComparingTo("25000");
        assertThat(payday.getTightestOn()).isEqualTo(LocalDate.of(2026, 10, 14));
        // The same money a day later says the same thing (it used to drop from 102,000 to 28,000).
        Daily next = walk(LocalDate.of(2026, 10, 8), "4400000", "8000000", "4100000", "0").daily();
        assertThat(next.getSafePerDay()).isEqualByComparingTo("28000");
    }

    // ── 5. LOW: the shape comes from the last complete month ─────────────────────

    /**
     * The owner's 8 October: the 7th's 5,889,000 is in, the 15th not yet. October passes half the
     * stable income with its first part; taken as the shape it dropped the 15th from November and
     * put the missing 1,111,000 on 31 October. September — complete — is the shape: the 15th is
     * expected in October (what is left of the 7M) and in November.
     */
    @Test
    void aMonthInProgressDoesNotReplaceTheShapeOfTheLastCompleteOne() {
        ledger.income(LocalDate.of(2026, 9, 7), "5889000", salary);
        ledger.income(LocalDate.of(2026, 9, 15), "2000000", salary);
        ledger.income(LocalDate.of(2026, 10, 7), "5889000", salary);

        Daily d = walk(LocalDate.of(2026, 10, 8), "6000000", "7000000", "1111000", "0").daily();

        assertThat(d.getUntil()).isEqualTo(LocalDate.of(2026, 12, 6));
        assertThat(d.getIncomes())
                .extracting(IncomePart::getDate, i -> i.getAmount().toPlainString())
                .containsExactly(
                        tuple(LocalDate.of(2026, 10, 15), "1111000"),
                        tuple(LocalDate.of(2026, 11, 7), "5225000"),
                        tuple(LocalDate.of(2026, 11, 15), "1775000"));
    }

    // ── 3. MEDIUM: paid means paid by today ──────────────────────────────────────

    /**
     * The rent recorded today but dated the 28th: the wallets (as of today) still hold the 4.2M, so
     * the rent is still to pay — on the 28th. It used to vanish from the list and the figure rose
     * to 173,000 a day. Paid on the 28th or today, the answer is the same: 5M − 4.2M + 7M − October's
     * 30% (2.1M) − October's rent over the 45 days to 6 November = 33,000 a day.
     */
    @Test
    void aBillPaidWithALaterDateIsStillToPayOnThatDay() {
        ledger.income(LocalDate.of(2026, 9, 7), "7000000", salary);
        bill(1, "Rent", "4200000", 28);
        ledger.billPaid(LocalDate.of(2026, 9, 28), "4200000", 1);

        Daily d = walk(SEP_23, "5000000", "7000000", "0", "0").daily();

        assertThat(d.getUpcoming())
                .extracting(Upcoming::getDate, Upcoming::getKind, Upcoming::getRefId,
                        u -> u.getAmount().toPlainString(), Upcoming::isOverdue)
                .contains(tuple(LocalDate.of(2026, 9, 28), "BILL", 1L, "4200000", false));
        assertThat(d.getSafePerDay()).isEqualByComparingTo("33000");
    }

    /**
     * The rent already recorded for the 28th is money leaving that day, so it is listed — as
     * recorded, which Home shows without a Pay button (paying it would record the rent twice). The
     * internet bill, still unpaid, is a due to pay, and so is next month's.
     */
    @Test
    void aPaymentRecordedAheadIsListedAsRecordedAndADueAsOwed() {
        ledger.income(LocalDate.of(2026, 9, 7), "7000000", salary);
        bill(1, "Rent", "4200000", 28);
        bill(2, "Internet", "200000", 25);
        ledger.billPaid(LocalDate.of(2026, 9, 28), "4200000", 1);

        Daily d = walk(SEP_23, "5000000", "7000000", "0", "0").daily();

        assertThat(d.getUpcoming())
                .extracting(Upcoming::getDate, Upcoming::getName, Upcoming::isRecorded)
                .containsExactly(
                        tuple(LocalDate.of(2026, 9, 25), "Internet", false),
                        tuple(LocalDate.of(2026, 9, 28), "Rent", true),
                        tuple(LocalDate.of(2026, 10, 25), "Internet", false));
    }

    /** The same rent dated today, the wallets already 4.2M lighter: the same answer. */
    @Test
    void theSameBillPaidTodayGivesTheSameFigure() {
        ledger.income(LocalDate.of(2026, 9, 7), "7000000", salary);
        bill(1, "Rent", "4200000", 28);
        ledger.billPaid(SEP_23, "4200000", 1);

        Daily d = walk(SEP_23, "800000", "7000000", "0", "0").daily();

        assertThat(d.getUpcoming()).noneMatch(u -> u.getDate().getMonthValue() == 9);
        assertThat(d.getSafePerDay()).isEqualByComparingTo("33000");
    }

    /**
     * The mirror: the 15th's 4.1M entered ahead, dated the 15th. Settings no longer counts it as
     * coming, and the wallets do not hold it yet — it is income on the 15th, once.
     */
    @Test
    void aSalaryRecordedForALaterDayIsIncomeOnThatDayOnce() {
        settings.setMonthlyStableIncome(new BigDecimal("8000000"));
        ledger.income(LocalDate.of(2026, 9, 7), "3900000", salary);
        ledger.income(LocalDate.of(2026, 9, 15), "4100000", salary);
        ledger.income(LocalDate.of(2026, 10, 7), "3900000", salary);
        ledger.income(LocalDate.of(2026, 10, 15), "4100000", salary);

        Daily d = walk(LocalDate.of(2026, 10, 10), "1000000", "8000000", "0", "0").daily();

        assertThat(d.getIncomes())
                .extracting(IncomePart::getDate, i -> i.getAmount().toPlainString())
                .containsExactly(
                        tuple(LocalDate.of(2026, 10, 15), "4100000"),
                        tuple(LocalDate.of(2026, 11, 7), "3900000"));
    }

    /**
     * A bank installment and a loan repayment entered ahead, for the 27th and the 28th: each is paid
     * on its own day, not "overdue today" — and the loan's next months still see what is left.
     */
    @Test
    void installmentsAndRepaymentsRecordedAheadArePaidOnTheirOwnDays() {
        ledger.income(LocalDate.of(2026, 9, 7), "7000000", salary);
        BankLoan bank = new BankLoan();
        bank.setId(5L);
        bank.setBankName("Kapitalbank");
        bank.setMonthlyPayment(new BigDecimal("400000"));
        bank.setTotalAmount(new BigDecimal("4800000"));
        bank.setCurrency(Currency.UZS);
        bank.setTakenDate(LocalDate.of(2026, 1, 5));
        banks.add(bank);
        ledger.add(LocalDate.of(2026, 9, 27), TransactionType.EXPENSE, TransactionSubType.BANK_LOAN_PAYMENT, "400000");
        LoanTaken friend = new LoanTaken();
        friend.setId(8L);
        friend.setLenderName("Aziz");
        friend.setTotalAmount(new BigDecimal("1000000"));
        friend.setPaidAmount(new BigDecimal("500000"));            // the repayment below, already counted
        friend.setPlannedMonthlyPayment(new BigDecimal("500000"));
        friend.setPaymentStartDate(SEP_1);
        friend.setCurrency(Currency.UZS);
        friend.setStatus(RecordStatus.PARTIALLY_PAID);
        loans.add(friend);
        ledger.add(LocalDate.of(2026, 9, 28), TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT, "500000")
                .setRepaidLoanTakenId(8L);

        Daily d = walk(SEP_23, "5000000", "7000000", "0", "0").daily();

        assertThat(d.getUpcoming())
                .extracting(Upcoming::getDate, Upcoming::getKind, Upcoming::getRefId,
                        u -> u.getAmount().stripTrailingZeros().toPlainString(), Upcoming::isOverdue)
                .containsExactly(
                        tuple(LocalDate.of(2026, 9, 27), "BANK", 5L, "400000", false),
                        tuple(LocalDate.of(2026, 9, 28), "LOAN", 8L, "500000", false),
                        tuple(LocalDate.of(2026, 10, 1), "LOAN", 8L, "500000", false),
                        tuple(LocalDate.of(2026, 10, 5), "BANK", 5L, "400000", false));
    }

    /** A donation entered ahead for the 28th leaves the wallets on the 28th, as a saving. */
    @Test
    void aSetAsideRecordedForALaterDayLeavesTheWalletsOnThatDay() {
        ledger.income(LocalDate.of(2026, 9, 7), "7000000", salary);
        ledger.add(LocalDate.of(2026, 9, 28), TransactionType.EXPENSE, TransactionSubType.DONATION, "500000");

        Daily d = walk(SEP_23, "5000000", "7000000", "0", "0").daily();

        // 5M − 0.5M + 7M − October's 30% (2.1M) over the 45 days to 6 November = 208,888
        // (ignoring the donation gave 220,000).
        assertThat(d.getTightestOn()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(d.getBreakdown().getSavings()).isEqualByComparingTo("2600000");
        assertThat(d.getSafePerDay()).isEqualByComparingTo("208000");
    }

    // ── 4. LOW: this month's savings wait for the salary that funds them ─────────

    /**
     * 3 September: 500,000 left, the 14M salary due on the 7th, 3.6M of September's set-asides
     * still to make. Reserved today, they read "short by 3.1M" before every payday; set aside on
     * the 7th out of the salary, 125,000 a day is affordable until then.
     */
    @Test
    void thisMonthsSetAsidesWaitForThePaydayThatFundsThem() {
        settings.setMonthlyStableIncome(new BigDecimal("14000000"));
        ledger.income(LocalDate.of(2026, 8, 7), "14000000", salary);

        Daily d = walk(LocalDate.of(2026, 9, 3), "500000", "14000000", "14000000", "3600000").daily();

        assertThat(d.getShortBy()).isNull();
        assertThat(d.getSafePerDay()).isEqualByComparingTo("125000");
        assertThat(d.getTightestOn()).isEqualTo(LocalDate.of(2026, 9, 6));
        assertThat(d.getBreakdown().getSavings()).isEqualByComparingTo("0");
    }
}
