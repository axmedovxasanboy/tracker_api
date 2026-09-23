package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.request.PersonRequest;
import uz.tracker.trackerproject.dto.response.PersonResponse;
import uz.tracker.trackerproject.entity.Counterparty;
import uz.tracker.trackerproject.entity.Debt;
import uz.tracker.trackerproject.entity.LoanGiven;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.enums.CounterpartyKind;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.exception.ResourceNotFoundException;
import uz.tracker.trackerproject.repository.CounterpartyRepository;
import uz.tracker.trackerproject.repository.DebtRepository;
import uz.tracker.trackerproject.repository.LoanGivenRepository;
import uz.tracker.trackerproject.repository.LoanTakenRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The owner's two lists of people: who they borrow from (LENDER — borrowed money and debts) and who
 * borrows from them (BORROWER — money lent). The owner borrows from the same person again and again,
 * so each record links to its person, and each list says who they borrow from (or lend to) most.
 *
 * <p>A name is the same person ignoring case and surrounding spaces. The records keep their own
 * name columns, which the bot shows; a rename here rewrites them.
 */
@Service
@RequiredArgsConstructor
public class CounterpartyService {

    private final CounterpartyRepository counterpartyRepository;
    private final LoanTakenRepository loanTakenRepository;
    private final DebtRepository debtRepository;
    private final LoanGivenRepository loanGivenRepository;

    /** A new person, or the one already on the list under that name; {@code created} says which. */
    public record Saved(PersonResponse person, boolean created) {}

    /**
     * The person on {@code kind}'s list with this name, ignoring case and surrounding spaces — created
     * when there is none. Null for a blank name.
     */
    @Transactional
    public Counterparty findOrCreate(String name, CounterpartyKind kind) {
        String key = Counterparty.keyOf(name);
        if (key == null || kind == null) return null;
        return counterpartyRepository.findByKindAndNameKey(kind, key).orElseGet(() -> {
            Counterparty c = new Counterparty();
            c.setName(name.trim());
            c.setNameKey(key);
            c.setKind(kind);
            return counterpartyRepository.save(c);
        });
    }

    /** The person {@code id}, who must be on {@code kind}'s list. Runs in the caller's (write) transaction. */
    public Counterparty require(Long id, CounterpartyKind kind) {
        Counterparty c = counterpartyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Person", id));
        if (c.getKind() != kind) {
            throw new IllegalArgumentException("Person " + id + " is on the " + listName(c.getKind())
                    + " list, not the " + listName(kind) + " list.");
        }
        return c;
    }

    /** {@code kind}'s list with each person's figures, the largest total first. */
    @Transactional(readOnly = true)
    public List<PersonResponse> list(CounterpartyKind kind) {
        Map<Long, Figures> figures = figures(kind);
        List<PersonResponse> rows = new ArrayList<>();
        for (Counterparty c : counterpartyRepository.findByKind(kind)) {
            rows.add(figures.getOrDefault(c.getId(), new Figures()).of(c));
        }
        rows.sort(Comparator.comparing(PersonResponse::getTotal, Comparator.reverseOrder())
                .thenComparing(p -> p.getName() == null ? "" : p.getName(), String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    /** Add a person — or, when the name is already on that list, hand back the one there. */
    @Transactional
    public Saved create(PersonRequest req) {
        if (req.getKind() == null) throw new IllegalArgumentException("kind is required: LENDER or BORROWER.");
        String key = requireName(req.getName());
        Counterparty existing = counterpartyRepository.findByKindAndNameKey(req.getKind(), key).orElse(null);
        if (existing != null) return new Saved(person(existing), false);
        return new Saved(person(findOrCreate(req.getName(), req.getKind())), true);
    }

    /** Rename a person, and every record linked to them with it. A name another person has is refused. */
    @Transactional
    public PersonResponse rename(Long id, PersonRequest req) {
        Counterparty c = counterpartyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Person", id));
        String key = requireName(req.getName());
        counterpartyRepository.findByKindAndNameKey(c.getKind(), key)
                .filter(other -> !other.getId().equals(c.getId()))
                .ifPresent(other -> {
                    throw new IllegalArgumentException("\"" + other.getName() + "\" is already on the "
                            + listName(c.getKind()) + " list.");
                });
        String name = req.getName().trim();
        c.setName(name);
        c.setNameKey(key);
        counterpartyRepository.save(c);
        if (c.getKind() == CounterpartyKind.LENDER) {
            List<LoanTaken> loans = loanTakenRepository.findByLenderId(id);
            loans.forEach(l -> l.setLenderName(name));
            loanTakenRepository.saveAll(loans);
            List<Debt> debts = debtRepository.findByLenderId(id);
            debts.forEach(d -> d.setCreditorName(name));
            debtRepository.saveAll(debts);
        } else {
            List<LoanGiven> lent = loanGivenRepository.findByBorrowerId(id);
            lent.forEach(l -> l.setDebtorName(name));
            loanGivenRepository.saveAll(lent);
        }
        return person(c);
    }

    /**
     * Link every record that has no person yet to the one its name belongs to, adding people as
     * needed. Runs at every boot: records already linked are left alone, so a second run links
     * nothing. Returns how many records it linked.
     */
    @Transactional
    public int backfillLinks() {
        int linked = 0;
        for (LoanTaken l : loanTakenRepository.findAll()) {
            if (l.getLenderId() != null) continue;
            Counterparty c = findOrCreate(l.getLenderName(), CounterpartyKind.LENDER);
            if (c == null) continue;
            l.setLenderId(c.getId());
            loanTakenRepository.save(l);
            linked++;
        }
        for (Debt d : debtRepository.findAll()) {
            if (d.getLenderId() != null) continue;
            Counterparty c = findOrCreate(d.getCreditorName(), CounterpartyKind.LENDER);
            if (c == null) continue;
            d.setLenderId(c.getId());
            debtRepository.save(d);
            linked++;
        }
        for (LoanGiven l : loanGivenRepository.findAll()) {
            if (l.getBorrowerId() != null) continue;
            Counterparty c = findOrCreate(l.getDebtorName(), CounterpartyKind.BORROWER);
            if (c == null) continue;
            l.setBorrowerId(c.getId());
            loanGivenRepository.save(l);
            linked++;
        }
        return linked;
    }

    // ── Figures ───────────────────────────────────────────────────────────────

    /** One person's records, added up. */
    private static final class Figures {
        int times;
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal open = BigDecimal.ZERO;
        LocalDate lastDate;

        void add(BigDecimal amount, BigDecimal settled, boolean paid, LocalDate on) {
            times++;
            BigDecimal whole = amount == null ? BigDecimal.ZERO : amount;
            total = total.add(whole);
            BigDecimal left = whole.subtract(settled == null ? BigDecimal.ZERO : settled);
            if (!paid && left.signum() > 0) open = open.add(left);
            if (on != null && (lastDate == null || on.isAfter(lastDate))) lastDate = on;
        }

        PersonResponse of(Counterparty c) {
            return PersonResponse.builder().id(c.getId()).name(c.getName()).kind(c.getKind())
                    .times(times).total(total).open(open).lastDate(lastDate).build();
        }
    }

    private PersonResponse person(Counterparty c) {
        return figures(c.getKind()).getOrDefault(c.getId(), new Figures()).of(c);
    }

    /** Every linked UZS record on {@code kind}'s side, added up per person. */
    private Map<Long, Figures> figures(CounterpartyKind kind) {
        Map<Long, Figures> byPerson = new HashMap<>();
        if (kind == CounterpartyKind.LENDER) {
            for (LoanTaken l : loanTakenRepository.findAll()) {
                if (l.getLenderId() == null || !isUzs(l.getCurrency())) continue;
                byPerson.computeIfAbsent(l.getLenderId(), id -> new Figures()).add(l.getTotalAmount(),
                        l.getPaidAmount(), l.getStatus() == RecordStatus.PAID, l.getBorrowedDate());
            }
            for (Debt d : debtRepository.findAll()) {
                if (d.getLenderId() == null || !isUzs(d.getCurrency())) continue;
                byPerson.computeIfAbsent(d.getLenderId(), id -> new Figures()).add(d.getTotalAmount(),
                        d.getPaidAmount(), d.getStatus() == RecordStatus.PAID, d.getBorrowedDate());
            }
        } else {
            for (LoanGiven l : loanGivenRepository.findAll()) {
                if (l.getBorrowerId() == null || !isUzs(l.getCurrency())) continue;
                byPerson.computeIfAbsent(l.getBorrowerId(), id -> new Figures()).add(l.getTotalAmount(),
                        l.getReceivedAmount(), l.getStatus() == RecordStatus.PAID, l.getLentDate());
            }
        }
        return byPerson;
    }

    private static String requireName(String name) {
        String key = Counterparty.keyOf(name);
        if (key == null) throw new IllegalArgumentException("Name is required.");
        return key;
    }

    private static String listName(CounterpartyKind kind) {
        return kind == CounterpartyKind.LENDER ? "lenders" : "borrowers";
    }

    private static boolean isUzs(Currency c) {
        return c == null || c == Currency.UZS;
    }
}
