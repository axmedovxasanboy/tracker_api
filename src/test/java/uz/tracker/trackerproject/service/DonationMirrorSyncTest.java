package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.request.DonationRequest;
import uz.tracker.trackerproject.entity.Card;
import uz.tracker.trackerproject.entity.Donation;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.BankLoanRepository;
import uz.tracker.trackerproject.repository.CardRepository;
import uz.tracker.trackerproject.repository.CategoryRepository;
import uz.tracker.trackerproject.repository.DebtRepository;
import uz.tracker.trackerproject.repository.DonationRepository;
import uz.tracker.trackerproject.repository.InvestmentRepository;
import uz.tracker.trackerproject.repository.LoanGivenRepository;
import uz.tracker.trackerproject.repository.LoanTakenRepository;
import uz.tracker.trackerproject.repository.MarkPaidRepository;
import uz.tracker.trackerproject.repository.MonthlyPaymentRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A donation is one payment recorded in two places: the DONATION transaction (what the wallet
 * balance and the Transactions list read) and the Donations tab's own row (what the Donation
 * bucket sums). Editing or deleting from the tab moved only the row — the bucket showed the new
 * figure while the wallet and the list kept the old one, and a deleted donation stayed spent.
 * The Emergencies tab had the same defect and the same fix (EmergencyMirrorSyncTest).
 */
class DonationMirrorSyncTest {

    private DonationRepository donationRepository;
    private TransactionRepository transactionRepository;
    private CardService cardService;
    private MonthCloseService monthCloseService;
    private FinanceService service;

    private static final YearMonth AUG = YearMonth.of(2026, 8);
    private static final LocalDate AUG_15 = AUG.atDay(15);

    @BeforeEach
    void setUp() {
        donationRepository = mock(DonationRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        cardService = mock(CardService.class);
        monthCloseService = mock(MonthCloseService.class);
        when(donationRepository.save(any(Donation.class))).thenAnswer(i -> i.getArgument(0));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        service = new FinanceService(
                mock(DebtRepository.class),
                mock(LoanGivenRepository.class),
                mock(LoanTakenRepository.class),
                mock(BankLoanRepository.class),
                mock(MonthlyPaymentRepository.class),
                donationRepository,
                mock(InvestmentRepository.class),
                mock(CategoryRepository.class),
                transactionRepository,
                mock(CardRepository.class),
                mock(MarkPaidRepository.class),
                cardService,
                monthCloseService,
                mock(SettingsService.class));
    }

    private DonationRequest request(String amount, LocalDate date, String description) {
        DonationRequest req = new DonationRequest();
        req.setRecipientName("Mehr uyi");
        req.setAmount(new BigDecimal(amount));
        req.setCurrency(Currency.UZS);
        req.setDonationDate(date);
        req.setDescription(description);
        req.setAnonymous(false);
        return req;
    }

    private Donation donation(Long mirrorId, String amount) {
        Donation d = new Donation();
        d.setId(40L);
        d.setRecipientName("Mehr uyi");
        d.setAmount(new BigDecimal(amount));
        d.setCurrency(Currency.UZS);
        d.setDonationDate(AUG_15);
        d.setAnonymous(false);
        d.setOriginatingTransactionId(mirrorId);
        when(donationRepository.findById(40L)).thenReturn(Optional.of(d));
        return d;
    }

    /** The DONATION transaction behind a donation: cash unless a card is given; split when both. */
    private Transaction mirror(long id, String amount, Card card, String cash) {
        Transaction tx = new Transaction();
        tx.setId(id);
        tx.setType(TransactionType.EXPENSE);
        tx.setSubType(TransactionSubType.DONATION);
        tx.setAmount(new BigDecimal(amount));
        tx.setCurrency(Currency.UZS);
        tx.setTransactionDate(AUG_15);
        tx.setDescription("Donation to Mehr uyi");
        tx.setCard(card);
        tx.setCashAmount(new BigDecimal(cash));
        when(transactionRepository.findById(id)).thenReturn(Optional.of(tx));
        return tx;
    }

    private static Card card() {
        Card c = new Card();
        c.setId(3L);
        c.setName("Humo");
        c.setCurrency(Currency.UZS);
        c.setInitialBalance(new BigDecimal("5000000"));
        return c;
    }

    /** Editing the amount on the tab must move the money too, or only the bucket shows it. */
    @Test
    void editingADonationMovesItsTransactionWithIt() {
        donation(700L, "500000");
        Transaction tx = mirror(700L, "500000", null, "500000");

        service.updateDonation(40L, request("800000", AUG.atDay(20), "Ramadan"));

        assertThat(tx.getAmount()).isEqualByComparingTo("800000");
        assertThat(tx.getTransactionDate()).isEqualTo(AUG.atDay(20));
        assertThat(tx.getDescription()).isEqualTo("Ramadan");
        assertThat(tx.getCashAmount()).isEqualByComparingTo("800000"); // a cash row carries it all
        verify(transactionRepository).save(tx);
        verify(monthCloseService, atLeastOnce()).assertMonthOpen(AUG_15);
        verify(monthCloseService).assertMonthOpen(AUG.atDay(20));
    }

    /** With no description typed, the transaction keeps the title a new donation gets. */
    @Test
    void aDonationWithoutADescriptionIsTitledAfterItsRecipient() {
        donation(700L, "500000");
        Transaction tx = mirror(700L, "500000", null, "500000");
        DonationRequest req = request("500000", AUG_15, "  ");
        req.setRecipientName("Mehribonlik");

        service.updateDonation(40L, req);

        assertThat(tx.getDescription()).isEqualTo("Donation to Mehribonlik");
    }

    /** A card row keeps its card; only the extra amount has to be available on it. */
    @Test
    void growingACardDonationChecksOnlyTheExtraAgainstTheCard() {
        donation(700L, "500000");
        Card card = card();
        Transaction tx = mirror(700L, "500000", card, "0");

        service.updateDonation(40L, request("800000", AUG_15, null));

        verify(cardService).assertSufficientBalance(card, new BigDecimal("300000"));
        assertThat(tx.getCard()).isSameAs(card);
        assertThat(tx.getCashAmount()).isEqualByComparingTo("0");
        assertThat(tx.getAmount()).isEqualByComparingTo("800000");
    }

    /**
     * Paid partly in cash and partly by card, a new total does not say which part changed, so the
     * tab refuses the amount rather than guess — everything else about the donation still edits.
     */
    @Test
    void aSplitDonationRefusesANewAmountButTakesOtherEdits() {
        donation(700L, "500000");
        Transaction tx = mirror(700L, "500000", card(), "200000");

        assertThatThrownBy(() -> service.updateDonation(40L, request("800000", AUG_15, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("partly in cash and partly by card");
        assertThat(tx.getAmount()).isEqualByComparingTo("500000");

        service.updateDonation(40L, request("500000", AUG.atDay(18), "Moved a few days"));

        assertThat(tx.getTransactionDate()).isEqualTo(AUG.atDay(18));
        assertThat(tx.getCashAmount()).isEqualByComparingTo("200000"); // the split is kept
    }

    /** Deleting the row alone used to leave the wallet debited and the payment in Transactions. */
    @Test
    void deletingADonationTakesItsTransactionWithIt() {
        Donation d = donation(700L, "500000");
        Transaction tx = mirror(700L, "500000", null, "500000");

        service.deleteDonation(40L);

        verify(transactionRepository).delete(tx);
        verify(donationRepository).delete(d);
    }

    /**
     * An edit made before the two were kept together can leave the transaction in another month
     * than the donation. Undoing it changes THAT month's wallet, so a closed one must refuse.
     */
    @Test
    void aTransactionLeftInAClosedMonthBlocksTheDelete() {
        Donation d = donation(700L, "500000");
        d.setDonationDate(YearMonth.of(2026, 9).atDay(3));
        mirror(700L, "500000", null, "500000"); // still dated August
        doThrow(new IllegalArgumentException("The month 2026-08 is closed and locked"))
                .when(monthCloseService).assertMonthOpen(AUG_15);

        assertThatThrownBy(() -> service.deleteDonation(40L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("closed");
        verify(transactionRepository, never()).delete(any(Transaction.class));
        verify(donationRepository, never()).delete(any(Donation.class));
    }

    /** Rows from before the link have no transaction to move — edit them, don't refuse them. */
    @Test
    void aLegacyDonationWithNoTransactionIsStillEditableAndDeletable() {
        Donation d = donation(null, "500000");

        service.updateDonation(40L, request("800000", AUG_15, "Corrected"));
        assertThat(d.getAmount()).isEqualByComparingTo("800000");

        service.deleteDonation(40L);
        verify(donationRepository).delete(d);
        verify(transactionRepository, never()).delete(any(Transaction.class));
    }
}
