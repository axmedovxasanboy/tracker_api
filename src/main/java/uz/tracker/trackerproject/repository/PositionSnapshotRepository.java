package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.tracker.trackerproject.entity.PositionSnapshot;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PositionSnapshotRepository extends JpaRepository<PositionSnapshot, Long> {
    Optional<PositionSnapshot> findByMonth(LocalDate month);
    List<PositionSnapshot> findAllByOrderByMonthAsc();
}
