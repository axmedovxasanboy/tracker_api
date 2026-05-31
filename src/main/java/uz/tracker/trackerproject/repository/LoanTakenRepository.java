package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.enums.RecordStatus;

import java.util.List;
import java.util.Optional;

@Repository
public interface LoanTakenRepository extends JpaRepository<LoanTaken, Long> {
    List<LoanTaken> findAllByOrderByDueDateAsc();
    List<LoanTaken> findByStatus(RecordStatus status);
    Optional<LoanTaken> findByOriginatingTransactionId(Long transactionId);
}
