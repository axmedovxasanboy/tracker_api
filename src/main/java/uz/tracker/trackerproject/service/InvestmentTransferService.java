package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.request.InvestmentTransferRequest;
import uz.tracker.trackerproject.dto.response.InvestmentTransferResponse;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.InvestmentTransfer;
import uz.tracker.trackerproject.exception.ResourceNotFoundException;
import uz.tracker.trackerproject.repository.InvestmentRepository;
import uz.tracker.trackerproject.repository.InvestmentTransferRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Moves between plain investments (Investments page, Batch 78) — "like between cards", but between
 * a business and an Islamic bank. A move writes no transaction: it is not spending, not income, not a
 * put-in for the month and not a take-out, so the month's Investments bucket never sees it. Across all
 * holdings the total put in and the total value stay exactly the same.
 */
@Service
@RequiredArgsConstructor
public class InvestmentTransferService {

    static final ZoneId OWNER_ZONE = ZoneId.of("Asia/Tashkent");

    private final InvestmentRepository investmentRepository;
    private final InvestmentTransferRepository transferRepository;

    /** What a holding is worth: its tracked value, else what was put in. */
    static BigDecimal valueOf(Investment i) {
        BigDecimal v = i.getCurrentValue() != null ? i.getCurrentValue() : i.getInvestedAmount();
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Not an emergency-fund holding, not a savings goal, not an own saving. */
    static boolean isPlain(Investment i) {
        return !Boolean.TRUE.equals(i.getEmergencyFund()) && !Boolean.TRUE.equals(i.getSavingsGoal())
                && !i.isCustomSaving();
    }

    LocalDate today() {
        return LocalDate.now(OWNER_ZONE);
    }

    @Transactional
    public InvestmentTransferResponse transfer(InvestmentTransferRequest req) {
        if (Objects.equals(req.getFromId(), req.getToId())) {
            throw new IllegalArgumentException("Choose two different investments.");
        }
        Investment from = investmentRepository.findById(req.getFromId())
                .orElseThrow(() -> new ResourceNotFoundException("Investment", req.getFromId()));
        Investment to = investmentRepository.findById(req.getToId())
                .orElseThrow(() -> new ResourceNotFoundException("Investment", req.getToId()));
        if (!isPlain(from) || !isPlain(to)) {
            throw new IllegalArgumentException(
                    "Money can be moved only between investments — not a goal, the emergency fund or an own saving.");
        }
        if (from.getCurrency() != to.getCurrency()) {
            throw new IllegalArgumentException("Both investments must be in the same currency.");
        }
        BigDecimal amount = req.getAmount();
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be greater than 0");
        }
        BigDecimal fromValue = valueOf(from);
        if (amount.compareTo(fromValue) > 0) {
            throw new IllegalArgumentException(String.format(
                    "Only %s %s is in %s — you can move at most that.",
                    fromValue.stripTrailingZeros().toPlainString(), from.getCurrency(), from.getName()));
        }
        if (req.getDate() == null) throw new IllegalArgumentException("Date is required");
        if (req.getDate().isAfter(today())) {
            throw new IllegalArgumentException("The date cannot be in the future.");
        }

        BigDecimal fromInvested = from.getInvestedAmount() == null ? BigDecimal.ZERO : from.getInvestedAmount();
        BigDecimal putInMoved;
        if (amount.compareTo(fromValue) == 0) {
            putInMoved = fromInvested;
        } else {
            putInMoved = fromInvested.multiply(amount).divide(fromValue, 2, RoundingMode.HALF_UP);
            if (putInMoved.compareTo(fromInvested) > 0) putInMoved = fromInvested;
        }

        from.setInvestedAmount(fromInvested.subtract(putInMoved));
        if (from.getCurrentValue() != null) from.setCurrentValue(from.getCurrentValue().subtract(amount));

        BigDecimal toInvested = to.getInvestedAmount() == null ? BigDecimal.ZERO : to.getInvestedAmount();
        if (to.getCurrentValue() != null) {
            to.setCurrentValue(to.getCurrentValue().add(amount));
        } else if (amount.compareTo(putInMoved) != 0) {
            to.setCurrentValue(toInvested.add(amount));
        }
        to.setInvestedAmount(toInvested.add(putInMoved));
        investmentRepository.save(from);
        investmentRepository.save(to);

        InvestmentTransfer t = new InvestmentTransfer();
        t.setFromInvestmentId(from.getId());
        t.setToInvestmentId(to.getId());
        t.setFromName(from.getName());
        t.setToName(to.getName());
        t.setAmount(amount);
        t.setPutInMoved(putInMoved);
        t.setCurrency(from.getCurrency());
        t.setTransferDate(req.getDate());
        t.setNote(req.getNote() == null || req.getNote().isBlank() ? null : req.getNote().trim());
        t = transferRepository.save(t);
        return toResponse(t, Map.of(from.getId(), from, to.getId(), to));
    }

    /** Newest first; with an id, only the moves it is on (either side); limit ≤ 0 or null = all. */
    @Transactional(readOnly = true)
    public List<InvestmentTransferResponse> list(Long investmentId, Integer limit) {
        List<InvestmentTransfer> rows = investmentId == null
                ? transferRepository.findAllByOrderByTransferDateDescIdDesc()
                : transferRepository.findByInvestment(investmentId);
        if (limit != null && limit > 0 && rows.size() > limit) rows = rows.subList(0, limit);
        Map<Long, Investment> holdings = investmentRepository.findAll().stream()
                .collect(Collectors.toMap(Investment::getId, Function.identity(), (a, b) -> a));
        return rows.stream().map(t -> toResponse(t, holdings)).toList();
    }

    /** Undo a mistaken move: exactly the reverse of {@link #transfer}, then the row goes. */
    @Transactional
    public void undo(Long id) {
        InvestmentTransfer t = transferRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("InvestmentTransfer", id));
        Investment from = t.getFromInvestmentId() == null ? null
                : investmentRepository.findById(t.getFromInvestmentId()).orElse(null);
        Investment to = t.getToInvestmentId() == null ? null
                : investmentRepository.findById(t.getToInvestmentId()).orElse(null);
        if (from == null || to == null) {
            throw new IllegalArgumentException("One side of this move was deleted, so it cannot be undone.");
        }
        BigDecimal toValue = valueOf(to);
        if (toValue.compareTo(t.getAmount()) < 0) {
            throw new IllegalArgumentException(String.format("%s now has only %s — take less out of it first.",
                    to.getName(), toValue.stripTrailingZeros().toPlainString()));
        }
        BigDecimal toInvested = to.getInvestedAmount() == null ? BigDecimal.ZERO : to.getInvestedAmount();
        BigDecimal back = toInvested.subtract(t.getPutInMoved());
        to.setInvestedAmount(back.signum() < 0 ? BigDecimal.ZERO : back);
        if (to.getCurrentValue() != null) to.setCurrentValue(to.getCurrentValue().subtract(t.getAmount()));

        BigDecimal fromInvested = from.getInvestedAmount() == null ? BigDecimal.ZERO : from.getInvestedAmount();
        from.setInvestedAmount(fromInvested.add(t.getPutInMoved()));
        if (from.getCurrentValue() != null) from.setCurrentValue(from.getCurrentValue().add(t.getAmount()));
        investmentRepository.save(to);
        investmentRepository.save(from);
        transferRepository.delete(t);
    }

    private InvestmentTransferResponse toResponse(InvestmentTransfer t, Map<Long, Investment> holdings) {
        Investment from = t.getFromInvestmentId() == null ? null : holdings.get(t.getFromInvestmentId());
        Investment to = t.getToInvestmentId() == null ? null : holdings.get(t.getToInvestmentId());
        boolean canUndo = from != null && to != null && valueOf(to).compareTo(t.getAmount()) >= 0;
        return InvestmentTransferResponse.builder()
                .id(t.getId())
                .fromId(from == null ? null : from.getId())
                .fromName(from != null ? from.getName() : t.getFromName())
                .toId(to == null ? null : to.getId())
                .toName(to != null ? to.getName() : t.getToName())
                .amount(t.getAmount())
                .putInMoved(t.getPutInMoved())
                .currency(t.getCurrency())
                .date(t.getTransferDate())
                .note(t.getNote())
                .createdAt(t.getCreatedAt())
                .canUndo(canUndo)
                .build();
    }
}
