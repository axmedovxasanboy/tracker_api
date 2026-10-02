package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.tracker.trackerproject.entity.HoldingMonthSnapshot;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface HoldingMonthSnapshotRepository extends JpaRepository<HoldingMonthSnapshot, Long> {
    Optional<HoldingMonthSnapshot> findByInvestmentIdAndMonth(Long investmentId, LocalDate month);
    List<HoldingMonthSnapshot> findByInvestmentIdIn(Collection<Long> investmentIds);
}
