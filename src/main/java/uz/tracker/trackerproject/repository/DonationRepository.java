package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.tracker.trackerproject.entity.Donation;

import java.util.List;
import java.util.Optional;

@Repository
public interface DonationRepository extends JpaRepository<Donation, Long> {
    List<Donation> findAllByOrderByDonationDateDesc();
    List<Donation> findByDonationDateBetweenOrderByDonationDateDesc(java.time.LocalDate start, java.time.LocalDate end);
    Optional<Donation> findByOriginatingTransactionId(Long transactionId);
}
