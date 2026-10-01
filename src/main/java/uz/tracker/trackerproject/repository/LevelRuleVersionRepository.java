package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.tracker.trackerproject.entity.LevelRuleVersion;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface LevelRuleVersionRepository extends JpaRepository<LevelRuleVersion, Long> {
    List<LevelRuleVersion> findAllByOrderByLevelAscFromMonthAsc();
    List<LevelRuleVersion> findByLevelOrderByFromMonthAsc(Integer level);
    Optional<LevelRuleVersion> findByLevelAndFromMonth(Integer level, LocalDate fromMonth);
    long countByLevel(Integer level);
}
