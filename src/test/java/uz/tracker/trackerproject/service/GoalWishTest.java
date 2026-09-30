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
 * A savings goal is a PLAN (money is set aside for its monthly payment) or a WISH (kept on the
 * list; asks for nothing). The owner flips goals between the two and must lose nothing doing so:
 * the {@code wish} flag is all that changes — the monthly payment, its start month and the deadline
 * stay stored — and, like them, it survives an edit that does not mention it (the bot's).
 */
class GoalWishTest {

    private static final LocalDate DEC_2026 = LocalDate.of(2026, 12, 31);
    private static final LocalDate OCT_2026 = LocalDate.of(2026, 10, 1);

    private FinanceService service;
    private Investment stored;

    @BeforeEach
    void setUp() {
        InvestmentRepository investmentRepository = mock(InvestmentRepository.class);
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

    /** The fields the bot knows — a goal with nothing saved yet, so no wallet is touched. */
    private static InvestmentRequest goal(String name) {
        InvestmentRequest r = new InvestmentRequest();
        r.setName(name);
        r.setType(InvestmentType.OTHER);
        r.setInvestedAmount(BigDecimal.ZERO);
        r.setCurrency(Currency.UZS);
        r.setPurchaseDate(LocalDate.of(2026, 9, 23));
        r.setSavingsGoal(true);
        r.setTargetAmount(new BigDecimal("10000000"));
        r.setOpeningBalance(true);
        return r;
    }

    /** A plan: 3,500,000 a month from October, by the end of December. */
    private InvestmentResponse createMacBook() {
        InvestmentRequest create = goal("MacBook");
        create.setMonthlyContribution(new BigDecimal("3500000"));
        create.setPaymentStartDate(OCT_2026);
        create.setTargetDate(DEC_2026);
        return service.createInvestment(create);
    }

    private void assertThePlanIsKept(InvestmentResponse r) {
        assertThat(r.getMonthlyContribution()).isEqualByComparingTo("3500000");
        assertThat(r.getPaymentStartDate()).isEqualTo(OCT_2026);
        assertThat(r.getTargetDate()).isEqualTo(DEC_2026);
    }

    @Test
    void aGoalWithAMonthlyPaymentIsAPlanUntilToldOtherwise() {
        InvestmentResponse created = createMacBook();

        assertThat(created.isWish()).isFalse();
        assertThat(created.getGoalKind()).isEqualTo("PLAN");
        assertThat(stored.getWish()).isNull();                    // the column is nullable: null = not a wish
    }

    @Test
    void makingItAWishChangesOnlyTheFlag_andMakingItAPlanAgainRestoresThePlan() {
        createMacBook();

        InvestmentRequest toWish = goal("MacBook");               // the quick switch: no plan fields sent
        toWish.setWish(true);
        InvestmentResponse wish = service.updateInvestment(9L, toWish);

        assertThat(wish.isWish()).isTrue();
        assertThat(wish.getGoalKind()).isEqualTo("WISH");
        assertThePlanIsKept(wish);

        InvestmentRequest toPlan = goal("MacBook");
        toPlan.setWish(false);
        InvestmentResponse plan = service.updateInvestment(9L, toPlan);

        assertThat(plan.isWish()).isFalse();
        assertThat(plan.getGoalKind()).isEqualTo("PLAN");
        assertThePlanIsKept(plan);
    }

    /** The bot's edit: every field it knows, and no {@code wish} key — the stored value stays. */
    @Test
    void anEditThatDoesNotMentionWishKeepsIt() {
        createMacBook();
        InvestmentRequest toWish = goal("MacBook");
        toWish.setWish(true);
        service.updateInvestment(9L, toWish);

        InvestmentResponse renamed = service.updateInvestment(9L, goal("MacBook Pro"));

        assertThat(renamed.getName()).isEqualTo("MacBook Pro");
        assertThat(renamed.isWish()).isTrue();
        assertThat(renamed.getGoalKind()).isEqualTo("WISH");
        assertThePlanIsKept(renamed);
    }

    @Test
    void aGoalCanBeCreatedAsAWish_withOrWithoutAMonthlyPayment() {
        InvestmentRequest bare = goal("Umra");                    // only a name and a target
        bare.setWish(true);
        InvestmentResponse created = service.createInvestment(bare);
        assertThat(created.isWish()).isTrue();
        assertThat(created.getGoalKind()).isEqualTo("WISH");
        assertThat(created.getMonthlyContribution()).isNull();
    }

    /** No monthly payment is a wish too, whatever the flag says: there is nothing to set aside. */
    @Test
    void aGoalWithNoMonthlyPaymentIsAWish_evenWithTheFlagOff() {
        InvestmentResponse none = service.createInvestment(goal("Umra"));
        assertThat(none.isWish()).isFalse();
        assertThat(none.getGoalKind()).isEqualTo("WISH");

        InvestmentRequest zero = goal("Umra");
        zero.setMonthlyContribution(BigDecimal.ZERO);
        zero.setWish(false);
        assertThat(service.updateInvestment(9L, zero).getGoalKind()).isEqualTo("WISH");

        InvestmentRequest paying = goal("Umra");
        paying.setMonthlyContribution(new BigDecimal("500000"));
        assertThat(service.updateInvestment(9L, paying).getGoalKind()).isEqualTo("PLAN");
    }

    @Test
    void onlyASavingsGoalHasAKind_andTheFlagIsIgnoredOnAnythingElse() {
        InvestmentRequest holding = goal("Aksiya");
        holding.setSavingsGoal(false);
        holding.setWish(true);
        InvestmentResponse created = service.createInvestment(holding);

        assertThat(created.isWish()).isFalse();
        assertThat(created.getGoalKind()).isNull();
        assertThat(stored.getWish()).isNull();                    // never stored on a plain holding
    }

    /** Whether {@code wish} was sent is decided by the JSON itself, as for the other goal fields. */
    @Test
    void theJsonDecidesWhetherWishWasSent() {
        JsonMapper json = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

        InvestmentRequest bot = json.readValue("""
                {"name":"MacBook","type":"OTHER","investedAmount":0,"currency":"UZS","purchaseDate":"2026-09-23",
                 "savingsGoal":true,"targetAmount":10000000,"wishSent":true}""", InvestmentRequest.class);
        assertThat(bot.wishGiven()).isFalse();

        InvestmentRequest web = json.readValue("""
                {"name":"MacBook","wish":true}""", InvestmentRequest.class);
        assertThat(web.wishGiven()).isTrue();
        assertThat(web.getWish()).isTrue();
        assertThat(web.monthlyContributionGiven()).isFalse();     // the switch sends nothing else
        assertThat(web.paymentStartDateGiven()).isFalse();
        assertThat(web.targetDateGiven()).isFalse();

        InvestmentRequest off = json.readValue("""
                {"name":"MacBook","wish":false}""", InvestmentRequest.class);
        assertThat(off.wishGiven()).isTrue();
        assertThat(off.getWish()).isFalse();
    }

    /** A row from before the column existed reads as a plan, with no backfill. */
    @Test
    void aStoredNullIsNotAWish() {
        Investment old = new Investment();
        old.setSavingsGoal(true);
        old.setMonthlyContribution(new BigDecimal("1000000"));
        old.setInvestedAmount(BigDecimal.ZERO);

        assertThat(old.getWish()).isNull();
        assertThat(old.goalKind()).isEqualTo("PLAN");
        assertThat(InvestmentResponse.from(old).isWish()).isFalse();
        assertThat(InvestmentResponse.from(old).getGoalKind()).isEqualTo("PLAN");
    }
}
