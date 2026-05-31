package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.tracker.trackerproject.entity.MonthlyPayment;

import java.util.List;

@Repository
public interface MonthlyPaymentRepository extends JpaRepository<MonthlyPayment, Long> {
    List<MonthlyPayment> findByActiveOrderByDueDayAsc(Boolean active);
    List<MonthlyPayment> findAllByOrderByDueDayAsc();
}
