package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.tracker.trackerproject.entity.Debt;
import uz.tracker.trackerproject.enums.RecordStatus;

import java.util.List;

@Repository
public interface DebtRepository extends JpaRepository<Debt, Long> {
    List<Debt> findAllByOrderByDueDateAsc();
    List<Debt> findByStatus(RecordStatus status);
}
