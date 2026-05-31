package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.enums.InvestmentType;

import java.util.List;
import java.util.Optional;

@Repository
public interface InvestmentRepository extends JpaRepository<Investment, Long> {
    List<Investment> findAllByOrderByPurchaseDateDesc();
    List<Investment> findByType(InvestmentType type);
    List<Investment> findByPurchaseDateBetweenOrderByPurchaseDateDesc(java.time.LocalDate start, java.time.LocalDate end);
    Optional<Investment> findByOriginatingTransactionId(Long transactionId);
}
