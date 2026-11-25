package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.request.InvestmentGoalRequest;
import uz.tracker.trackerproject.dto.request.InvestmentRequest;
import uz.tracker.trackerproject.dto.response.InvestmentGoalResponse;
import uz.tracker.trackerproject.dto.response.InvestmentResponse;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.InvestmentGoal;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.InvestmentType;
import uz.tracker.trackerproject.exception.ResourceNotFoundException;
import uz.tracker.trackerproject.repository.*;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Investment goals (Batch 79): "a car, about 1 mlrd — IMAN 1, IMAN 2 and Open Bank 1 save for it".
 * Saved = the accounts' values; an account serves one goal; links change only through the goal.
 */
class InvestmentGoalTest {

    private final Map<Long, Investment> holdings = new LinkedHashMap<>();
    private final Map<Long, InvestmentGoal> goals = new LinkedHashMap<>();
    private InvestmentRepository investmentRepository;
    private InvestmentGoalRepository goalRepository;
    private InvestmentGoalService service;
    private FinanceService finance;
    private long nextGoalId = 1;

    @BeforeEach
    void setUp() throws Exception {
        investmentRepository = mock(InvestmentRepository.class);
        goalRepository = mock(InvestmentGoalRepository.class);
        when(investmentRepository.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(holdings.get((Long) inv.getArgument(0))));
        when(investmentRepository.findAll()).thenAnswer(inv -> new ArrayList<>(holdings.values()));
        when(investmentRepository.findAllByOrderByPurchaseDateDesc()).thenAnswer(inv -> new ArrayList<>(holdings.values()));
        when(investmentRepository.save(any(Investment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(goalRepository.save(any(InvestmentGoal.class))).thenAnswer(inv -> {
            InvestmentGoal g = inv.getArgument(0);
            if (g.getId() == null) g.setId(nextGoalId++);
            goals.put(g.getId(), g);
            return g;
        });
        when(goalRepository.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(goals.get((Long) inv.getArgument(0))));
        when(goalRepository.findAll()).thenAnswer(inv -> new ArrayList<>(goals.values()));
        when(goalRepository.findAllByOrderByCreatedAtAscIdAsc()).thenAnswer(inv -> new ArrayList<>(goals.values()));
        doAnswer(inv -> goals.remove(((InvestmentGoal) inv.getArgument(0)).getId()))
                .when(goalRepository).delete(any(InvestmentGoal.class));
        service = new InvestmentGoalService(goalRepository, investmentRepository);

        TransactionRepository tx = mock(TransactionRepository.class);
        finance = new FinanceService(mock(DebtRepository.class), mock(LoanGivenRepository.class), mock(LoanTakenRepository.class),
                mock(BankLoanRepository.class), mock(MonthlyPaymentRepository.class), mock(DonationRepository.class),
                investmentRepository, mock(CategoryRepository.class), tx,
                mock(CardRepository.class), mock(MarkPaidRepository.class), mock(CardService.class),
                mock(MonthCloseService.class), mock(SettingsService.class), mock(CounterpartyService.class));
        Field f = FinanceService.class.getDeclaredField("investmentGoalRepository");
        f.setAccessible(true);
        f.set(finance, goalRepository);

        holding(1, "IMAN 1", "IMAN", "100000000", null);
        holding(2, "IMAN 2", "IMAN", "150000000", "160000000");
        holding(3, "IMAN 3", "IMAN", "50000000", null);
        holding(4, "Open Bank 1", "Open Bank", "80000000", null);
        holding(5, "Open Bank 2", "Open Bank", "30000000", null);
    }

    private Investment holding(long id, String name, String broker, String invested, String value) {
        Investment i = new Investment();
        i.setId(id);
        i.setName(name);
        i.setBroker(broker);
        i.setType(InvestmentType.BUSINESS);
        i.setCurrency(Currency.UZS);
        i.setInvestedAmount(new BigDecimal(invested));
        i.setCurrentValue(value == null ? null : new BigDecimal(value));
        i.setPurchaseDate(LocalDate.of(2026, 6, 1));
        holdings.put(id, i);
        return i;
    }

    private static InvestmentGoalRequest req(String name, String target, Long... ids) {
        InvestmentGoalRequest r = new InvestmentGoalRequest();
        r.setName(name);
        r.setTargetAmount(new BigDecimal(target));
        r.setInvestmentIds(List.of(ids));
        return r;
    }

    @Test
    void removedTypesAreGone() {
        assertThat(Arrays.stream(InvestmentType.values()).map(Enum::name))
                .containsExactly("BUSINESS", "ISLAMIC_BANK", "REAL_ESTATE", "GOLD", "OTHER");
    }

    /** The owner's example: 2 of 3 IMAN accounts and 1 of 2 Open Bank accounts save for the car. */
    @Test
    void goalAddsUpItsAccountsValues() {
        InvestmentGoalResponse car = service.create(req("Mashina", "1000000000", 1L, 2L, 4L));

        assertThat(car.getSaved()).isEqualByComparingTo("340000000"); // 100M + 160M (value) + 80M
        assertThat(car.getLeft()).isEqualByComparingTo("660000000");
        assertThat(car.getProgressPercent()).isEqualByComparingTo("34.0");
        assertThat(car.isReached()).isFalse();
        assertThat(car.getCurrency()).isEqualTo(Currency.UZS);
        assertThat(car.getInvestments()).extracting(InvestmentGoalResponse.Account::getName)
                .containsExactly("IMAN 1", "IMAN 2", "Open Bank 1");
        assertThat(holdings.get(3L).getInvestmentGoalId()).isNull();
        assertThat(holdings.get(5L).getInvestmentGoalId()).isNull();
    }

    @Test
    void reachedWhenSavedMeetsTarget() {
        InvestmentGoalResponse g = service.create(req("Uy", "200000000", 2L, 4L));
        assertThat(g.isReached()).isTrue();
        assertThat(g.getLeft()).isEqualByComparingTo("0");
        assertThat(g.getProgressPercent()).isEqualByComparingTo("120.0");
    }

    @Test
    void editMovesAnAccountFromAnotherGoalAndUnlinksDropped() {
        InvestmentGoalResponse car = service.create(req("Mashina", "1000000000", 1L, 2L));
        InvestmentGoalResponse home = service.create(req("Uy", "500000000", 3L));

        service.update(home.getId(), req("Uy", "500000000", 2L));

        assertThat(holdings.get(2L).getInvestmentGoalId()).isEqualTo(home.getId());
        assertThat(holdings.get(3L).getInvestmentGoalId()).isNull();
        List<InvestmentGoalResponse> all = service.list();
        assertThat(all.get(0).getInvestments()).extracting(InvestmentGoalResponse.Account::getId).containsExactly(1L);
        assertThat(all.get(1).getSaved()).isEqualByComparingTo("160000000");
        assertThat(car.getId()).isNotEqualTo(home.getId());
    }

    @Test
    void deleteUnlinksAndKeepsTheMoney() {
        InvestmentGoalResponse car = service.create(req("Mashina", "1000000000", 1L, 4L));
        service.delete(car.getId());
        assertThat(goals).isEmpty();
        assertThat(holdings.get(1L).getInvestmentGoalId()).isNull();
        assertThat(holdings.get(1L).getInvestedAmount()).isEqualByComparingTo("100000000");
    }

    @Test
    void refusesNonPlainAccountsAndBadInput() {
        holdings.get(5L).setEmergencyFund(true);
        assertThatThrownBy(() -> service.create(req("Mashina", "1000", 1L, 5L)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Open Bank 2 is not an investment account");
        assertThat(goals).isEmpty();
        assertThat(holdings.get(1L).getInvestmentGoalId()).isNull();
        assertThatThrownBy(() -> service.create(req(" ", "1000")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(req("X", "0")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(req("X", "1000", 99L)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(service.create(req("Bo'sh", "1000")).getSaved()).isEqualByComparingTo("0");
    }

    private InvestmentRequest echo(Investment i) {
        InvestmentRequest r = new InvestmentRequest();
        r.setName(i.getName());
        r.setType(i.getType());
        r.setInvestedAmount(i.getInvestedAmount());
        r.setCurrency(i.getCurrency());
        r.setPurchaseDate(i.getPurchaseDate());
        r.setBroker(i.getBroker());
        return r;
    }

    /** The bot edits a holding by echoing its fields — the link stays; the list names the goal. */
    @Test
    void investmentEditKeepsTheLinkAndListNamesIt() {
        InvestmentGoalResponse car = service.create(req("Mashina", "1000000000", 1L));
        InvestmentRequest r = echo(holdings.get(1L));
        r.setName("IMAN 1 (car)");

        InvestmentResponse edited = finance.updateInvestment(1L, r);

        assertThat(edited.getInvestmentGoalId()).isEqualTo(car.getId());
        assertThat(edited.getInvestmentGoalName()).isEqualTo("Mashina");
        InvestmentResponse listed = finance.getAllInvestments(null).stream().filter(x -> x.getId() == 1L).findFirst().orElseThrow();
        assertThat(listed.getInvestmentGoalName()).isEqualTo("Mashina");
        assertThat(finance.getAllInvestments(null).stream().filter(x -> x.getId() == 3L).findFirst().orElseThrow()
                .getInvestmentGoalId()).isNull();
    }

    @Test
    void makingItTheEmergencyFundClearsTheLink() {
        service.create(req("Mashina", "1000000000", 1L));
        InvestmentRequest r = echo(holdings.get(1L));
        r.setEmergencyFund(true);

        InvestmentResponse edited = finance.updateInvestment(1L, r);

        assertThat(holdings.get(1L).getInvestmentGoalId()).isNull();
        assertThat(edited.getInvestmentGoalId()).isNull();
        assertThat(service.list().get(0).getInvestments()).isEmpty();
    }
}
