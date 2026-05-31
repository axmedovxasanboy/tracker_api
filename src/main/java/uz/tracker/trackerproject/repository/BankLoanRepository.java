package uz.tracker.trackerproject.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uz.tracker.trackerproject.entity.BankLoan;

import java.util.List;

@Repository
public interface BankLoanRepository extends JpaRepository<BankLoan, Long> {

    @Query(value = """
            SELECT DISTINCT bank_name FROM bank_loans
            WHERE :prefix IS NULL OR :prefix = '' OR bank_name ILIKE '%' || :prefix || '%'
            ORDER BY bank_name
            LIMIT 15
            """, nativeQuery = true)
    List<String> findDistinctBankNames(@Param("prefix") String prefix);
}
