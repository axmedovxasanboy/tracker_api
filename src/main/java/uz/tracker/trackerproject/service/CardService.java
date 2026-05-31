package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.request.CardRequest;
import uz.tracker.trackerproject.dto.response.CardResponse;
import uz.tracker.trackerproject.entity.Card;
import uz.tracker.trackerproject.exception.ResourceNotFoundException;
import uz.tracker.trackerproject.repository.CardRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;
import uz.tracker.trackerproject.security.CardNumberCipher;
import uz.tracker.trackerproject.security.PinHasher;
import uz.tracker.trackerproject.security.RevealAttemptTracker;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CardService {

    private final CardRepository cardRepository;
    private final TransactionRepository transactionRepository;
    private final PinHasher pinHasher;
    private final CardNumberCipher cipher;
    private final RevealAttemptTracker revealTracker;

    @Transactional(readOnly = true)
    public List<CardResponse> getAll() {
        return cardRepository.findAll().stream()
                .map(c -> CardResponse.from(c, cardRepository.sumTransactionsByCardId(c.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public CardResponse getById(Long id) {
        Card c = findOrThrow(id);
        return CardResponse.from(c, cardRepository.sumTransactionsByCardId(id));
    }

    @Transactional
    public CardResponse create(CardRequest req) {
        Card card = new Card();
        applyRequest(card, req);
        return CardResponse.from(cardRepository.save(card), BigDecimal.ZERO);
    }

    @Transactional
    public CardResponse update(Long id, CardRequest req) {
        Card card = findOrThrow(id);
        applyRequest(card, req);
        return CardResponse.from(cardRepository.save(card),
                cardRepository.sumTransactionsByCardId(id));
    }

    @Transactional
    public void delete(Long id) {
        if (!cardRepository.existsById(id)) throw new ResourceNotFoundException("Card", id);
        // Null out the card reference on transactions instead of letting the FK constraint
        // throw — matches the warning shown to the user in the UI.
        transactionRepository.detachFromCard(id);
        cardRepository.deleteById(id);
    }

    @Transactional
    public String revealFullNumber(Long id, String pin) {
        revealTracker.checkAllowed(id);
        Card card = findOrThrow(id);
        if (card.getFullNumber() == null || card.getFullNumber().isBlank()) {
            throw new IllegalArgumentException("No full card number stored for this card");
        }
        if (!pinHasher.matches(pin, card.getPin())) {
            revealTracker.recordFailure(id);
            throw new IllegalArgumentException("Incorrect PIN");
        }
        revealTracker.recordSuccess(id);
        return cipher.decrypt(card.getFullNumber());
    }

    /**
     * Current balance of a card = initialBalance + net of its transactions (card portion
     * only — the cash split is already netted out by {@code sumTransactionsByCardId}).
     */
    public BigDecimal currentBalance(Card card) {
        BigDecimal initial = card.getInitialBalance() != null ? card.getInitialBalance() : BigDecimal.ZERO;
        BigDecimal delta = cardRepository.sumTransactionsByCardId(card.getId());
        return initial.add(delta != null ? delta : BigDecimal.ZERO);
    }

    /**
     * Guard a card-sourced EXPENSE: throw if {@code cardAmount} exceeds the card's current
     * balance. Shared by the Overview pay flows (donations / emergencies / investments /
     * subscription / loan repayments) so they validate balance the same way a normal
     * card transaction does. No-op for null/zero amounts.
     */
    public void assertSufficientBalance(Card card, BigDecimal cardAmount) {
        if (cardAmount == null || cardAmount.signum() <= 0) return;
        BigDecimal balance = currentBalance(card);
        if (cardAmount.compareTo(balance) > 0) {
            throw new IllegalArgumentException(
                    String.format("Insufficient card balance. Available: %s %s, required: %s %s",
                            balance.setScale(2, RoundingMode.HALF_UP), card.getCurrency(),
                            cardAmount.setScale(2, RoundingMode.HALF_UP), card.getCurrency()));
        }
    }

    private void applyRequest(Card card, CardRequest req) {
        card.setName(req.getName());
        card.setBankName(req.getBankName());
        card.setType(req.getType());
        card.setLastFourDigits(req.getLastFourDigits());
        card.setInitialBalance(req.getInitialBalance());
        card.setCurrency(req.getCurrency());
        card.setColor(req.getColor() != null ? req.getColor() : "#6366f1");
        if (req.getFullNumber() != null && !req.getFullNumber().isBlank()) {
            String cleaned = req.getFullNumber().replaceAll("\\s+", "");
            card.setFullNumber(cipher.encrypt(cleaned));
        }
        if (req.getPin() != null && !req.getPin().isBlank()) {
            String pin = req.getPin();
            card.setPin(pinHasher.looksHashed(pin) ? pin : pinHasher.hash(pin));
        }
    }

    private Card findOrThrow(Long id) {
        return cardRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Card", id));
    }
}
