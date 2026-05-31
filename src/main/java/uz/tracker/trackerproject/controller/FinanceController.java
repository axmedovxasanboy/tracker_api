package uz.tracker.trackerproject.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.tracker.trackerproject.dto.request.*;
import uz.tracker.trackerproject.dto.response.*;
import uz.tracker.trackerproject.service.FinanceService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/finance")
@RequiredArgsConstructor
public class FinanceController {

    private final FinanceService financeService;

    // ---- Debts ----

    @GetMapping("/debts")
    public ResponseEntity<List<DebtResponse>> getDebts() {
        return ResponseEntity.ok(financeService.getAllDebts());
    }

    @PostMapping("/debts")
    public ResponseEntity<DebtResponse> createDebt(@Valid @RequestBody DebtRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(financeService.createDebt(req));
    }

    @PutMapping("/debts/{id}")
    public ResponseEntity<DebtResponse> updateDebt(@PathVariable Long id, @Valid @RequestBody DebtRequest req) {
        return ResponseEntity.ok(financeService.updateDebt(id, req));
    }

    @DeleteMapping("/debts/{id}")
    public ResponseEntity<Void> deleteDebt(@PathVariable Long id) {
        financeService.deleteDebt(id);
        return ResponseEntity.noContent().build();
    }

    // ---- Loans Given ----

    @GetMapping("/loans-given")
    public ResponseEntity<List<LoanGivenResponse>> getLoansGiven() {
        return ResponseEntity.ok(financeService.getAllLoansGiven());
    }

    @PostMapping("/loans-given")
    public ResponseEntity<LoanGivenResponse> createLoanGiven(@Valid @RequestBody LoanGivenRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(financeService.createLoanGiven(req));
    }

    @PutMapping("/loans-given/{id}")
    public ResponseEntity<LoanGivenResponse> updateLoanGiven(@PathVariable Long id, @Valid @RequestBody LoanGivenRequest req) {
        return ResponseEntity.ok(financeService.updateLoanGiven(id, req));
    }

    @DeleteMapping("/loans-given/{id}")
    public ResponseEntity<Void> deleteLoanGiven(@PathVariable Long id) {
        financeService.deleteLoanGiven(id);
        return ResponseEntity.noContent().build();
    }

    // ---- Loans Taken ----

    @GetMapping("/loans-taken")
    public ResponseEntity<List<LoanTakenResponse>> getLoansTaken() {
        return ResponseEntity.ok(financeService.getAllLoansTaken());
    }

    @PostMapping("/loans-taken")
    public ResponseEntity<LoanTakenResponse> createLoanTaken(@Valid @RequestBody LoanTakenRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(financeService.createLoanTaken(req));
    }

    @PutMapping("/loans-taken/{id}")
    public ResponseEntity<LoanTakenResponse> updateLoanTaken(@PathVariable Long id, @Valid @RequestBody LoanTakenRequest req) {
        return ResponseEntity.ok(financeService.updateLoanTaken(id, req));
    }

    @DeleteMapping("/loans-taken/{id}")
    public ResponseEntity<Void> deleteLoanTaken(@PathVariable Long id) {
        financeService.deleteLoanTaken(id);
        return ResponseEntity.noContent().build();
    }

    // ---- Bank Loans ----

    @GetMapping("/bank-loans")
    public ResponseEntity<List<BankLoanResponse>> getBankLoans() {
        return ResponseEntity.ok(financeService.getAllBankLoans());
    }

    @GetMapping("/bank-loans/banks")
    public ResponseEntity<List<String>> getBankNameSuggestions(@RequestParam(defaultValue = "") String q) {
        return ResponseEntity.ok(financeService.getBankNameSuggestions(q));
    }

    @PostMapping("/bank-loans")
    public ResponseEntity<BankLoanResponse> createBankLoan(@Valid @RequestBody BankLoanRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(financeService.createBankLoan(req));
    }

    @PutMapping("/bank-loans/{id}")
    public ResponseEntity<BankLoanResponse> updateBankLoan(@PathVariable Long id, @Valid @RequestBody BankLoanRequest req) {
        return ResponseEntity.ok(financeService.updateBankLoan(id, req));
    }

    @DeleteMapping("/bank-loans/{id}")
    public ResponseEntity<Void> deleteBankLoan(@PathVariable Long id) {
        financeService.deleteBankLoan(id);
        return ResponseEntity.noContent().build();
    }

    // ---- Monthly Payments ----

    @GetMapping("/monthly-payments")
    public ResponseEntity<List<MonthlyPaymentResponse>> getMonthlyPayments() {
        return ResponseEntity.ok(financeService.getAllMonthlyPayments());
    }

    @PostMapping("/monthly-payments")
    public ResponseEntity<MonthlyPaymentResponse> createMonthlyPayment(@Valid @RequestBody MonthlyPaymentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(financeService.createMonthlyPayment(req));
    }

    @PutMapping("/monthly-payments/{id}")
    public ResponseEntity<MonthlyPaymentResponse> updateMonthlyPayment(@PathVariable Long id, @Valid @RequestBody MonthlyPaymentRequest req) {
        return ResponseEntity.ok(financeService.updateMonthlyPayment(id, req));
    }

    @DeleteMapping("/monthly-payments/{id}")
    public ResponseEntity<Void> deleteMonthlyPayment(@PathVariable Long id) {
        financeService.deleteMonthlyPayment(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/monthly-payments/{id}/pay")
    public ResponseEntity<MonthlyPaymentResponse> payMonthlyPayment(
            @PathVariable Long id, @Valid @RequestBody MonthlyPaymentPayRequest req) {
        return ResponseEntity.ok(financeService.payMonthlyPayment(id, req));
    }

    @GetMapping("/monthly-payments/{id}/payments")
    public ResponseEntity<List<TransactionResponse>> getMonthlyPaymentPayments(@PathVariable Long id) {
        return ResponseEntity.ok(financeService.getMonthlyPaymentPayments(id));
    }

    // ---- Donations ----

    @GetMapping("/donations")
    public ResponseEntity<List<DonationResponse>> getDonations() {
        return ResponseEntity.ok(financeService.getAllDonations());
    }

    @PostMapping("/donations")
    public ResponseEntity<DonationResponse> createDonation(@Valid @RequestBody DonationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(financeService.createDonation(req));
    }

    @PutMapping("/donations/{id}")
    public ResponseEntity<DonationResponse> updateDonation(@PathVariable Long id, @Valid @RequestBody DonationRequest req) {
        return ResponseEntity.ok(financeService.updateDonation(id, req));
    }

    @DeleteMapping("/donations/{id}")
    public ResponseEntity<Void> deleteDonation(@PathVariable Long id) {
        financeService.deleteDonation(id);
        return ResponseEntity.noContent().build();
    }

    // ---- Investments ----

    @GetMapping("/investments")
    public ResponseEntity<List<InvestmentResponse>> getInvestments() {
        return ResponseEntity.ok(financeService.getAllInvestments());
    }

    @PostMapping("/investments")
    public ResponseEntity<InvestmentResponse> createInvestment(@Valid @RequestBody InvestmentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(financeService.createInvestment(req));
    }

    @PutMapping("/investments/{id}")
    public ResponseEntity<InvestmentResponse> updateInvestment(@PathVariable Long id, @Valid @RequestBody InvestmentRequest req) {
        return ResponseEntity.ok(financeService.updateInvestment(id, req));
    }

    @DeleteMapping("/investments/{id}")
    public ResponseEntity<Void> deleteInvestment(@PathVariable Long id) {
        financeService.deleteInvestment(id);
        return ResponseEntity.noContent().build();
    }

    // ---- Repayments ----

    @PostMapping("/loans-taken/{id}/repay")
    public ResponseEntity<LoanTakenResponse> repayLoanTaken(
            @PathVariable Long id, @Valid @RequestBody RepaymentRequest req) {
        return ResponseEntity.ok(financeService.repayLoanTaken(id, req));
    }

    @PostMapping("/debts/{id}/repay")
    public ResponseEntity<DebtResponse> repayDebt(
            @PathVariable Long id, @Valid @RequestBody RepaymentRequest req) {
        return ResponseEntity.ok(financeService.repayDebt(id, req));
    }

    @PostMapping("/loans-given/{id}/mark-returned")
    public ResponseEntity<LoanGivenResponse> markLoanGivenReturned(
            @PathVariable Long id, @Valid @RequestBody RepaymentRequest req) {
        return ResponseEntity.ok(financeService.markLoanGivenReturned(id, req));
    }

    // ---- Payment history (per loan/debt) ----

    @GetMapping("/loans-taken/{id}/repayments")
    public ResponseEntity<List<TransactionResponse>> getLoanTakenRepayments(@PathVariable Long id) {
        return ResponseEntity.ok(financeService.getLoanTakenRepayments(id));
    }

    @GetMapping("/loans-given/{id}/repayments")
    public ResponseEntity<List<TransactionResponse>> getLoanGivenRepayments(@PathVariable Long id) {
        return ResponseEntity.ok(financeService.getLoanGivenRepayments(id));
    }

    @GetMapping("/debts/{id}/repayments")
    public ResponseEntity<List<TransactionResponse>> getDebtRepayments(@PathVariable Long id) {
        return ResponseEntity.ok(financeService.getDebtRepayments(id));
    }
}
