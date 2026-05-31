package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.tracker.trackerproject.entity.Emergency;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface EmergencyRepository extends JpaRepository<Emergency, Long> {
    List<Emergency> findAllByOrderByDateDesc();
    List<Emergency> findByDateBetweenOrderByDateDesc(LocalDate start, LocalDate end);
}
