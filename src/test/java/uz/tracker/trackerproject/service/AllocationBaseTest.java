package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.enums.CategoryType;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The allocation base the owner chose on 2026-09-23: the percentages apply to what they earn —
 * max(stable income, salary received) + bonus — where the salary is the income recorded in the
 * salary's category tree. Everything else about the tier (the level, the rule, the percentages)
 * still comes from the stable income and the debt.
 */
class AllocationBaseTest {

    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final LocalDate SEP_23 = LocalDate.of(2026, 9, 23);

    private final List<Category> categories = new ArrayList<>();
    private TransactionLedger ledger;
    private OverviewService service;

    @BeforeEach
    void setUp() {
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        CategoryRepository categoryRepository = mock(CategoryRepository.class);
        SettingsService settingsService = mock(SettingsService.class);
        Settings settings = new Settings();
        settings.setMonthlyStableIncome(new BigDecimal("7000000"));
        settings.setMonthlyStableIncomeCurrency(Currency.UZS);
        settings.setAllocationTrackingStartMonth(SEP.atDay(1));
        when(settingsService.getOrCreate()).thenReturn(settings);
        when(categoryRepository.findAll()).thenReturn(categories);

        ledger = new TransactionLedger(transactionRepository);
        service = new OverviewService(transactionRepository, mock(MonthlyPaymentRepository.class),
                mock(BankLoanRepository.class), mock(LoanTakenRepository.class), mock(DebtRepository.class),
                mock(DonationRepository.class), mock(InvestmentRepository.class),
                mock(LevelAllocationRuleRepository.class), mock(LevelConfigRepository.class),
                mock(MarkPaidRepository.class), settingsService, categoryRepository);
    }

    private Category category(long id, String name, boolean bonus, Category parent) {
        Category c = TransactionLedger.category(name, bonus, parent);
        c.setId(id);
        c.setType(CategoryType.INCOME);
        categories.add(c);
        return c;
    }

    private OverviewTierResponse tier(LocalDate asOf) {
        return service.getTierIgnoringSubscriptions(SEP, Currency.UZS, asOf);
    }

    /**
     * The owner's shape: the bonus sits under Salary, so the tree is Salary with its Avans (and the
     * Bonus, counted as the bonus). "Other income" and freelance work are roots of their own — not
     * salary, however they are named.
     */
    @Test
    void theSalaryTreeIsTheRootAboveTheBonusCategories() {
        Category salary = category(1, "Salary", false, null);
        Category avans = category(2, "Avans", false, salary);
        Category bonus = category(3, "Bonus", true, salary);
        Category other = category(4, "Other income", false, null);
        Category freelance = category(5, "Freelance", false, null);
        ledger.income(LocalDate.of(2026, 9, 7), "5889000", salary);
        ledger.income(LocalDate.of(2026, 9, 15), "2000000", avans);
        ledger.income(LocalDate.of(2026, 9, 12), "1000000", bonus);
        ledger.income(LocalDate.of(2026, 9, 10), "100000", other);
        ledger.income(LocalDate.of(2026, 9, 14), "500000", freelance);

        OverviewTierResponse t = tier(SEP_23);

        assertThat(t.getSalaryReceived()).isEqualByComparingTo("7889000");
        assertThat(t.getBonusIncome()).isEqualByComparingTo("1000000");
        assertThat(t.getAllocationBase()).isEqualByComparingTo("8889000");   // 7,889,000 + 1,000,000
        // The ledger's month is built on the same base.
        assertThat(service.getAllocationLedger(SEP, Currency.UZS).getAllocationBase()).isEqualByComparingTo("8889000");
    }

    /** No bonus under a root (the bonus is a root itself): the root income category named Salary is the tree. */
    @Test
    void withoutABonusUnderARootTheRootNamedSalaryIsTheTree() {
        Category salary = category(1, " SALARY ", false, null);
        Category avans = category(2, "Avans", false, salary);
        Category bonus = category(3, "Bonus", true, null);
        Category other = category(4, "Other income", false, null);
        ledger.income(LocalDate.of(2026, 9, 7), "6000000", salary);
        ledger.income(LocalDate.of(2026, 9, 15), "1000000", avans);
        ledger.income(LocalDate.of(2026, 9, 18), "1000000", bonus);
        ledger.income(LocalDate.of(2026, 9, 10), "500000", other);

        OverviewTierResponse t = tier(SEP_23);

        assertThat(t.getSalaryReceived()).isEqualByComparingTo("7000000");
        assertThat(t.getAllocationBase()).isEqualByComparingTo("8000000");
    }

    /**
     * Neither a bonus under a root nor a root named Salary: every non-bonus regular income is the
     * salary — but still not borrowed money, nor a foreign pot.
     */
    @Test
    void withNeitherEveryRegularIncomeIsTheSalary() {
        Category wage = category(1, "Wage", false, null);
        Category other = category(2, "Other income", false, null);
        ledger.income(LocalDate.of(2026, 9, 7), "7500000", wage);
        ledger.income(LocalDate.of(2026, 9, 10), "500000", other);
        ledger.add(LocalDate.of(2026, 9, 12), TransactionType.INCOME, TransactionSubType.LOAN_RECEIVED, "1000000");
        ledger.income(LocalDate.of(2026, 9, 13), "300", wage).setCurrency(Currency.USD);

        OverviewTierResponse t = tier(SEP_23);

        assertThat(t.getSalaryReceived()).isEqualByComparingTo("8000000");
        assertThat(t.getAllocationBase()).isEqualByComparingTo("8000000");
    }

    /**
     * Before payday nothing is received, so the stable income stands in — the targets are the
     * month's from its first day; once the salary is in, it counts when it is more. The month under
     * way counts the salary up to today: an advance recorded for the 28th is not in yet on the 23rd.
     */
    @Test
    void theStableIncomeStandsInUntilMoreIsReceivedCountingUpToToday() {
        Category salary = category(1, "Salary", false, null);
        Category avans = category(2, "Avans", false, salary);
        category(3, "Bonus", true, salary);
        ledger.income(LocalDate.of(2026, 9, 7), "5889000", salary);
        ledger.income(LocalDate.of(2026, 9, 28), "2000000", avans);

        OverviewTierResponse beforePayday = tier(LocalDate.of(2026, 9, 5));
        assertThat(beforePayday.getSalaryReceived()).isEqualByComparingTo("0");
        assertThat(beforePayday.getAllocationBase()).isEqualByComparingTo("7000000");

        OverviewTierResponse after = tier(SEP_23);
        assertThat(after.getSalaryReceived()).isEqualByComparingTo("5889000");
        assertThat(after.getAllocationBase()).isEqualByComparingTo("7000000");     // still under Settings

        OverviewTierResponse monthOver = tier(LocalDate.of(2026, 10, 2));
        assertThat(monthOver.getSalaryReceived()).isEqualByComparingTo("7889000");
        assertThat(monthOver.getAllocationBase()).isEqualByComparingTo("7889000");  // the salary, above Settings
        // The level and the rule stay on the stable income: 7M, no bills, no debt.
        assertThat(monthOver.getLevel()).isEqualTo(1);
        assertThat(monthOver.getSubLevel()).isEqualTo("1.1");
    }
}
