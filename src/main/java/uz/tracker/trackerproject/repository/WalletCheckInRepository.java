package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.tracker.trackerproject.entity.WalletCheckIn;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface WalletCheckInRepository extends JpaRepository<WalletCheckIn, Long> {

    /** The most recent check-in by the day it was true on; ties go to the one saved last. */
    Optional<WalletCheckIn> findTopByOrderByDateDescIdDesc();

    long countByDateBetween(LocalDate start, LocalDate end);
}
