package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.request.EmergencyRequest;
import uz.tracker.trackerproject.dto.response.EmergencyResponse;
import uz.tracker.trackerproject.entity.Card;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.Emergency;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.exception.ResourceNotFoundException;
import uz.tracker.trackerproject.repository.CardRepository;
import uz.tracker.trackerproject.repository.CategoryRepository;
import uz.tracker.trackerproject.repository.EmergencyRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EmergencyService {

    private final EmergencyRepository repo;
    private final TransactionRepository transactionRepository;
    private final CardRepository cardRepository;
    private final CategoryRepository categoryRepository;
    private final CardService cardService;

    @Transactional(readOnly = true)
    public List<EmergencyResponse> getAll() {
        return repo.findAllByOrderByDateDesc().stream().map(EmergencyResponse::from).toList();
    }

    @Transactional
    public EmergencyResponse create(EmergencyRequest req) {
        // Mirror to an EXPENSE Transaction so the contribution shows up in the
        // Transactions list AND in the per-bucket payment history alongside any
        // emergency contributions created from the other side.
        Transaction tx = new Transaction();
        tx.setType(TransactionType.EXPENSE);
        tx.setSubType(TransactionSubType.EMERGENCY_CONTRIBUTION);
        tx.setAmount(req.getAmount());
        tx.setCurrency(req.getCurrency());
        tx.setDescription(req.getDescription() != null && !req.getDescription().isBlank()
                ? req.getDescription() : "Emergency fund contribution");
        tx.setTransactionDate(req.getDate());
        if (req.getCardId() != null) {
            Card card = cardRepository.findById(req.getCardId())
                    .orElseThrow(() -> new ResourceNotFoundException("Card", req.getCardId()));
            if (card.getCurrency() != req.getCurrency()) {
                throw new IllegalArgumentException(
                        "Card currency (" + card.getCurrency() + ") does not match payment currency (" + req.getCurrency() + ")");
            }
            cardService.assertSufficientBalance(card, req.getAmount());
            tx.setCard(card);
            tx.setCashAmount(BigDecimal.ZERO);
        } else {
            tx.setCard(null);
            tx.setCashAmount(req.getAmount());
        }
        // Category: explicit override wins; else auto-pick the single category for this sub-type.
        if (req.getCategoryId() != null) {
            categoryRepository.findById(req.getCategoryId()).ifPresent(tx::setCategory);
        } else {
            List<Category> matches = categoryRepository.findByApplicableSubTypeAndParentIsNull(TransactionSubType.EMERGENCY_CONTRIBUTION);
            if (matches.size() == 1) tx.setCategory(matches.get(0));
        }
        transactionRepository.save(tx);

        Emergency e = new Emergency();
        apply(e, req);
        return EmergencyResponse.from(repo.save(e));
    }

    @Transactional
    public EmergencyResponse update(Long id, EmergencyRequest req) {
        Emergency e = repo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Emergency", id));
        apply(e, req);
        return EmergencyResponse.from(repo.save(e));
    }

    @Transactional
    public void delete(Long id) {
        if (!repo.existsById(id)) throw new ResourceNotFoundException("Emergency", id);
        repo.deleteById(id);
    }

    private void apply(Emergency e, EmergencyRequest req) {
        e.setAmount(req.getAmount());
        e.setCurrency(req.getCurrency());
        e.setDate(req.getDate());
        e.setDescription(req.getDescription());
    }
}
