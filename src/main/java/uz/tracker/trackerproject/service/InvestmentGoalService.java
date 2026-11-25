package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.request.InvestmentGoalRequest;
import uz.tracker.trackerproject.dto.response.InvestmentGoalResponse;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.InvestmentGoal;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.exception.ResourceNotFoundException;
import uz.tracker.trackerproject.repository.InvestmentGoalRepository;
import uz.tracker.trackerproject.repository.InvestmentRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Investment goals (Investments page, Batch 79): "a car, about 1 mlrd — IMAN 1, IMAN 2 and Open Bank 1
 * are saving for it". A goal holds no money; its saved figure is the sum of its accounts' values. An
 * account serves at most one goal, and the links change only here (an investment edit never touches
 * them, except that a holding which stops being a plain investment drops its link).
 */
@Service
@RequiredArgsConstructor
public class InvestmentGoalService {

    private final InvestmentGoalRepository goalRepository;
    private final InvestmentRepository investmentRepository;

    @Transactional(readOnly = true)
    public List<InvestmentGoalResponse> list() {
        List<Investment> all = investmentRepository.findAll();
        return goalRepository.findAllByOrderByCreatedAtAscIdAsc().stream()
                .map(g -> toResponse(g, all)).toList();
    }

    @Transactional
    public InvestmentGoalResponse create(InvestmentGoalRequest req) {
        InvestmentGoal g = new InvestmentGoal();
        g.setCurrency(Currency.UZS);
        apply(g, req);
        List<Investment> accounts = accounts(req.getInvestmentIds());
        g = goalRepository.save(g);
        link(g.getId(), accounts);
        return toResponse(g, investmentRepository.findAll());
    }

    @Transactional
    public InvestmentGoalResponse update(Long id, InvestmentGoalRequest req) {
        InvestmentGoal g = goalRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("InvestmentGoal", id));
        apply(g, req);
        List<Investment> accounts = accounts(req.getInvestmentIds());
        g = goalRepository.save(g);
        link(g.getId(), accounts);
        return toResponse(g, investmentRepository.findAll());
    }

    /** The goal goes; its accounts stay as they are, with no goal. */
    @Transactional
    public void delete(Long id) {
        InvestmentGoal g = goalRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("InvestmentGoal", id));
        for (Investment i : investmentRepository.findAll()) {
            if (Objects.equals(i.getInvestmentGoalId(), id)) {
                i.setInvestmentGoalId(null);
                investmentRepository.save(i);
            }
        }
        goalRepository.delete(g);
    }

    private static void apply(InvestmentGoal g, InvestmentGoalRequest req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("Give the goal a name.");
        }
        if (req.getTargetAmount() == null || req.getTargetAmount().signum() <= 0) {
            throw new IllegalArgumentException("The goal amount must be more than 0.");
        }
        g.setName(req.getName().trim());
        g.setTargetAmount(req.getTargetAmount());
        g.setNote(req.getNote() == null || req.getNote().isBlank() ? null : req.getNote().trim());
    }

    /** The requested accounts, each a plain investment; checked before anything is written. */
    private List<Investment> accounts(List<Long> ids) {
        List<Investment> out = new ArrayList<>();
        if (ids == null) return out;
        for (Long id : new LinkedHashSet<>(ids)) {
            if (id == null) continue;
            Investment i = investmentRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Investment", id));
            if (!InvestmentTransferService.isPlain(i)) {
                throw new IllegalArgumentException(i.getName() + " is not an investment account.");
            }
            out.add(i);
        }
        return out;
    }

    /** Exactly {@code accounts} serve goal {@code goalId}: others linked to it are unlinked. */
    private void link(Long goalId, List<Investment> accounts) {
        Set<Long> keep = new HashSet<>();
        for (Investment i : accounts) keep.add(i.getId());
        for (Investment i : investmentRepository.findAll()) {
            if (Objects.equals(i.getInvestmentGoalId(), goalId) && !keep.contains(i.getId())) {
                i.setInvestmentGoalId(null);
                investmentRepository.save(i);
            }
        }
        for (Investment i : accounts) {
            if (!Objects.equals(i.getInvestmentGoalId(), goalId)) {
                i.setInvestmentGoalId(goalId);
                investmentRepository.save(i);
            }
        }
    }

    private static InvestmentGoalResponse toResponse(InvestmentGoal g, List<Investment> all) {
        List<Investment> mine = all.stream()
                .filter(i -> Objects.equals(i.getInvestmentGoalId(), g.getId()))
                .sorted(Comparator.comparing((Investment i) -> i.getBroker() == null ? "" : i.getBroker().toLowerCase())
                        .thenComparing(i -> i.getName() == null ? "" : i.getName().toLowerCase())
                        .thenComparing(Investment::getId))
                .toList();
        BigDecimal saved = BigDecimal.ZERO;
        List<InvestmentGoalResponse.Account> accounts = new ArrayList<>();
        for (Investment i : mine) {
            BigDecimal v = InvestmentTransferService.valueOf(i);
            saved = saved.add(v);
            accounts.add(InvestmentGoalResponse.Account.builder()
                    .id(i.getId()).name(i.getName()).broker(i.getBroker()).type(i.getType()).value(v).build());
        }
        BigDecimal target = g.getTargetAmount();
        BigDecimal left = target.subtract(saved);
        return InvestmentGoalResponse.builder()
                .id(g.getId())
                .name(g.getName())
                .targetAmount(target)
                .currency(g.getCurrency())
                .note(g.getNote())
                .saved(saved)
                .left(left.signum() < 0 ? BigDecimal.ZERO : left)
                .progressPercent(saved.multiply(BigDecimal.valueOf(100)).divide(target, 1, RoundingMode.HALF_UP))
                .reached(saved.compareTo(target) >= 0)
                .investments(accounts)
                .createdAt(g.getCreatedAt())
                .build();
    }
}
