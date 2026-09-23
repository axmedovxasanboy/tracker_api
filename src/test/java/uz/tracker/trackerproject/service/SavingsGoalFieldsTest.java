package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import uz.tracker.trackerproject.dto.request.InvestmentRequest;
import uz.tracker.trackerproject.dto.response.InvestmentResponse;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.InvestmentType;
import uz.tracker.trackerproject.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A savings goal's deadline, monthly payment and payment start month. The owner asked for them when
 * adding a goal; the bot, kept as it is, edits a goal by sending back the fields it knows — a shape
 * without these three — so an edit that leaves them out must keep them, while one that sends them
 * (null included) sets them.
 */
class SavingsGoalFieldsTest {

    private static final LocalDate JUNE_2027 = LocalDate.of(2027, 6, 1);
    private static final LocalDate OCT_2026 = LocalDate.of(2026, 10, 1);

    private InvestmentRepository investmentRepository;
    private FinanceService service;
    private Investment stored;

    @BeforeEach
    void setUp() {
        investmentRepository = mock(InvestmentRepository.class);
        service = new FinanceService(
                mock(DebtRepository.class),
                mock(LoanGivenRepository.class),
                mock(LoanTakenRepository.class),
                mock(BankLoanRepository.class),
                mock(MonthlyPaymentRepository.class),
                mock(DonationRepository.class),
                investmentRepository,
                mock(CategoryRepository.class),
                mock(TransactionRepository.class),
                mock(CardRepository.class),
                mock(MarkPaidRepository.class),
                mock(CardService.class),
                mock(MonthCloseService.class),
                mock(SettingsService.class),
                mock(CounterpartyService.class));
        when(investmentRepository.save(any(Investment.class))).thenAnswer(inv -> {
            stored = inv.getArgument(0);
            if (stored.getId() == null) stored.setId(9L);
            return stored;
        });
        when(investmentRepository.findById(9L)).thenAnswer(inv -> Optional.ofNullable(stored));
    }

    /** A goal with nothing saved yet — an opening balance of 0, so no wallet is touched. */
    private static InvestmentRequest goal(String name) {
        InvestmentRequest r = new InvestmentRequest();
        r.setName(name);
        r.setType(InvestmentType.OTHER);
        r.setInvestedAmount(BigDecimal.ZERO);
        r.setCurrency(Currency.UZS);
        r.setPurchaseDate(LocalDate.of(2026, 9, 23));
        r.setSavingsGoal(true);
        r.setTargetAmount(new BigDecimal("12000000"));
        r.setOpeningBalance(true);
        return r;
    }

    private InvestmentResponse createCar() {
        InvestmentRequest create = goal("Car");
        create.setTargetDate(JUNE_2027);
        create.setMonthlyContribution(new BigDecimal("1000000"));
        return service.createInvestment(create);
    }

    @Test
    void createAndUpdateCarryTheDeadlineAndTheMonthlyPayment() {
        InvestmentResponse created = createCar();
        assertThat(created.getTargetDate()).isEqualTo(JUNE_2027);
        assertThat(created.getMonthlyContribution()).isEqualByComparingTo("1000000");

        InvestmentRequest edit = goal("Car");
        edit.setTargetDate(LocalDate.of(2027, 9, 1));
        edit.setMonthlyContribution(new BigDecimal("750000"));
        InvestmentResponse updated = service.updateInvestment(9L, edit);

        assertThat(updated.getTargetDate()).isEqualTo(LocalDate.of(2027, 9, 1));
        assertThat(updated.getMonthlyContribution()).isEqualByComparingTo("750000");
    }

    /** The bot's edit: every field it knows, none of the two new ones. */
    @Test
    void anEditThatLeavesThemOutKeepsThem() {
        createCar();

        InvestmentResponse renamed = service.updateInvestment(9L, goal("Car (Cobalt)"));

        assertThat(renamed.getName()).isEqualTo("Car (Cobalt)");
        assertThat(renamed.getTargetDate()).isEqualTo(JUNE_2027);
        assertThat(renamed.getMonthlyContribution()).isEqualByComparingTo("1000000");
    }

    @Test
    void sendingNullClearsIt() {
        createCar();

        InvestmentRequest edit = goal("Car");
        edit.setTargetDate(null);   // "no deadline any more"
        InvestmentResponse updated = service.updateInvestment(9L, edit);

        assertThat(updated.getTargetDate()).isNull();
        assertThat(updated.getMonthlyContribution()).isEqualByComparingTo("1000000"); // left out: kept
    }

    /**
     * Whether a field was sent is decided by the JSON itself: Jackson calls a setter only for a key
     * that is present — an explicit null included. The flags are not properties a client can set.
     */
    @Test
    void theJsonDecidesWhatWasSent() {
        JsonMapper json = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

        InvestmentRequest bot = json.readValue("""
                {"name":"Car","type":"OTHER","investedAmount":0,"currency":"UZS","purchaseDate":"2026-09-23",
                 "savingsGoal":true,"targetAmount":12000000,"targetDateSent":true}""", InvestmentRequest.class);
        assertThat(bot.targetDateGiven()).isFalse();
        assertThat(bot.monthlyContributionGiven()).isFalse();

        InvestmentRequest web = json.readValue("""
                {"name":"Car","targetDate":null,"monthlyContribution":500000}""", InvestmentRequest.class);
        assertThat(web.targetDateGiven()).isTrue();
        assertThat(web.getTargetDate()).isNull();
        assertThat(web.monthlyContributionGiven()).isTrue();
        assertThat(web.getMonthlyContribution()).isEqualByComparingTo("500000");

        InvestmentRequest dated = json.readValue("""
                {"targetDate":"2027-06-01"}""", InvestmentRequest.class);
        assertThat(dated.getTargetDate()).isEqualTo(JUNE_2027);

        // The start month the same way: the bot's shape has no key, the web sends one.
        assertThat(bot.paymentStartDateGiven()).isFalse();
        InvestmentRequest reset = json.readValue("""
                {"name":"Car","paymentStartDate":null}""", InvestmentRequest.class);
        assertThat(reset.paymentStartDateGiven()).isTrue();
        assertThat(reset.getPaymentStartDate()).isNull();
        InvestmentRequest starting = json.readValue("""
                {"paymentStartDate":"2026-10-15"}""", InvestmentRequest.class);
        assertThat(starting.paymentStartDateGiven()).isTrue();
        assertThat(starting.getPaymentStartDate()).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    // ── The payment start month ───────────────────────────────────────────────

    /** Any day of the month is stored as its 1st — the start is a month, not a day. */
    @Test
    void theStartMonthIsStoredAsItsFirstDay() {
        InvestmentRequest create = goal("Car");
        create.setMonthlyContribution(new BigDecimal("3500000"));
        create.setPaymentStartDate(LocalDate.of(2026, 10, 15));

        InvestmentResponse created = service.createInvestment(create);

        assertThat(created.getPaymentStartDate()).isEqualTo(OCT_2026);
        assertThat(stored.getPaymentStartDate()).isEqualTo(OCT_2026);
        assertThat(stored.paymentStartMonth()).isEqualTo(YearMonth.of(2026, 10));

        InvestmentRequest moved = goal("Car");
        moved.setPaymentStartDate(LocalDate.of(2026, 11, 30));
        assertThat(service.updateInvestment(9L, moved).getPaymentStartDate()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    /** None given: the payment starts in the month the goal was created, as every goal did before. */
    @Test
    void withoutAStartMonthThePaymentStartsInThePurchaseMonth() {
        InvestmentResponse created = createCar();

        assertThat(created.getPaymentStartDate()).isNull();
        assertThat(stored.paymentStartMonth()).isEqualTo(YearMonth.of(2026, 9));   // purchased 23 September
    }

    /** The bot's edit keeps the start month; an explicit null resets it to the purchase month. */
    @Test
    void anEditWithoutTheStartMonthKeepsItAndNullResetsIt() {
        InvestmentRequest create = goal("Car");
        create.setMonthlyContribution(new BigDecimal("3500000"));
        create.setPaymentStartDate(OCT_2026);
        service.createInvestment(create);

        InvestmentResponse renamed = service.updateInvestment(9L, goal("Car (Cobalt)"));
        assertThat(renamed.getName()).isEqualTo("Car (Cobalt)");
        assertThat(renamed.getPaymentStartDate()).isEqualTo(OCT_2026);
        assertThat(renamed.getMonthlyContribution()).isEqualByComparingTo("3500000");

        InvestmentRequest reset = goal("Car (Cobalt)");
        reset.setPaymentStartDate(null);
        InvestmentResponse updated = service.updateInvestment(9L, reset);
        assertThat(updated.getPaymentStartDate()).isNull();
        assertThat(stored.paymentStartMonth()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(updated.getMonthlyContribution()).isEqualByComparingTo("3500000");   // left out: kept
    }
}
