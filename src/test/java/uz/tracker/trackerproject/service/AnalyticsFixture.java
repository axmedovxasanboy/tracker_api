package uz.tracker.trackerproject.service;

import uz.tracker.trackerproject.entity.BankLoan;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.Debt;
import uz.tracker.trackerproject.entity.Emergency;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.LoanGiven;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.entity.MarkPaid;
import uz.tracker.trackerproject.entity.MonthlyPayment;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.InvestmentType;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.BankLoanRepository;
import uz.tracker.trackerproject.repository.CategoryRepository;
import uz.tracker.trackerproject.repository.DebtRepository;
import uz.tracker.trackerproject.repository.DonationRepository;
import uz.tracker.trackerproject.repository.EmergencyRepository;
import uz.tracker.trackerproject.repository.InvestmentRepository;
import uz.tracker.trackerproject.repository.LevelAllocationRuleRepository;
import uz.tracker.trackerproject.repository.LevelConfigRepository;
import uz.tracker.trackerproject.repository.LoanGivenRepository;
import uz.tracker.trackerproject.repository.LoanTakenRepository;
import uz.tracker.trackerproject.repository.MarkPaidRepository;
import uz.tracker.trackerproject.repository.MonthlyPaymentRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Analytics with everything real behind it — the Plan (OverviewService), the daily walk and the
 * advisor — over mocked repositories and one ledger of transactions, so a test can hold Analytics
 * against what those already say. The tables start empty; a test adds what it needs.
 */
final class AnalyticsFixture {

    final TransactionLedger ledger;
    final Settings settings = new Settings();
    final List<MonthlyPayment> bills = new ArrayList<>();
    final List<BankLoan> bankLoans = new ArrayList<>();
    final List<LoanTaken> loansTaken = new ArrayList<>();
    final List<Debt> debts = new ArrayList<>();
    final List<LoanGiven> loansGiven = new ArrayList<>();
    final List<Investment> holdings = new ArrayList<>();
    final List<Emergency> emergencies = new ArrayList<>();
    final List<MarkPaid> marks = new ArrayList<>();
    final List<Category> categories = new ArrayList<>();
    final List<MonthCloseService.ComputedWallet> wallets = new ArrayList<>();

    final MarkPaidRepository markPaidRepository = mock(MarkPaidRepository.class);
    /** Mocked: answers {@code getOrCreate()} with {@link #settings}; a test may stub the income's history on it. */
    final SettingsService settingsService = mock(SettingsService.class);
    final PositionSnapshotService snapshots = mock(PositionSnapshotService.class);
    final OverviewService overview;
    final AdvisorService advisor;
    final AnalyticsService analytics;

    AnalyticsFixture() {
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        MonthlyPaymentRepository monthlyPaymentRepository = mock(MonthlyPaymentRepository.class);
        BankLoanRepository bankLoanRepository = mock(BankLoanRepository.class);
        LoanTakenRepository loanTakenRepository = mock(LoanTakenRepository.class);
        DebtRepository debtRepository = mock(DebtRepository.class);
        LoanGivenRepository loanGivenRepository = mock(LoanGivenRepository.class);
        InvestmentRepository investmentRepository = mock(InvestmentRepository.class);
        EmergencyRepository emergencyRepository = mock(EmergencyRepository.class);
        CategoryRepository categoryRepository = mock(CategoryRepository.class);
        MonthCloseService monthCloseService = mock(MonthCloseService.class);

        settings.setMonthlyStableIncomeCurrency(Currency.UZS);
        when(settingsService.getOrCreate()).thenReturn(settings);
        when(monthlyPaymentRepository.findAll()).thenReturn(bills);
        when(bankLoanRepository.findAll()).thenReturn(bankLoans);
        when(loanTakenRepository.findAll()).thenReturn(loansTaken);
        when(debtRepository.findAll()).thenReturn(debts);
        when(loanGivenRepository.findAll()).thenReturn(loansGiven);
        when(investmentRepository.findAll()).thenReturn(holdings);
        when(investmentRepository.findById(any())).thenAnswer(inv ->
                holdings.stream().filter(i -> inv.getArgument(0).equals(i.getId())).findFirst());
        when(investmentRepository.findByOriginatingTransactionId(any())).thenReturn(Optional.empty());
        when(emergencyRepository.findAllByOrderByDateDesc()).thenReturn(emergencies);
        when(categoryRepository.findAll()).thenReturn(categories);
        when(markPaidRepository.findByMonth(any())).thenAnswer(inv ->
                marks.stream().filter(m -> inv.getArgument(0).equals(m.getMonth())).toList());
        when(monthCloseService.computedWallets(any(), any())).thenReturn(wallets);

        ledger = new TransactionLedger(transactionRepository);
        overview = new OverviewService(transactionRepository, monthlyPaymentRepository,
                bankLoanRepository, loanTakenRepository, debtRepository, mock(DonationRepository.class),
                investmentRepository, mock(LevelAllocationRuleRepository.class), mock(LevelConfigRepository.class),
                markPaidRepository, settingsService, categoryRepository);
        DailyAdviceService daily = new DailyAdviceService(overview, settingsService, transactionRepository,
                monthlyPaymentRepository, bankLoanRepository, loanTakenRepository, debtRepository);
        WalletCheckInService walletCheckInService = mock(WalletCheckInService.class);
        when(walletCheckInService.status(any())).thenAnswer(inv ->
                uz.tracker.trackerproject.dto.response.WalletCheckInStatusResponse.builder()
                        .date(inv.getArgument(0)).due(false).daysSinceLastReconciled(1)
                        .wallets(wallets.stream().map(MonthCloseService.ComputedWallet::toLine).toList())
                        .build());
        advisor = new AdvisorService(overview, walletCheckInService, monthCloseService, transactionRepository,
                loanGivenRepository, investmentRepository, emergencyRepository, daily);
        analytics = new AnalyticsService(transactionRepository, settingsService, overview, advisor,
                monthCloseService, investmentRepository, emergencyRepository, loanTakenRepository, debtRepository,
                bankLoanRepository, loanGivenRepository, monthlyPaymentRepository, snapshots);
    }

    void stableIncome(String amount, LocalDate trackingStart) {
        settings.setMonthlyStableIncome(new BigDecimal(amount));
        settings.setAllocationTrackingStartMonth(trackingStart);
    }

    Category category(long id, String name, Category parent) {
        Category c = TransactionLedger.category(name, false, parent);
        c.setId(id);
        categories.add(c);
        return c;
    }

    /** An itemised everyday expense. */
    Transaction spend(LocalDate date, String amount, Category category, String description) {
        Transaction t = ledger.expense(date, amount);
        t.setCategory(category);
        t.setDescription(description);
        return t;
    }

    /** A wallet check that found money missing (an EXPENSE) — spending nobody itemised. */
    Transaction foundMissing(LocalDate date, String amount) {
        return ledger.add(date, TransactionType.EXPENSE, TransactionSubType.EVERYDAY_SPENDING, amount);
    }

    /** A wallet check that found more than expected (an INCOME) — a correction. */
    Transaction foundExtra(LocalDate date, String amount) {
        return ledger.add(date, TransactionType.INCOME, TransactionSubType.EVERYDAY_SPENDING, amount);
    }

    /** Money put by, with the bucket recorded on the row as the write path records it. */
    Transaction save(LocalDate date, TransactionSubType subType, String bucket, String amount) {
        Transaction t = ledger.add(date, TransactionType.EXPENSE, subType, amount);
        t.setAllocationBucket(bucket);
        return t;
    }

    MonthlyPayment bill(long id, String name, String amount) {
        MonthlyPayment m = new MonthlyPayment();
        m.setId(id);
        m.setName(name);
        m.setAmount(new BigDecimal(amount));
        m.setCurrency(Currency.UZS);
        m.setDueDay(10);
        m.setActive(true);
        bills.add(m);
        return m;
    }

    BankLoan bankLoan(long id, String bank, String product, String total, String monthly,
                      LocalDate taken, LocalDate end) {
        BankLoan b = new BankLoan();
        b.setId(id);
        b.setBankName(bank);
        b.setLoanName(product);
        b.setTotalAmount(new BigDecimal(total));
        b.setMonthlyPayment(monthly == null ? null : new BigDecimal(monthly));
        b.setCurrency(Currency.UZS);
        b.setTakenDate(taken);
        b.setEndDate(end);
        bankLoans.add(b);
        return b;
    }

    /** "Already paid" for a bank installment: no transaction, the Plan counts it. */
    void bankMark(long bankLoanId, LocalDate month, String amount) {
        MarkPaid m = new MarkPaid();
        m.setKind("BANK");
        m.setRefId(bankLoanId);
        m.setMonth(month);
        m.setAmount(new BigDecimal(amount));
        m.setCurrency(Currency.UZS);
        marks.add(m);
    }

    /** Borrowed money; {@code plan} null = ASAP. */
    LoanTaken borrowed(long id, String lender, String total, String paid, String plan,
                       LocalDate borrowedOn, LocalDate planStarts) {
        LoanTaken l = new LoanTaken();
        l.setId(id);
        l.setLenderName(lender);
        l.setTotalAmount(new BigDecimal(total));
        l.setPaidAmount(new BigDecimal(paid));
        l.setPlannedMonthlyPayment(plan == null ? null : new BigDecimal(plan));
        l.setBorrowedDate(borrowedOn);
        l.setPaymentStartDate(planStarts);
        l.setCurrency(Currency.UZS);
        l.setStatus(l.getTotalAmount().compareTo(l.getPaidAmount()) <= 0 ? RecordStatus.PAID : RecordStatus.PENDING);
        loansTaken.add(l);
        return l;
    }

    Debt debt(long id, String creditor, String total, String paid, LocalDate borrowedOn) {
        Debt d = new Debt();
        d.setId(id);
        d.setCreditorName(creditor);
        d.setTotalAmount(new BigDecimal(total));
        d.setPaidAmount(new BigDecimal(paid));
        d.setBorrowedDate(borrowedOn);
        d.setCurrency(Currency.UZS);
        d.setStatus(RecordStatus.PENDING);
        debts.add(d);
        return d;
    }

    LoanGiven lent(long id, String debtor, String total, String received) {
        LoanGiven l = new LoanGiven();
        l.setId(id);
        l.setDebtorName(debtor);
        l.setTotalAmount(new BigDecimal(total));
        l.setReceivedAmount(new BigDecimal(received));
        l.setCurrency(Currency.UZS);
        l.setStatus(RecordStatus.PARTIALLY_PAID);
        loansGiven.add(l);
        return l;
    }

    /** A holding; {@code value} null = worth what was put in. */
    Investment holding(long id, String name, String invested, String value, LocalDate bought) {
        Investment i = new Investment();
        i.setId(id);
        i.setName(name);
        i.setType(InvestmentType.OTHER);
        i.setInvestedAmount(new BigDecimal(invested));
        i.setCurrentValue(value == null ? null : new BigDecimal(value));
        i.setCurrency(Currency.UZS);
        i.setPurchaseDate(bought);
        holdings.add(i);
        return i;
    }

    void emergencyContribution(LocalDate date, String amount) {
        Emergency e = new Emergency();
        e.setAmount(new BigDecimal(amount));
        e.setCurrency(Currency.UZS);
        e.setDate(date);
        emergencies.add(e);
    }

    void wallet(String type, Currency currency, String balance) {
        wallets.add(new MonthCloseService.ComputedWallet(type, null, currency, type, new BigDecimal(balance)));
    }
}
