package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.tracker.trackerproject.entity.LoanGiven;
import uz.tracker.trackerproject.enums.RecordStatus;

import java.util.List;
import java.util.Optional;

@Repository
public interface LoanGivenRepository extends JpaRepository<LoanGiven, Long> {
    List<LoanGiven> findAllByOrderByExpectedReturnDateAsc();
    List<LoanGiven> findByStatus(RecordStatus status);
    Optional<LoanGiven> findByOriginatingTransactionId(Long transactionId);
}
