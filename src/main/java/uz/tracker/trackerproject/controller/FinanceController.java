package uz.tracker.trackerproject.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.tracker.trackerproject.dto.request.*;
import uz.tracker.trackerproject.dto.response.*;
import uz.tracker.trackerproject.service.FinanceService;
import uz.tracker.trackerproject.service.InvestmentTransferService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/finance")
@RequiredArgsConstructor
public class FinanceController {

    private final FinanceService financeService;
    private final InvestmentTransferService investmentTransferService;
    private final uz.tracker.trackerproject.service.InvestmentGoalService investmentGoalService;
    private final uz.tracker.trackerproject.service.InvestmentSplitService investmentSplitService;

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

    /** @param month YYYY-MM to scope the list to; blank → all time (the previous behaviour). */
    @GetMapping("/donations")
    public ResponseEntity<List<DonationResponse>> getDonations(
            @RequestParam(required = false) String month) {
        return ResponseEntity.ok(financeService.getAllDonations(month));
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

    /** @param month YYYY-MM to scope the list to; blank → all time (the previous behaviour). */
    @GetMapping("/investments")
    public ResponseEntity<List<InvestmentResponse>> getInvestments(
            @RequestParam(required = false) String month) {
        return ResponseEntity.ok(financeService.getAllInvestments(month));
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

    @PostMapping("/investments/{id}/contribute")
    public ResponseEntity<InvestmentResponse> contributeToInvestment(
            @PathVariable Long id, @Valid @RequestBody InvestmentContributeRequest req) {
        return ResponseEntity.ok(financeService.contributeToInvestment(id, req));
    }

    /**
     * Take money out of an investment — all of it or a part — into a wallet: an INCOME
     * INVESTMENT_WITHDRAWAL transaction, and the holding's invested total and tracked value go down.
     */
    @PostMapping("/investments/{id}/withdraw")
    public ResponseEntity<InvestmentResponse> withdrawFromInvestment(
            @PathVariable Long id, @Valid @RequestBody InvestmentWithdrawRequest req) {
        return ResponseEntity.ok(financeService.withdrawFromInvestment(id, req));
    }

    @PostMapping("/investments/{id}/value")
    public ResponseEntity<InvestmentResponse> updateInvestmentValue(
            @PathVariable Long id, @Valid @RequestBody InvestmentValueRequest req) {
        return ResponseEntity.ok(financeService.updateInvestmentValue(id, req));
    }

    // ---- Investment goals (Batch 79) ----

    /** Every investment goal with what its accounts hold now, oldest first. */
    @GetMapping("/investment-goals")
    public ResponseEntity<List<uz.tracker.trackerproject.dto.response.InvestmentGoalResponse>> getInvestmentGoals() {
        return ResponseEntity.ok(investmentGoalService.list());
    }

    @PostMapping("/investment-goals")
    public ResponseEntity<uz.tracker.trackerproject.dto.response.InvestmentGoalResponse> createInvestmentGoal(
            @Valid @RequestBody uz.tracker.trackerproject.dto.request.InvestmentGoalRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(investmentGoalService.create(req));
    }

    /** The linked accounts become exactly {@code investmentIds}; one linked to another goal moves here. */
    @PutMapping("/investment-goals/{id}")
    public ResponseEntity<uz.tracker.trackerproject.dto.response.InvestmentGoalResponse> updateInvestmentGoal(
            @PathVariable Long id, @Valid @RequestBody uz.tracker.trackerproject.dto.request.InvestmentGoalRequest req) {
        return ResponseEntity.ok(investmentGoalService.update(id, req));
    }

    /** The goal goes; its accounts and their money stay. */
    @DeleteMapping("/investment-goals/{id}")
    public ResponseEntity<Void> deleteInvestmentGoal(@PathVariable Long id) {
        investmentGoalService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ---- This month's split of the Investments money across the accounts (Batch 81) ----

    /** Which account each part of the month's Investments figure goes to; {@code month} = YYYY-MM, default this month. */
    @GetMapping("/investments/split")
    public ResponseEntity<uz.tracker.trackerproject.dto.response.InvestmentSplitResponse> getInvestmentSplit(
            @RequestParam(required = false) String month) {
        return ResponseEntity.ok(investmentSplitService.get(month));
    }

    /** The owner's shares: the typed amounts become weights; an account left out gets 0. */
    @PutMapping("/investments/split")
    public ResponseEntity<uz.tracker.trackerproject.dto.response.InvestmentSplitResponse> putInvestmentSplit(
            @RequestBody uz.tracker.trackerproject.dto.request.InvestmentSplitRequest req) {
        return ResponseEntity.ok(investmentSplitService.put(req));
    }

    /** Back to an equal split. */
    @DeleteMapping("/investments/split")
    public ResponseEntity<uz.tracker.trackerproject.dto.response.InvestmentSplitResponse> resetInvestmentSplit() {
        return ResponseEntity.ok(investmentSplitService.reset());
    }

    // ---- Moves between investments (Batch 78) ----

    /** Move money from one investment to another — no transaction; totals across holdings stay the same. */
    @PostMapping("/investments/transfer")
    public ResponseEntity<InvestmentTransferResponse> transferBetweenInvestments(
            @Valid @RequestBody InvestmentTransferRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(investmentTransferService.transfer(req));
    }

    /** Newest first; {@code investmentId} = only the moves it is on; {@code limit} = at most that many. */
    @GetMapping("/investments/transfers")
    public ResponseEntity<List<InvestmentTransferResponse>> getInvestmentTransfers(
            @RequestParam(required = false) Long investmentId,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(investmentTransferService.list(investmentId, limit));
    }

    /** Undo a move: exactly reverses it. */
    @DeleteMapping("/investments/transfers/{id}")
    public ResponseEntity<Void> undoInvestmentTransfer(@PathVariable Long id) {
        investmentTransferService.undo(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/investments/{id}/contributions")
    public ResponseEntity<List<TransactionResponse>> getInvestmentContributions(@PathVariable Long id) {
        return ResponseEntity.ok(financeService.getInvestmentContributions(id));
    }

    // ---- Repayments ----

    @PostMapping("/loans-taken/{id}/repay")
    public ResponseEntity<LoanTakenResponse> repayLoanTaken(
            @PathVariable Long id, @Valid @RequestBody RepaymentRequest req) {
        return ResponseEntity.ok(financeService.repayLoanTaken(id, req));
    }

    @PostMapping("/loans-taken/{id}/installments")
    public ResponseEntity<LoanTakenResponse> switchNasiyaToInstallments(
            @PathVariable Long id, @RequestBody NasiyaInstallmentsRequest req) {
        return ResponseEntity.ok(financeService.switchNasiyaToInstallments(id, req));
    }

    @PostMapping("/loans-taken/{id}/left")
    public ResponseEntity<LoanTakenResponse> setNasiyaLeft(
            @PathVariable Long id, @RequestBody NasiyaLeftRequest req) {
        return ResponseEntity.ok(financeService.setNasiyaLeft(id, req));
    }

    @PutMapping("/loans-taken/{id}/plan")
    public ResponseEntity<LoanTakenResponse> setLoanTakenPlan(
            @PathVariable Long id, @RequestBody java.util.Map<String, java.math.BigDecimal> body) {
        return ResponseEntity.ok(financeService.setLoanTakenPlan(id, body.get("plannedMonthlyPayment")));
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

    // ---- "Already paid" mark (no transaction, no money movement) ----

    @PostMapping("/mark-paid")
    public ResponseEntity<MarkPaidResponse> markPaid(@Valid @RequestBody MarkPaidRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(financeService.markPaid(req));
    }

    /** @param month YYYY-MM; blank → the current month. */
    @GetMapping("/mark-paid")
    public ResponseEntity<List<MarkPaidResponse>> listMarks(
            @RequestParam(required = false) String month) {
        return ResponseEntity.ok(financeService.listMarks(month));
    }

    @DeleteMapping("/mark-paid/{id}")
    public ResponseEntity<Void> deleteMark(@PathVariable Long id) {
        financeService.deleteMark(id);
        return ResponseEntity.noContent().build();
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
