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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A savings goal's deadline and monthly payment. The owner asked for both when adding a goal; the
 * bot, kept as it is, edits a goal by sending back the fields it knows — a shape without these two
 * — so an edit that leaves them out must keep them, while one that sends them (null included) sets
 * them.
 */
class SavingsGoalFieldsTest {

    private static final LocalDate JUNE_2027 = LocalDate.of(2027, 6, 1);

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
                mock(SettingsService.class));
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
    }
}
