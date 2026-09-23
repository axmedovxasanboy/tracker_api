package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.request.DebtRequest;
import uz.tracker.trackerproject.dto.request.LoanGivenRequest;
import uz.tracker.trackerproject.dto.request.LoanTakenRequest;
import uz.tracker.trackerproject.dto.request.PersonRequest;
import uz.tracker.trackerproject.dto.response.LoanTakenResponse;
import uz.tracker.trackerproject.dto.response.PersonResponse;
import uz.tracker.trackerproject.entity.Counterparty;
import uz.tracker.trackerproject.entity.Debt;
import uz.tracker.trackerproject.entity.LoanGiven;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.enums.CounterpartyKind;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.enums.RepaymentType;
import uz.tracker.trackerproject.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The owner's lenders and borrowers: the same person borrowed from again and again is one row that
 * adds up, whatever the case or spacing the name was typed with; old records are linked by name at
 * boot; the bot, which sends names only, finds or adds the person; a rename reaches every record.
 */
class PeopleTest {

    private final List<Counterparty> people = new ArrayList<>();
    private final List<LoanTaken> loans = new ArrayList<>();
    private final List<Debt> debts = new ArrayList<>();
    private final List<LoanGiven> lent = new ArrayList<>();
    private CounterpartyService service;
    private FinanceService finance;
    private long nextId = 100;

    @BeforeEach
    void setUp() {
        CounterpartyRepository counterpartyRepository = mock(CounterpartyRepository.class);
        when(counterpartyRepository.findByKindAndNameKey(any(), any())).thenAnswer(inv -> people.stream()
                .filter(c -> c.getKind() == inv.getArgument(0) && c.getNameKey().equals(inv.getArgument(1))).findFirst());
        when(counterpartyRepository.findByKind(any())).thenAnswer(inv -> people.stream()
                .filter(c -> c.getKind() == inv.getArgument(0)).toList());
        when(counterpartyRepository.findById(any())).thenAnswer(inv -> people.stream()
                .filter(c -> c.getId().equals(inv.getArgument(0))).findFirst());
        when(counterpartyRepository.save(any(Counterparty.class))).thenAnswer(inv -> {
            Counterparty c = inv.getArgument(0);
            if (c.getId() == null) {
                c.setId(nextId++);
                people.add(c);
            }
            return c;
        });

        LoanTakenRepository loanTakenRepository = mock(LoanTakenRepository.class);
        when(loanTakenRepository.findAll()).thenReturn(loans);
        when(loanTakenRepository.findByLenderId(any())).thenAnswer(inv -> loans.stream()
                .filter(l -> Objects.equals(l.getLenderId(), inv.getArgument(0))).toList());
        when(loanTakenRepository.save(any(LoanTaken.class))).thenAnswer(inv -> saved(loans, inv.getArgument(0)));
        when(loanTakenRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(loanTakenRepository.findById(any())).thenAnswer(inv -> loans.stream()
                .filter(l -> l.getId().equals(inv.getArgument(0))).findFirst());
        DebtRepository debtRepository = mock(DebtRepository.class);
        when(debtRepository.findAll()).thenReturn(debts);
        when(debtRepository.findByLenderId(any())).thenAnswer(inv -> debts.stream()
                .filter(d -> Objects.equals(d.getLenderId(), inv.getArgument(0))).toList());
        when(debtRepository.save(any(Debt.class))).thenAnswer(inv -> saved(debts, inv.getArgument(0)));
        when(debtRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        LoanGivenRepository loanGivenRepository = mock(LoanGivenRepository.class);
        when(loanGivenRepository.findAll()).thenReturn(lent);
        when(loanGivenRepository.findByBorrowerId(any())).thenAnswer(inv -> lent.stream()
                .filter(l -> Objects.equals(l.getBorrowerId(), inv.getArgument(0))).toList());
        when(loanGivenRepository.save(any(LoanGiven.class))).thenAnswer(inv -> saved(lent, inv.getArgument(0)));
        when(loanGivenRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service = new CounterpartyService(counterpartyRepository, loanTakenRepository, debtRepository, loanGivenRepository);
        finance = new FinanceService(debtRepository, loanGivenRepository, loanTakenRepository,
                mock(BankLoanRepository.class), mock(MonthlyPaymentRepository.class), mock(DonationRepository.class),
                mock(InvestmentRepository.class), mock(CategoryRepository.class), mock(TransactionRepository.class),
                mock(CardRepository.class), mock(MarkPaidRepository.class), mock(CardService.class),
                mock(MonthCloseService.class), mock(SettingsService.class), service);
    }

    private <T> T saved(List<T> rows, T row) {
        if (!rows.contains(row)) rows.add(row);
        try {
            var id = row.getClass().getMethod("getId");
            if (id.invoke(row) == null) row.getClass().getMethod("setId", Long.class).invoke(row, nextId++);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return row;
    }

    private LoanTaken loan(String name, String total, String paid, LocalDate borrowed) {
        LoanTaken l = new LoanTaken();
        l.setId(nextId++);
        l.setLenderName(name);
        l.setTotalAmount(new BigDecimal(total));
        l.setPaidAmount(new BigDecimal(paid));
        l.setCurrency(Currency.UZS);
        l.setBorrowedDate(borrowed);
        l.setStatus(new BigDecimal(paid).compareTo(new BigDecimal(total)) >= 0 ? RecordStatus.PAID : RecordStatus.PENDING);
        loans.add(l);
        return l;
    }

    private Debt debt(String name, String total, LocalDate borrowed) {
        Debt d = new Debt();
        d.setId(nextId++);
        d.setCreditorName(name);
        d.setTotalAmount(new BigDecimal(total));
        d.setPaidAmount(BigDecimal.ZERO);
        d.setCurrency(Currency.UZS);
        d.setBorrowedDate(borrowed);
        d.setStatus(RecordStatus.PENDING);
        debts.add(d);
        return d;
    }

    private LoanGiven lentTo(String name, String total, String received, LocalDate on) {
        LoanGiven l = new LoanGiven();
        l.setId(nextId++);
        l.setDebtorName(name);
        l.setTotalAmount(new BigDecimal(total));
        l.setReceivedAmount(new BigDecimal(received));
        l.setCurrency(Currency.UZS);
        l.setLentDate(on);
        l.setStatus(RecordStatus.PENDING);
        lent.add(l);
        return l;
    }

    private static LoanTakenRequest borrowed(String name, Long lenderId, String total) {
        LoanTakenRequest r = new LoanTakenRequest();
        r.setLenderName(name);
        r.setLenderId(lenderId);
        r.setTotalAmount(new BigDecimal(total));
        r.setCurrency(Currency.UZS);
        r.setBorrowedDate(LocalDate.of(2026, 9, 14));
        return r;
    }

    /** The owner's records, linked at boot: "Uzum Bank" and " uzum bank" are one lender; a second run links nothing. */
    @Test
    void theBootBackfillLinksByNameIgnoringCaseAndSpacesAndRunsOnce() {
        LoanTaken parents = loan("Ota-onam (parents)", "50000000", "0", LocalDate.of(2026, 9, 1));
        LoanTaken uzum = loan("Uzum Bank", "1155000", "1155000", LocalDate.of(2026, 9, 14));
        LoanTaken uzumAgain = loan(" uzum bank ", "600000", "0", LocalDate.of(2026, 9, 20));
        Debt nasiya = debt("Uzum Nasiya", "800000", LocalDate.of(2026, 9, 14));
        LoanGiven mirjalol = lentTo("Mirjalol Sulaymonov", "300000", "300000", LocalDate.of(2026, 8, 30));

        assertThat(service.backfillLinks()).isEqualTo(5);

        assertThat(people).extracting(Counterparty::getName, Counterparty::getKind).containsExactlyInAnyOrder(
                tuple("Ota-onam (parents)", CounterpartyKind.LENDER), tuple("Uzum Bank", CounterpartyKind.LENDER),
                tuple("Uzum Nasiya", CounterpartyKind.LENDER), tuple("Mirjalol Sulaymonov", CounterpartyKind.BORROWER));
        assertThat(uzumAgain.getLenderId()).isEqualTo(uzum.getLenderId());
        assertThat(parents.getLenderId()).isNotNull().isNotEqualTo(uzum.getLenderId());
        assertThat(nasiya.getLenderId()).isNotNull();
        assertThat(mirjalol.getBorrowerId()).isNotNull();

        assertThat(service.backfillLinks()).isZero();
        assertThat(people).hasSize(4);
    }

    /**
     * The bot sends a name only: the person on the list under it, whatever its case, else a new one.
     * The web sends the person's id: the record takes the person's name. A borrower's id is not a lender.
     */
    @Test
    void aNameFindsOrAddsThePersonAndAnIdTakesThePersonsName() {
        Counterparty uzum = service.findOrCreate("Uzum Bank", CounterpartyKind.LENDER);

        LoanTakenResponse bot = finance.createLoanTaken(borrowed("UZUM BANK", null, "600000"));
        assertThat(bot.getLenderId()).isEqualTo(uzum.getId());
        assertThat(bot.getLenderName()).isEqualTo("UZUM BANK");
        assertThat(bot.getRepaymentType()).isEqualTo(RepaymentType.ASAP);     // no type, no plan

        LoanTakenResponse fresh = finance.createLoanTaken(borrowed("Aziz aka", null, "2000000"));
        assertThat(fresh.getLenderId()).isNotNull().isNotEqualTo(uzum.getId());
        assertThat(people).extracting(Counterparty::getName).contains("Aziz aka");

        LoanTakenResponse web = finance.createLoanTaken(borrowed(null, uzum.getId(), "400000"));
        assertThat(web.getLenderName()).isEqualTo("Uzum Bank");

        Counterparty borrower = service.findOrCreate("Mirjalol Sulaymonov", CounterpartyKind.BORROWER);
        assertThatThrownBy(() -> finance.createLoanTaken(borrowed(null, borrower.getId(), "400000")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> finance.createLoanTaken(borrowed("  ", null, "400000")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A debt's creditor is a lender, and money lent goes on the borrowers list — found or added the same way. */
    @Test
    void debtsAndMoneyLentAreLinkedToo() {
        DebtRequest shop = new DebtRequest();
        shop.setCreditorName("Korzinka");
        shop.setTotalAmount(new BigDecimal("300000"));
        shop.setCurrency(Currency.UZS);
        shop.setBorrowedDate(LocalDate.of(2026, 9, 2));
        assertThat(finance.createDebt(shop).getLenderId())
                .isEqualTo(service.findOrCreate("korzinka", CounterpartyKind.LENDER).getId());

        LoanGivenRequest given = new LoanGivenRequest();
        given.setDebtorName("Mirjalol Sulaymonov");
        given.setTotalAmount(new BigDecimal("300000"));
        given.setCurrency(Currency.UZS);
        given.setLentDate(LocalDate.of(2026, 9, 5));
        assertThat(finance.createLoanGiven(given).getBorrowerId())
                .isEqualTo(service.findOrCreate("Mirjalol Sulaymonov", CounterpartyKind.BORROWER).getId());
        assertThat(service.findOrCreate("Mirjalol Sulaymonov", CounterpartyKind.LENDER).getId())
                .isNotEqualTo(lent.getFirst().getBorrowerId());                   // two lists, two rows
    }

    /**
     * Who the owner borrows from most: every record with a person, added up — how many times, how much
     * in all, how much is still owed on what is not settled, and when last — the largest total first.
     */
    @Test
    void theListsAddUpEachPersonsRecordsLargestFirst() {
        loan("Uzum Bank", "1155000", "1155000", LocalDate.of(2026, 9, 14));
        loan("Uzum Bank", "600000", "100000", LocalDate.of(2026, 9, 20));
        debt("Uzum bank", "300000", LocalDate.of(2026, 9, 21));
        loan("Ota-onam (parents)", "50000000", "0", LocalDate.of(2026, 9, 1));
        lentTo("Mirjalol Sulaymonov", "300000", "300000", LocalDate.of(2026, 8, 30));
        lentTo("Mirjalol Sulaymonov", "500000", "100000", LocalDate.of(2026, 9, 10));
        service.backfillLinks();
        service.create(person("Aziz aka", CounterpartyKind.LENDER));            // on the list, nothing borrowed yet

        assertThat(service.list(CounterpartyKind.LENDER))
                .extracting(PersonResponse::getName, PersonResponse::getTimes, p -> p.getTotal().toPlainString(),
                        p -> p.getOpen().toPlainString(), PersonResponse::getLastDate)
                .containsExactly(
                        tuple("Ota-onam (parents)", 1, "50000000", "50000000", LocalDate.of(2026, 9, 1)),
                        tuple("Uzum Bank", 3, "2055000", "800000", LocalDate.of(2026, 9, 21)),
                        tuple("Aziz aka", 0, "0", "0", null));
        assertThat(service.list(CounterpartyKind.BORROWER))
                .extracting(PersonResponse::getName, PersonResponse::getTimes, p -> p.getTotal().toPlainString(),
                        p -> p.getOpen().toPlainString())
                .containsExactly(tuple("Mirjalol Sulaymonov", 2, "800000", "400000"));
    }

    /** Adding a name already on the list hands back that person; the other list is another row. */
    @Test
    void addingANameTwiceHandsBackTheFirst() {
        CounterpartyService.Saved first = service.create(person("Uzum Nasiya", CounterpartyKind.LENDER));
        CounterpartyService.Saved again = service.create(person("  UZUM NASIYA", CounterpartyKind.LENDER));

        assertThat(first.created()).isTrue();
        assertThat(again.created()).isFalse();
        assertThat(again.person().getId()).isEqualTo(first.person().getId());
        assertThat(again.person().getName()).isEqualTo("Uzum Nasiya");
        assertThat(service.create(person("Uzum Nasiya", CounterpartyKind.BORROWER)).created()).isTrue();
    }

    /** A rename rewrites the name the bot shows on every linked record; another person's name is refused. */
    @Test
    void aRenameReachesEveryLinkedRecord() {
        LoanTaken l = loan("uzum", "600000", "0", LocalDate.of(2026, 9, 20));
        Debt d = debt("Uzum", "300000", LocalDate.of(2026, 9, 21));
        loan("Ota-onam", "50000000", "0", LocalDate.of(2026, 9, 1));
        service.backfillLinks();

        PersonResponse renamed = service.rename(l.getLenderId(), person("Uzum Bank", null));

        assertThat(renamed.getName()).isEqualTo("Uzum Bank");
        assertThat(l.getLenderName()).isEqualTo("Uzum Bank");
        assertThat(d.getCreditorName()).isEqualTo("Uzum Bank");
        assertThat(service.rename(l.getLenderId(), person("uzum BANK", null)).getName()).isEqualTo("uzum BANK");
        assertThatThrownBy(() -> service.rename(l.getLenderId(), person("OTA-ONAM", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PersonRequest person(String name, CounterpartyKind kind) {
        PersonRequest r = new PersonRequest();
        r.setName(name);
        r.setKind(kind);
        return r;
    }
}
