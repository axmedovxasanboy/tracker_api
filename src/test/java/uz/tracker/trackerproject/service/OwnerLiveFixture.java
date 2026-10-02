package uz.tracker.trackerproject.service;

import uz.tracker.trackerproject.entity.BankLoan;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.Debt;
import uz.tracker.trackerproject.entity.HoldingMonthSnapshot;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.LoanGiven;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.entity.MonthlyPayment;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.CategoryType;
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
import uz.tracker.trackerproject.repository.HoldingMonthSnapshotRepository;
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
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The owner's live data of 2 October 2026 (ANALYTICS-V2-SPEC §4.7 "Test fixture"), behind the V2
 * breakdown with the real Plan (OverviewService) and mocked tables. Ids are the live ones where the
 * spec gives them; a date the spec leaves open is any day of its month — no check depends on it.
 * A test may change a row before it reads (the 7 Sep salary marked as August's: Q2).
 */
final class OwnerLiveFixture {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 2);
    static final YearMonth SEP = YearMonth.of(2026, 9);
    static final YearMonth OCT = YearMonth.of(2026, 10);

    final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    final TransactionLedger ledger = new TransactionLedger(transactionRepository);
    final Settings settings = new Settings();
    final List<Category> categories = new ArrayList<>();
    final List<Investment> holdings = new ArrayList<>();
    final List<LoanTaken> loansTaken = new ArrayList<>();
    final List<LoanGiven> loansGiven = new ArrayList<>();
    final List<Debt> debts = new ArrayList<>();
    final List<BankLoan> bankLoans = new ArrayList<>();
    final List<MonthlyPayment> bills = new ArrayList<>();
    final List<HoldingMonthSnapshot> snapshots = new ArrayList<>();
    final SettingsService settingsService = mock(SettingsService.class);
    final HoldingMonthSnapshotRepository snapshotRepository = mock(HoldingMonthSnapshotRepository.class);
    final InvestmentRepository investmentRepository = mock(InvestmentRepository.class);
    final OverviewService overview;
    final AnalyticsBreakdownService breakdown;
    /** GET /analytics over the same rows, to hold the two endpoints to one flow per month. */
    final AnalyticsService analytics;

    // ── Categories ──
    final Category salaryRoot, bonus, salary, avans, otherIncome;
    final Category food, housing, education, loanRepayment, bankInstalment, donation, mosque, investment,
            emergencyFund, everydaySpending, family, travel, other, work, entertainment, loanGiven, loanReceived,
            loanReturned;

    // ── Rows a test may change ──
    Transaction salary7Sep;

    /** The live data. */
    OwnerLiveFixture() {
        this(true);
    }

    /** {@code live} false: the categories and Settings only (tracking start unset) — a test adds its rows. */
    OwnerLiveFixture(boolean live) {
        CategoryRepository categoryRepository = mock(CategoryRepository.class);
        MonthlyPaymentRepository monthlyPaymentRepository = mock(MonthlyPaymentRepository.class);
        BankLoanRepository bankLoanRepository = mock(BankLoanRepository.class);
        LoanTakenRepository loanTakenRepository = mock(LoanTakenRepository.class);
        LoanGivenRepository loanGivenRepository = mock(LoanGivenRepository.class);
        DebtRepository debtRepository = mock(DebtRepository.class);
        MarkPaidRepository markPaidRepository = mock(MarkPaidRepository.class);

        when(settingsService.getOrCreate()).thenReturn(settings);
        when(categoryRepository.findAll()).thenReturn(categories);
        when(investmentRepository.findAll()).thenReturn(holdings);
        when(investmentRepository.findById(any())).thenAnswer(inv ->
                holdings.stream().filter(i -> inv.getArgument(0).equals(i.getId())).findFirst());
        when(investmentRepository.findByOriginatingTransactionId(any())).thenAnswer(inv ->
                holdings.stream().filter(i -> inv.getArgument(0).equals(i.getOriginatingTransactionId())).findFirst());
        when(loanTakenRepository.findAll()).thenReturn(loansTaken);
        when(loanGivenRepository.findAll()).thenReturn(loansGiven);
        when(debtRepository.findAll()).thenReturn(debts);
        when(bankLoanRepository.findAll()).thenReturn(bankLoans);
        when(monthlyPaymentRepository.findAll()).thenReturn(bills);
        when(markPaidRepository.findByMonth(any())).thenReturn(List.of());
        when(snapshotRepository.findByInvestmentIdIn(any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return snapshots.stream().filter(s -> ids.contains(s.getInvestmentId())).toList();
        });

        overview = new OverviewService(transactionRepository, monthlyPaymentRepository, bankLoanRepository,
                loanTakenRepository, debtRepository, mock(DonationRepository.class), investmentRepository,
                mock(LevelAllocationRuleRepository.class), mock(LevelConfigRepository.class), markPaidRepository,
                settingsService, categoryRepository);
        breakdown = new AnalyticsBreakdownService(transactionRepository, categoryRepository, investmentRepository,
                loanTakenRepository, loanGivenRepository, debtRepository, bankLoanRepository, monthlyPaymentRepository,
                snapshotRepository, settingsService, overview);
        EmergencyRepository emergencyRepository = mock(EmergencyRepository.class);
        MonthCloseService monthCloseService = mock(MonthCloseService.class);
        WalletCheckInService walletCheckInService = mock(WalletCheckInService.class);
        DailyAdviceService daily = new DailyAdviceService(overview, settingsService, transactionRepository,
                monthlyPaymentRepository, bankLoanRepository, loanTakenRepository, debtRepository);
        AdvisorService advisor = new AdvisorService(overview, walletCheckInService, monthCloseService,
                transactionRepository, loanGivenRepository, investmentRepository, emergencyRepository, daily);
        analytics = new AnalyticsService(transactionRepository, settingsService, overview, advisor, monthCloseService,
                investmentRepository, emergencyRepository, loanTakenRepository, debtRepository, bankLoanRepository,
                loanGivenRepository, monthlyPaymentRepository, mock(PositionSnapshotService.class));

        // ── Settings: monthly income 8,000,000 from 2026-09; tracking start 2026-09 ──
        settings.setMonthlyStableIncome(new BigDecimal("8000000"));
        settings.setMonthlyStableIncomeCurrency(Currency.UZS);
        if (live) settings.setAllocationTrackingStartMonth(SEP.atDay(1));

        // ── Income tree ──
        salaryRoot = category(1, "Salary", "Oylik maosh", CategoryType.INCOME, null);
        bonus = category(23, "Bonus", "Premiya", CategoryType.INCOME, salaryRoot);
        bonus.setBonusIncome(true);
        salary = category(24, "Salary", "Maosh", CategoryType.INCOME, salaryRoot);
        avans = category(26, "Avans", "Avans", CategoryType.INCOME, salaryRoot);
        category(2, "Freelance", null, CategoryType.INCOME, null);
        loanReceived = category(3, "Loan Received", null, CategoryType.INCOME, null);
        loanReturned = category(4, "Loan Returned", null, CategoryType.INCOME, null);
        category(5, "Investment Return", null, CategoryType.INCOME, null);
        otherIncome = category(6, "Other Income", "Boshqa daromad", CategoryType.INCOME, null);
        category(29, "Taken from savings", null, CategoryType.INCOME, null);

        // ── Expense tree ──
        food = category(7, "Food & Dining", null, CategoryType.EXPENSE, null);
        category(8, "Transport", null, CategoryType.EXPENSE, null);
        housing = category(9, "Housing", "Uy-joy", CategoryType.EXPENSE, null);
        category(10, "Healthcare", null, CategoryType.EXPENSE, null);
        entertainment = category(11, "Entertainment", null, CategoryType.EXPENSE, null);
        category(12, "Shopping", null, CategoryType.EXPENSE, null);
        education = category(13, "Education", null, CategoryType.EXPENSE, null);
        loanGiven = category(14, "Loan Given", null, CategoryType.EXPENSE, null);
        loanRepayment = category(15, "Loan Repayment", null, CategoryType.EXPENSE, null);
        bankInstalment = category(16, "Bank Instalment", null, CategoryType.EXPENSE, null);
        donation = category(17, "Donation", null, CategoryType.EXPENSE, null);
        donation.setApplicableSubType(TransactionSubType.DONATION);
        category(22, "Anonymous", null, CategoryType.EXPENSE, donation).setAnonymizes(true);
        mosque = category(30, "Mosque", null, CategoryType.EXPENSE, donation);
        investment = category(18, "Investment", null, CategoryType.EXPENSE, null);
        category(19, "Stocks", null, CategoryType.EXPENSE, null);
        emergencyFund = category(20, "Emergency Fund", null, CategoryType.EXPENSE, null);
        everydaySpending = category(21, "Everyday Spending", null, CategoryType.EXPENSE, null);
        family = category(25, "Family & Support", "Oila", CategoryType.EXPENSE, null);
        travel = category(27, "Travel", "Sayohat", CategoryType.EXPENSE, null);
        other = category(28, "Other (mainly for commisions)", null, CategoryType.EXPENSE, null);
        work = category(31, "Work", null, CategoryType.EXPENSE, null);
        if (live) live();
    }

    /** The rows, loans, bills and holdings of 2 October. */
    private void live() {
        // ── Income rows: 41,204,000 counted in September ──
        salary7Sep = income(sep(7), "5889000", salary, "Salary", null);
        income(sep(15), "2000000", avans, "Avans", null);
        income(sep(18), "16380000", bonus, "1-Oktyabr", null);
        income(sep(30), "9665000", bonus, "Kartoshka puli", SEP);
        income(sep(18), "100000", otherIncome, "Other", null);
        income(OCT.atDay(2), "7170000", salary, "Salary", SEP);

        // ── Loans: Uzum Bank and Uzum Nasiya borrowed and repaid on 18 Sep; a bank loan's instalment on 1 Oct ──
        Transaction uzumBank = row(sep(14), TransactionType.INCOME, TransactionSubType.LOAN_RECEIVED, "1155000",
                loanReceived, "Uzum Bank");
        Transaction uzumNasiya = row(sep(17), TransactionType.INCOME, TransactionSubType.LOAN_RECEIVED, "800000",
                loanReceived, "Uzum Nasiya");
        loanTaken(5, "Uzum Bank", "1155000", sep(14), uzumBank);
        loanTaken(6, "Uzum Nasiya", "800000", sep(17), uzumNasiya);
        row(sep(18), TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT, "1155000", loanRepayment, "Uzum Bank")
                .setRepaidLoanTakenId(5L);
        row(sep(18), TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT, "800000", loanRepayment, "Uzum Nasiya")
                .setRepaidLoanTakenId(6L);
        BankLoan xalq = new BankLoan();
        xalq.setId(1L);
        xalq.setBankName("Xalq Banki");
        xalq.setLoanName("Talim kredit");
        xalq.setTotalAmount(new BigDecimal("26000000"));
        xalq.setMonthlyPayment(new BigDecimal("400000"));
        xalq.setCurrency(Currency.UZS);
        xalq.setTakenDate(LocalDate.of(2026, 1, 1));
        bankLoans.add(xalq);
        row(OCT.atDay(1), TransactionType.EXPENSE, TransactionSubType.BANK_LOAN_PAYMENT, "400000", bankInstalment,
                "Xalq Banki · Talim kredit");

        // ── Lent: 6 rows, 1,000,000; paid back to you: Mirjalol 300,000 and Fozilbek 400,000 ──
        LoanGiven mirjalol = loanGiven(3, "Mirjalol", "300000", null);
        LoanGiven fozilbek = loanGiven(4, "Fozilbek", "400000", null);
        String[][] lent = {{"5", "200000", "Sardor"}, {"8", "150000", "Aziz"}, {"12", "150000", "Aziz"},
                {"20", "200000", "Bekzod"}, {"24", "100000", "Sardor"}, {"29", "200000", "Jasur"}};
        for (String[] l : lent) {
            row(sep(Integer.parseInt(l[0])), TransactionType.EXPENSE, TransactionSubType.LOAN_GIVEN, l[1], loanGiven, l[2]);
        }
        row(sep(18), TransactionType.INCOME, TransactionSubType.LOAN_RETURNED_TO_ME, "300000", loanReturned, "Mirjalol")
                .setRepaidLoanGivenId(mirjalol.getId());
        row(sep(27), TransactionType.INCOME, TransactionSubType.LOAN_RETURNED_TO_ME, "400000", loanReturned, "Fozilbek")
                .setRepaidLoanGivenId(fozilbek.getId());

        // ── Everyday, itemised: 10,988,000 ──
        String[][] xusanboy = {{"3", "500000"}, {"6", "450000"}, {"11", "400000"}, {"19", "400000"}, {"25", "300000"}};
        for (String[] r : xusanboy) spend(sep(Integer.parseInt(r[0])), r[1], family, "Xusanboy");
        String[][] tabassum = {{"4", "800000"}, {"16", "600000"}, {"26", "500000"}};
        for (String[] r : tabassum) spend(sep(Integer.parseInt(r[0])), r[1], family, "Tabassum");
        spend(sep(19), "2100000", housing, "Perfectum WiFi");
        spend(sep(9), "455000", housing, "Toilet repair");
        spend(sep(12), "394000", housing, "Xarajat");
        spend(sep(22), "300000", housing, "Xarajat uchun (Tabassum)");
        spend(sep(27), "139000", housing, "Xarajat");
        spend(sep(13), "600000", food, "Bolo Hovuz");
        spend(sep(14), "350000", food, "Jalyuzi uchun");
        spend(sep(20), "297000", food, "Ahmad");
        spend(sep(21), "294000", food, "GIOTTO");
        spend(sep(24), "121000", food, "Papa Johns");
        spend(sep(10), "1350000", work, "Claude subscription");
        spend(sep(28), "398000", travel, "Travel");
        spend(sep(6), "200000", entertainment, "Bicycle");
        spend(sep(17), "40000", other, "Uzum Nasiya commission");

        // ── Wallet checks: 4,168,000 found missing, 120,000 found (cash, 15 Sep) → 4,048,000 ──
        check(sep(15), "1427000");
        check(sep(15), "473000");
        check(sep(23), "1197000");
        check(sep(23), "380000");
        check(sep(23), "20000");
        check(sep(28), "591000");
        check(sep(28), "80000");
        row(sep(15), TransactionType.INCOME, TransactionSubType.EVERYDAY_SPENDING, "120000", null, "Cash found");

        // ── Bills: one row each in September ──
        bill(3, "Kvartira Arenda", "4200000", housing);
        bill(4, "Noon Academy (Tabassum)", "1100000", education);
        ledger.billPaid(sep(1), "4200000", 3).setCategory(housing);
        ledger.billPaid(sep(5), "1100000", 4).setCategory(education);

        // ── Holdings: IMAN with four top-ups, Asaxiy with none, IMAN (Emergency) with two ──
        holding(2, "IMAN", InvestmentType.MUTUAL_FUND, "17300000", "17610147", LocalDate.of(2025, 3, 1), false);
        holding(1, "Asaxiy", InvestmentType.OTHER, "2000000", "2005827", LocalDate.of(2026, 6, 1), true);
        holding(3, "IMAN (Emergency)", InvestmentType.MUTUAL_FUND, "1400000", "1413254", LocalDate.of(2026, 5, 1), true)
                .setEmergencyFund(true);
        save(sep(15), TransactionSubType.INVESTMENT, AllocationBucket.INVESTMENTS, "100000", 2L, investment, "IMAN");
        save(sep(21), TransactionSubType.INVESTMENT, AllocationBucket.INVESTMENTS, "1500000", 2L, investment, "IMAN");
        save(sep(23), TransactionSubType.INVESTMENT, AllocationBucket.INVESTMENTS, "400000", 2L, investment, "IMAN");
        save(sep(30), TransactionSubType.INVESTMENT, AllocationBucket.INVESTMENTS, "1000000", 2L, investment, "IMAN");
        save(sep(21), TransactionSubType.EMERGENCY_CONTRIBUTION, AllocationBucket.EMERGENCY, "500000", 3L, emergencyFund,
                "IMAN (Emergency)");
        save(sep(23), TransactionSubType.EMERGENCY_CONTRIBUTION, AllocationBucket.EMERGENCY, "220000", 3L, emergencyFund,
                "IMAN (Emergency)");

        // ── Donations: Mosque, 400,000 twice ──
        save(sep(25), TransactionSubType.DONATION, AllocationBucket.DONATION, "400000", null, mosque, "Abu Talha masjidi");
        save(sep(26), TransactionSubType.DONATION, AllocationBucket.DONATION, "400000", null, mosque, "Novza masjidi");

        // ── Goals, all created in October; 1,000,000 into Ota-onam on 1 Oct ──
        goal(11, "Ota-onam (parents)", "1000000", "50000000", "1000000", null, false);
        goal(9, "Atam uchun Samsung S26 ultra", "0", "12000000", "4000000", LocalDate.of(2026, 12, 31), false);
        goal(7, "Lobarxon uchun MacBook", "0", "10000000", "3500000", LocalDate.of(2026, 12, 31), false);
        goal(8, "IPhone 19 / 18 pro max", "0", "40000000", null, null, true);
        save(OCT.atDay(1), TransactionSubType.INVESTMENT, AllocationBucket.SAVINGS, "1000000", 11L, investment,
                "Ota-onam (parents)");

        // ── A move between own wallets: must be ignored ──
        Transaction out = row(sep(27), TransactionType.EXPENSE, TransactionSubType.TRANSFER_OUT, "1180000", null, "MinCon → cash");
        Transaction in = row(sep(27), TransactionType.INCOME, TransactionSubType.TRANSFER_IN, "1180000", null, "MinCon → cash");
        out.setTransferPairId(out.getId());
        in.setTransferPairId(out.getId());
    }

    static LocalDate sep(int day) {
        return SEP.atDay(day);
    }

    Category category(long id, String name, String nameUz, CategoryType type, Category parent) {
        Category c = TransactionLedger.category(name, false, parent);
        c.setId(id);
        c.setNameUz(nameUz);
        c.setType(type);
        categories.add(c);
        return c;
    }

    Transaction row(LocalDate date, TransactionType type, TransactionSubType subType, String amount, Category category,
                    String description) {
        Transaction t = ledger.add(date, type, subType, amount);
        t.setCategory(category);
        t.setDescription(description);
        return t;
    }

    Transaction income(LocalDate date, String amount, Category category, String description, YearMonth forMonth) {
        Transaction t = row(date, TransactionType.INCOME, TransactionSubType.REGULAR_INCOME, amount, category, description);
        if (forMonth != null) t.setSalaryMonth(forMonth.atDay(1));
        return t;
    }

    Transaction spend(LocalDate date, String amount, Category category, String description) {
        return row(date, TransactionType.EXPENSE, TransactionSubType.REGULAR_EXPENSE, amount, category, description);
    }

    /** A wallet check that found money missing: everyday spending nobody itemised. */
    Transaction check(LocalDate date, String amount) {
        return row(date, TransactionType.EXPENSE, TransactionSubType.EVERYDAY_SPENDING, amount, everydaySpending,
                "Everyday spending (wallet check-in)");
    }

    /** Money put by, with the bucket recorded on the row as the write path records it. */
    Transaction save(LocalDate date, TransactionSubType subType, String bucket, String amount, Long investmentId,
                     Category category, String description) {
        Transaction t = row(date, TransactionType.EXPENSE, subType, amount, category, description);
        t.setAllocationBucket(bucket);
        t.setInvestmentId(investmentId);
        return t;
    }

    MonthlyPayment bill(long id, String name, String amount, Category category) {
        MonthlyPayment m = new MonthlyPayment();
        m.setId(id);
        m.setName(name);
        m.setAmount(new BigDecimal(amount));
        m.setCurrency(Currency.UZS);
        m.setDueDay(1);
        m.setActive(true);
        m.setCategory(category);
        bills.add(m);
        return m;
    }

    LoanTaken loanTaken(long id, String lender, String amount, LocalDate borrowedOn, Transaction created) {
        LoanTaken l = new LoanTaken();
        l.setId(id);
        l.setLenderName(lender);
        l.setTotalAmount(new BigDecimal(amount));
        l.setPaidAmount(new BigDecimal(amount));
        l.setCurrency(Currency.UZS);
        l.setBorrowedDate(borrowedOn);
        l.setStatus(RecordStatus.PAID);
        l.setOriginatingTransactionId(created.getId());
        loansTaken.add(l);
        return l;
    }

    LoanGiven loanGiven(long id, String debtor, String amount, Transaction created) {
        LoanGiven l = new LoanGiven();
        l.setId(id);
        l.setDebtorName(debtor);
        l.setTotalAmount(new BigDecimal(amount));
        l.setReceivedAmount(new BigDecimal(amount));
        l.setCurrency(Currency.UZS);
        l.setLentDate(LocalDate.of(2026, 8, 1));
        l.setStatus(RecordStatus.PAID);
        l.setOriginatingTransactionId(created == null ? null : created.getId());
        loansGiven.add(l);
        return l;
    }

    Investment holding(long id, String name, InvestmentType type, String invested, String value, LocalDate bought,
                       boolean opening) {
        Investment i = new Investment();
        i.setId(id);
        i.setName(name);
        i.setType(type);
        i.setInvestedAmount(new BigDecimal(invested));
        i.setCurrentValue(value == null ? null : new BigDecimal(value));
        i.setCurrency(Currency.UZS);
        i.setPurchaseDate(bought);
        i.setOpeningBalance(opening);
        holdings.add(i);
        return i;
    }

    /** A savings goal made in October; {@code monthly} null and {@code wish} true make a wish. */
    Investment goal(long id, String name, String invested, String target, String monthly, LocalDate deadline,
                    boolean wish) {
        Investment g = holding(id, name, InvestmentType.OTHER, invested, null, OCT.atDay(1), false);
        g.setSavingsGoal(true);
        g.setTargetAmount(new BigDecimal(target));
        g.setMonthlyContribution(monthly == null ? null : new BigDecimal(monthly));
        g.setTargetDate(deadline);
        g.setWish(wish);
        return g;
    }

    Investment holding(long id) {
        return holdings.stream().filter(h -> h.getId() == id).findFirst().orElseThrow();
    }

    Optional<Category> categoryById(long id) {
        return categories.stream().filter(c -> c.getId() == id).findFirst();
    }

    HoldingMonthSnapshot snapshot(long investmentId, YearMonth month, String source, String value, String putIn,
                                  String takenOut) {
        HoldingMonthSnapshot s = new HoldingMonthSnapshot();
        s.setInvestmentId(investmentId);
        s.setMonth(month.atDay(1));
        s.setAsOf(month.atEndOfMonth());
        s.setValue(new BigDecimal(value));
        s.setInvested(new BigDecimal(value));
        s.setPutInMonth(new BigDecimal(putIn));
        s.setTakenOutMonth(new BigDecimal(takenOut));
        s.setSource(source);
        s.setKind("GOAL");
        snapshots.add(s);
        return s;
    }
}
