package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.tracker.trackerproject.entity.MonthClose;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface MonthCloseRepository extends JpaRepository<MonthClose, Long> {
    boolean existsByMonth(LocalDate month);
    Optional<MonthClose> findByMonth(LocalDate month);
    /** Most recently closed month — all months on/before it are closed (closing is sequential). */
    Optional<MonthClose> findTopByOrderByMonthDesc();
    List<MonthClose> findAllByOrderByMonthDesc();
}
