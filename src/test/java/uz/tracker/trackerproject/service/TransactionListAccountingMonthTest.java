package uz.tracker.trackerproject.service;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uz.tracker.trackerproject.controller.TransactionController;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.CardRepository;
import uz.tracker.trackerproject.repository.CashBalanceRepository;
import uz.tracker.trackerproject.repository.CategoryRepository;
import uz.tracker.trackerproject.repository.DonationRepository;
import uz.tracker.trackerproject.repository.InvestmentRepository;
import uz.tracker.trackerproject.repository.LoanGivenRepository;
import uz.tracker.trackerproject.repository.LoanTakenRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;
import uz.tracker.trackerproject.repository.TransactionSpecification;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/transactions?accountingMonth=true — History's month view (2026-10-02: "When I add Salary
 * for September it does not update September, it changes October's"). For one whole month the list is
 * the rows that count in it — a salary marked as another month's moves to that month; for any other
 * range, and without the flag, the dates work exactly as before. The Specification the service builds
 * is applied to a CriteriaBuilder that writes the condition out, so a test reads what the database gets.
 */
class TransactionListAccountingMonthTest {

    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEP_30 = LocalDate.of(2026, 9, 30);

    private TransactionRepository transactionRepository;
    private TransactionService service;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        service = new TransactionService(transactionRepository, mock(CategoryRepository.class),
                mock(CardRepository.class), mock(CashBalanceRepository.class), mock(FinanceService.class),
                mock(MonthCloseService.class), mock(SettingsService.class), mock(LoanGivenRepository.class),
                mock(LoanTakenRepository.class), mock(DonationRepository.class), mock(InvestmentRepository.class));
        ReflectionTestUtils.setField(service, "maxPageSize", 100);
        when(transactionRepository.findAll(any(Specification.class), any(Pageable.class))).thenAnswer(inv ->
                new PageImpl<Transaction>(List.of(), inv.getArgument(1), 0));
    }

    // ── Which range is a whole month ──────────────────────────────────────────

    @Test
    void aWholeMonthIsTheFirstToThatMonthsLastDay() {
        assertThat(TransactionSpecification.wholeMonth(SEP_1, SEP_30)).isEqualTo(YearMonth.of(2026, 9));
        assertThat(TransactionSpecification.wholeMonth(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31)))
                .isEqualTo(YearMonth.of(2026, 12));
        assertThat(TransactionSpecification.wholeMonth(LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 28)))
                .isEqualTo(YearMonth.of(2027, 2));
        assertThat(TransactionSpecification.wholeMonth(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 29)))
                .isEqualTo(YearMonth.of(2028, 2));                                  // a leap year

        assertThat(TransactionSpecification.wholeMonth(SEP_1, LocalDate.of(2026, 9, 29))).isNull();   // a part
        assertThat(TransactionSpecification.wholeMonth(LocalDate.of(2026, 9, 2), SEP_30)).isNull();
        assertThat(TransactionSpecification.wholeMonth(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 28))).isNull();
        assertThat(TransactionSpecification.wholeMonth(SEP_1, LocalDate.of(2026, 10, 31))).isNull();  // two months
        assertThat(TransactionSpecification.wholeMonth(SEP_1, LocalDate.of(2026, 10, 30))).isNull();
        assertThat(TransactionSpecification.wholeMonth(SEP_1, null)).isNull();                         // open ends
        assertThat(TransactionSpecification.wholeMonth(null, SEP_30)).isNull();
        assertThat(TransactionSpecification.wholeMonth(null, null)).isNull();
    }

    // ── What the list asks the database for ───────────────────────────────────

    /** The live case: the History month of September also lists the salary marked as September's, paid 2 October. */
    @Test
    void withTheFlag_aWholeMonthListsTheRowsThatCountInIt() {
        assertThat(where(SEP_1, SEP_30, true)).isEqualTo(
                "((salaryMonth IS NULL AND transactionDate BETWEEN 2026-09-01 AND 2026-09-30) OR salaryMonth = 2026-09-01)");
        assertThat(where(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), true)).isEqualTo(
                "((salaryMonth IS NULL AND transactionDate BETWEEN 2026-10-01 AND 2026-10-31) OR salaryMonth = 2026-10-01)");
    }

    @Test
    void withTheFlag_anyOtherRangeIsByDateAsAlways() {
        assertThat(where(SEP_1, LocalDate.of(2026, 9, 15), true))
                .isEqualTo("transactionDate >= 2026-09-01 AND transactionDate <= 2026-09-15");
        assertThat(where(LocalDate.of(2026, 9, 2), SEP_30, true))
                .isEqualTo("transactionDate >= 2026-09-02 AND transactionDate <= 2026-09-30");
        assertThat(where(SEP_1, LocalDate.of(2026, 10, 31), true))
                .isEqualTo("transactionDate >= 2026-09-01 AND transactionDate <= 2026-10-31");
        assertThat(where(SEP_1, null, true)).isEqualTo("transactionDate >= 2026-09-01");
        assertThat(where(null, SEP_30, true)).isEqualTo("transactionDate <= 2026-09-30");
        assertThat(where(null, null, true)).isEmpty();
    }

    /** An old client never sends the flag: a whole month is still the rows dated in it. */
    @Test
    void withoutTheFlag_aWholeMonthIsByDateAsAlways() {
        assertThat(where(SEP_1, SEP_30, false))
                .isEqualTo("transactionDate >= 2026-09-01 AND transactionDate <= 2026-09-30");
    }

    @Test
    void everyOtherFilter_paging_andSortingAreTheSameWithTheFlag() {
        Specification<Transaction> on = spec(TransactionType.INCOME, 7L, 3L, 9L, "  Vazirlik ", SEP_1, SEP_30, true);
        Specification<Transaction> off = spec(TransactionType.INCOME, 7L, 3L, 9L, "  Vazirlik ", SEP_1, SEP_30, false);

        String dates = "transactionDate >= 2026-09-01 AND transactionDate <= 2026-09-30";
        String month = "((salaryMonth IS NULL AND transactionDate BETWEEN 2026-09-01 AND 2026-09-30) OR salaryMonth = 2026-09-01)";
        String others = "type = INCOME AND category.id = 7 AND card.id = 3 AND investmentId = 9 AND %s"
                + " AND lower(description) LIKE %%  vazirlik %%"
                + " AND (subType IS NULL OR NOT (subType IN (TRANSFER_IN, TRANSFER_OUT)))"
                + " AND cashAmount > 0";
        assertThat(render(off)).isEqualTo(others.formatted(dates));
        assertThat(render(on)).isEqualTo(others.formatted(month));

        ArgumentCaptor<Pageable> pages = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository, org.mockito.Mockito.times(2)).findAll(any(Specification.class), pages.capture());
        for (Pageable p : pages.getAllValues()) {
            assertThat(p.getPageNumber()).isEqualTo(2);
            assertThat(p.getPageSize()).isEqualTo(50);
            assertThat(p.getSort()).isEqualTo(Sort.by("transactionDate").descending().and(Sort.by("id").descending()));
        }
    }

    // ── The query string ──────────────────────────────────────────────────────

    @Test
    void theFlagIsOffUnlessSent_andPassedOnWhenSent() throws Exception {
        TransactionService mocked = mock(TransactionService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TransactionController(mocked)).build();

        mvc.perform(get("/api/v1/transactions").param("startDate", "2026-09-01").param("endDate", "2026-09-30"))
                .andExpect(status().isOk());
        verify(mocked).getAll(isNull(), isNull(), isNull(), isNull(), isNull(), eq(SEP_1), eq(SEP_30), isNull(),
                eq(0), eq(20), eq("transactionDate"), eq("desc"), eq(false), eq(false), eq(false));

        mvc.perform(get("/api/v1/transactions").param("startDate", "2026-09-01").param("endDate", "2026-09-30")
                        .param("accountingMonth", "true"))
                .andExpect(status().isOk());
        verify(mocked).getAll(isNull(), isNull(), isNull(), isNull(), isNull(), eq(SEP_1), eq(SEP_30), isNull(),
                anyInt(), anyInt(), any(), any(), anyBoolean(), anyBoolean(), eq(true));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String where(LocalDate start, LocalDate end, boolean accountingMonth) {
        return render(spec(null, null, null, null, null, start, end, accountingMonth));
    }

    /** The Specification the service hands the repository for one request. */
    @SuppressWarnings("unchecked")
    private Specification<Transaction> spec(TransactionType type, Long categoryId, Long cardId, Long investmentId,
                                            String search, LocalDate start, LocalDate end, boolean accountingMonth) {
        boolean filtered = categoryId != null;
        service.getAll(type, null, categoryId, cardId, investmentId, start, end, search,
                filtered ? 2 : 0, filtered ? 50 : 20, "transactionDate", "desc", filtered, filtered, accountingMonth);
        ArgumentCaptor<Specification<Transaction>> captor = ArgumentCaptor.forClass(Specification.class);
        verify(transactionRepository, org.mockito.Mockito.atLeastOnce()).findAll(captor.capture(), any(Pageable.class));
        return captor.getValue();
    }

    /**
     * The WHERE clause the Specification builds, written out; "" when it adds no condition. The list's
     * own and(...) of every filter joins them flat; an and / or of two conditions is bracketed.
     */
    @SuppressWarnings("unchecked")
    private static String render(Specification<Transaction> spec) {
        Root<Transaction> root = (Root<Transaction>) node(Root.class, "");
        CriteriaBuilder cb = (CriteriaBuilder) Proxy.newProxyInstance(CriteriaBuilder.class.getClassLoader(),
                new Class<?>[]{CriteriaBuilder.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "equal" -> predicate(text(args[0]) + " = " + text(args[1]));
                    case "greaterThanOrEqualTo" -> predicate(text(args[0]) + " >= " + text(args[1]));
                    case "lessThanOrEqualTo" -> predicate(text(args[0]) + " <= " + text(args[1]));
                    case "greaterThan" -> predicate(text(args[0]) + " > " + text(args[1]));
                    case "between" -> predicate(text(args[0]) + " BETWEEN " + text(args[1]) + " AND " + text(args[2]));
                    case "isNull" -> predicate(text(args[0]) + " IS NULL");
                    case "like" -> predicate(text(args[0]) + " LIKE " + text(args[1]));
                    case "lower" -> node(Expression.class, "lower(" + text(args[0]) + ")");
                    case "and" -> predicate(isVarargs(args) ? join(" AND ", args) : "(" + join(" AND ", args) + ")");
                    case "or" -> predicate("(" + join(" OR ", args) + ")");
                    case "toString" -> "cb";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return text(spec.toPredicate(root, null, cb));
    }

    private static boolean isVarargs(Object[] args) {
        return args.length == 1 && args[0] instanceof Object[];
    }

    private static String join(String op, Object[] args) {
        Object[] operands = isVarargs(args) ? (Object[]) args[0] : args;
        return Arrays.stream(operands).map(TransactionListAccountingMonthTest::text).collect(Collectors.joining(op));
    }

    private static Predicate predicate(String text) {
        return (Predicate) node(Predicate.class, text);
    }

    /** A criteria object that only knows what it says: a path's get(), an expression's in(), a predicate's not(). */
    private static Object node(Class<?> type, String text) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> node(Path.class, text.isEmpty() ? (String) args[0] : text + "." + args[0]);
                    case "in" -> predicate(text + " IN (" + join(", ", args) + ")");
                    case "not" -> predicate("NOT (" + text + ")");
                    case "toString" -> text;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static String text(Object o) {
        return String.valueOf(o);
    }
}
