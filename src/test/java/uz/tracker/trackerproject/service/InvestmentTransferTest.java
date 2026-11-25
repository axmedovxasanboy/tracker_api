package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.request.InvestmentTransferRequest;
import uz.tracker.trackerproject.dto.response.InvestmentResponse;
import uz.tracker.trackerproject.dto.response.InvestmentTransferResponse;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.InvestmentTransfer;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.InvestmentType;
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
 * Moving money between investments (Batch 78): "like between cards", no transaction, and the totals
 * of put-in and of value across holdings never change. Undo reverses a move exactly.
 */
class InvestmentTransferTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);

    private final Map<Long, Investment> holdings = new HashMap<>();
    private final Map<Long, InvestmentTransfer> moves = new LinkedHashMap<>();
    private InvestmentRepository investmentRepository;
    private InvestmentTransferRepository transferRepository;
    private TransactionRepository transactionRepository;
    private InvestmentTransferService service;
    private long nextMoveId = 1;

    @BeforeEach
    void setUp() {
        investmentRepository = mock(InvestmentRepository.class);
        transferRepository = mock(InvestmentTransferRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        when(investmentRepository.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(holdings.get((Long) inv.getArgument(0))));
        when(investmentRepository.findAll()).thenAnswer(inv -> new ArrayList<>(holdings.values()));
        when(investmentRepository.save(any(Investment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transferRepository.save(any(InvestmentTransfer.class))).thenAnswer(inv -> {
            InvestmentTransfer t = inv.getArgument(0);
            if (t.getId() == null) t.setId(nextMoveId++);
            moves.put(t.getId(), t);
            return t;
        });
        when(transferRepository.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(moves.get((Long) inv.getArgument(0))));
        doAnswer(inv -> moves.remove(((InvestmentTransfer) inv.getArgument(0)).getId()))
                .when(transferRepository).delete(any(InvestmentTransfer.class));
        when(transferRepository.findAllByOrderByTransferDateDescIdDesc()).thenAnswer(inv -> {
            List<InvestmentTransfer> l = new ArrayList<>(moves.values());
            Collections.reverse(l);
            return l;
        });
        service = new InvestmentTransferService(investmentRepository, transferRepository) {
            @Override LocalDate today() { return DAY; }
        };
    }

    private Investment holding(long id, String name, String invested, String value) {
        Investment i = new Investment();
        i.setId(id);
        i.setName(name);
        i.setType(InvestmentType.ISLAMIC_BANK);
        i.setCurrency(Currency.UZS);
        i.setInvestedAmount(new BigDecimal(invested));
        i.setCurrentValue(value == null ? null : new BigDecimal(value));
        i.setPurchaseDate(LocalDate.of(2026, 6, 1));
        holdings.put(id, i);
        return i;
    }

    private static InvestmentTransferRequest move(long from, long to, String amount) {
        InvestmentTransferRequest r = new InvestmentTransferRequest();
        r.setFromId(from);
        r.setToId(to);
        r.setAmount(new BigDecimal(amount));
        r.setDate(DAY);
        return r;
    }

    private static BigDecimal valueOf(Investment i) {
        return i.getCurrentValue() != null ? i.getCurrentValue() : i.getInvestedAmount();
    }

    private BigDecimal totalPutIn() {
        return holdings.values().stream().map(Investment::getInvestedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal totalValue() {
        return holdings.values().stream().map(InvestmentTransferTest::valueOf).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void enumHasTheNewTypes() {
        assertThat(InvestmentType.valueOf("BUSINESS")).isNotNull();
        assertThat(InvestmentType.valueOf("ISLAMIC_BANK")).isNotNull();
        assertThat(InvestmentType.values()[0]).isEqualTo(InvestmentType.BUSINESS);
    }

    /** No value tracked: put in moves one for one, both values stay untracked. */
    @Test
    void untrackedValueMovesPutInOneForOne() {
        Investment iman = holding(1, "IMAN", "12000000", null);
        Investment biz = holding(2, "Biznes A", "3000000", null);

        InvestmentTransferResponse r = service.transfer(move(1, 2, "5000000"));

        assertThat(iman.getInvestedAmount()).isEqualByComparingTo("7000000");
        assertThat(biz.getInvestedAmount()).isEqualByComparingTo("8000000");
        assertThat(iman.getCurrentValue()).isNull();
        assertThat(biz.getCurrentValue()).isNull();
        assertThat(r.getPutInMoved()).isEqualByComparingTo("5000000");
        assertThat(r.getFromName()).isEqualTo("IMAN");
        assertThat(r.getToName()).isEqualTo("Biznes A");
        assertThat(r.isCanUndo()).isTrue();
        verifyNoInteractions(transactionRepository);
    }

    /** IMAN put 10M, worth 11M; moving 5.5M carries 5M of put-in; totals conserved; to starts tracking. */
    @Test
    void growthTravelsProportionallyAndTotalsStayTheSame() {
        Investment iman = holding(1, "IMAN", "10000000", "11000000");
        Investment biz = holding(2, "Biznes A", "2000000", null);
        BigDecimal putIn = totalPutIn(), value = totalValue();

        InvestmentTransferResponse r = service.transfer(move(1, 2, "5500000"));

        assertThat(r.getPutInMoved()).isEqualByComparingTo("5000000");
        assertThat(iman.getInvestedAmount()).isEqualByComparingTo("5000000");
        assertThat(iman.getCurrentValue()).isEqualByComparingTo("5500000");
        assertThat(biz.getInvestedAmount()).isEqualByComparingTo("7000000");
        assertThat(biz.getCurrentValue()).isEqualByComparingTo("7500000");
        assertThat(totalPutIn()).isEqualByComparingTo(putIn);
        assertThat(totalValue()).isEqualByComparingTo(value);
    }

    /** Moving everything leaves exactly 0 put in — no rounding residue. */
    @Test
    void fullMoveLeavesNoResidue() {
        Investment a = holding(1, "A", "1000000", "3333333");
        Investment b = holding(2, "B", "0", "0");

        InvestmentTransferResponse r = service.transfer(move(1, 2, "3333333"));

        assertThat(a.getInvestedAmount()).isEqualByComparingTo("0");
        assertThat(a.getCurrentValue()).isEqualByComparingTo("0");
        assertThat(r.getPutInMoved()).isEqualByComparingTo("1000000");
        assertThat(b.getInvestedAmount()).isEqualByComparingTo("1000000");
        assertThat(b.getCurrentValue()).isEqualByComparingTo("3333333");
    }

    @Test
    void refusals() {
        holding(1, "IMAN", "5000000", null);
        holding(2, "Biznes", "1000000", null);
        Investment goal = holding(3, "Uy", "1000000", null);
        goal.setSavingsGoal(true);
        Investment fund = holding(4, "Zaxira", "1000000", null);
        fund.setEmergencyFund(true);
        Investment own = holding(5, "Mashina", "1000000", null);
        own.setCustomSaving(true);
        Investment usd = holding(6, "USD", "100", null);
        usd.setCurrency(Currency.USD);

        assertThatThrownBy(() -> service.transfer(move(1, 1, "1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.transfer(move(1, 2, "5000001")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("IMAN");
        assertThatThrownBy(() -> service.transfer(move(1, 3, "1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.transfer(move(4, 1, "1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.transfer(move(1, 5, "1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.transfer(move(1, 6, "1"))).isInstanceOf(IllegalArgumentException.class);
        InvestmentTransferRequest future = move(1, 2, "1");
        future.setDate(DAY.plusDays(1));
        assertThatThrownBy(() -> service.transfer(future)).isInstanceOf(IllegalArgumentException.class);
        assertThat(moves).isEmpty();
        assertThat(holdings.get(1L).getInvestedAmount()).isEqualByComparingTo("5000000");
    }

    @Test
    void undoRestoresBothExactly() {
        Investment iman = holding(1, "IMAN", "10000000", "11000000");
        Investment biz = holding(2, "Biznes A", "2000000", null);
        InvestmentTransferResponse r = service.transfer(move(1, 2, "5500000"));

        service.undo(r.getId());

        assertThat(iman.getInvestedAmount()).isEqualByComparingTo("10000000");
        assertThat(iman.getCurrentValue()).isEqualByComparingTo("11000000");
        assertThat(biz.getInvestedAmount()).isEqualByComparingTo("2000000");
        assertThat(valueOf(biz)).isEqualByComparingTo("2000000");
        assertThat(moves).isEmpty();
    }

    @Test
    void undoIsRefusedWhenASideIsGoneOrToHasLess() {
        holding(1, "IMAN", "5000000", null);
        Investment biz = holding(2, "Biznes", "0", null);
        InvestmentTransferResponse r = service.transfer(move(1, 2, "3000000"));

        biz.setInvestedAmount(new BigDecimal("1000000"));          // 2M taken out since
        assertThat(service.list(null, null)).singleElement().satisfies(x -> assertThat(x.isCanUndo()).isFalse());
        assertThatThrownBy(() -> service.undo(r.getId()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Biznes");

        moves.get(r.getId()).setToInvestmentId(null);              // the holding was deleted
        holdings.remove(2L);
        assertThatThrownBy(() -> service.undo(r.getId()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deleted");
        InvestmentTransferResponse listed = service.list(null, null).getFirst();
        assertThat(listed.getToId()).isNull();
        assertThat(listed.getToName()).isEqualTo("Biznes");
        assertThat(listed.isCanUndo()).isFalse();
    }

    @Test
    void listHonoursLimit() {
        holding(1, "A", "5000000", null);
        holding(2, "B", "0", null);
        service.transfer(move(1, 2, "1000"));
        service.transfer(move(1, 2, "2000"));
        service.transfer(move(2, 1, "500"));
        List<InvestmentTransferResponse> l = service.list(null, 2);
        assertThat(l).hasSize(2);
        assertThat(l.getFirst().getAmount()).isEqualByComparingTo("500");
    }

    /** FinanceService: deleting a holding detaches its moves; the list carries lastMovedAt. */
    @Test
    void financeServiceDetachesOnDeleteAndShowsLastMove() throws Exception {
        Investment a = holding(1, "A", "5000000", null);
        a.setOriginatingTransactionId(null);
        Investment b = holding(2, "B", "0", null);
        b.setPurchaseDate(LocalDate.of(2026, 7, 1));
        b.setOriginatingTransactionId(77L);
        when(investmentRepository.findAllByOrderByPurchaseDateDesc()).thenReturn(List.of(a, b));
        when(transactionRepository.findByInvestmentIdOrderByTransactionDateDesc(anyLong())).thenReturn(List.of());
        when(transactionRepository.findById(77L)).thenReturn(Optional.empty());
        List<Object[]> txDates = new ArrayList<>();
        txDates.add(new Object[]{1L, LocalDate.of(2026, 10, 3)});
        when(transactionRepository.latestDateByInvestment()).thenReturn(txDates);
        List<Object[]> fromDates = new ArrayList<>();
        fromDates.add(new Object[]{1L, LocalDate.of(2026, 11, 12)});
        when(transferRepository.latestMoveFrom()).thenReturn(fromDates);
        when(transferRepository.latestMoveTo()).thenReturn(List.of());

        FinanceService finance = new FinanceService(mock(DebtRepository.class), mock(LoanGivenRepository.class),
                mock(LoanTakenRepository.class), mock(BankLoanRepository.class), mock(MonthlyPaymentRepository.class),
                mock(DonationRepository.class), investmentRepository, mock(CategoryRepository.class), transactionRepository,
                mock(CardRepository.class), mock(MarkPaidRepository.class), mock(CardService.class),
                mock(MonthCloseService.class), mock(SettingsService.class), mock(CounterpartyService.class));
        Field f = FinanceService.class.getDeclaredField("investmentTransferRepository");
        f.setAccessible(true);
        f.set(finance, transferRepository);

        List<InvestmentResponse> list = finance.getAllInvestments(null);
        assertThat(list.get(0).getLastMovedAt()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(list.get(1).getLastMovedAt()).isEqualTo(LocalDate.of(2026, 7, 1));

        finance.deleteInvestment(2L);
        verify(transferRepository).detachFrom(2L);
        verify(transferRepository).detachTo(2L);
        verify(investmentRepository).delete(b);
        verify(transactionRepository, never()).save(any(Transaction.class));
    }
}
