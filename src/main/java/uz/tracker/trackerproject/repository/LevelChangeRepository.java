package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.tracker.trackerproject.entity.LevelChange;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface LevelChangeRepository extends JpaRepository<LevelChange, Long> {
    List<LevelChange> findAllByOrderByMonthAscIdAsc();
    List<LevelChange> findByMonth(LocalDate month);
}
