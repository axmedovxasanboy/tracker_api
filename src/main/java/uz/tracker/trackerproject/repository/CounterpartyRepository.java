package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.tracker.trackerproject.entity.Counterparty;
import uz.tracker.trackerproject.enums.CounterpartyKind;

import java.util.List;
import java.util.Optional;

@Repository
public interface CounterpartyRepository extends JpaRepository<Counterparty, Long> {

    /** The person on {@code kind}'s list with this name key (Counterparty.keyOf). */
    Optional<Counterparty> findByKindAndNameKey(CounterpartyKind kind, String nameKey);

    List<Counterparty> findByKind(CounterpartyKind kind);
}
