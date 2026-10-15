package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.tracker.trackerproject.entity.MonthRule;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface MonthRuleRepository extends JpaRepository<MonthRule, Long> {
    Optional<MonthRule> findByMonth(LocalDate month);
}
