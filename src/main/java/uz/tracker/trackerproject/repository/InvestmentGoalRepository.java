package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.tracker.trackerproject.entity.InvestmentGoal;

import java.util.List;

@Repository
public interface InvestmentGoalRepository extends JpaRepository<InvestmentGoal, Long> {

    List<InvestmentGoal> findAllByOrderByCreatedAtAscIdAsc();
}
