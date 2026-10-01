package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.tracker.trackerproject.entity.StableIncomeEntry;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StableIncomeEntryRepository extends JpaRepository<StableIncomeEntry, Long> {
    List<StableIncomeEntry> findAllByOrderByMonthAsc();
    Optional<StableIncomeEntry> findByMonth(LocalDate month);
}
