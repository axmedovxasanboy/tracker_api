package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.tracker.trackerproject.entity.LevelAllocationRule;

import java.util.List;
import java.util.Optional;

@Repository
public interface LevelAllocationRuleRepository extends JpaRepository<LevelAllocationRule, Long> {
    Optional<LevelAllocationRule> findBySubLevel(String subLevel);
    List<LevelAllocationRule> findAllByOrderBySubLevelAsc();
}
