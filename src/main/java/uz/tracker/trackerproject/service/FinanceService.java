package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.request.*;
import uz.tracker.trackerproject.dto.request.MonthlyPaymentPayRequest.Mode;
import uz.tracker.trackerproject.dto.response.*;
import uz.tracker.trackerproject.entity.*;
import uz.tracker.trackerproject.enums.CounterpartyKind;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.enums.RepaymentType;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.exception.ResourceNotFoundException;
import uz.tracker.trackerproject.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class FinanceService {

    private final DebtRepository debtRepository;
    private final LoanGivenRepository loanGivenRepository;
    private final LoanTakenRepository loanTakenRepository;
    private final BankLoanRepository bankLoanRepository;
    private final MonthlyPaymentRepository monthlyPaymentRepository;
    private final DonationRepository donationRepository;
    private final InvestmentRepository investmentRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final CardRepository cardRepository;
    private final MarkPaidRepository markPaidRepository;
    private final CardService cardService;
    private final MonthCloseService monthCloseService;
    private final SettingsService settingsService;
    private final CounterpartyService counterpartyService;

    /** Targets that support the "already paid" (no-transaction) mark. */
    private static final Set<String> MARK_KINDS =
            Set.of("SUBSCRIPTION", "BANK", "PERSONAL_LOAN", "DEBT", "BUCKET");
    private static final Set<String> MARK_BUCKETS =
            Set.of("DONATION", "EMERGENCY", "INVESTMENTS", "STOCKS");

    // ---- Debts ----

    @Transactional(readOnly = true)
    public List<DebtResponse> getAllDebts() {
        return debtRepository.findAllByOrderByDueDateAsc().stream().map(DebtResponse::from).toList();
    }

    @Transactional
    public DebtResponse createDebt(DebtRequest req) {
        Debt d = new Debt();
        d.setCreditorName(trim(req.getCreditorName()));
        linkCreditor(d, req.getLenderId());
        d.setTotalAmount(req.getTotalAmount());
        d.setPaidAmount(req.getPaidAmount() != null ? req.getPaidAmount() : BigDecimal.ZERO);
        d.setCurrency(req.getCurrency());
        d.setBorrowedDate(req.getBorrowedDate());
        d.setDueDate(req.getDueDate());
        d.setPaymentStartDate(resolvePaymentStart(req.getPaymentStartDate(), req.getBorrowedDate()));
        d.setStatus(req.getStatus() != null ? req.getStatus() : RecordStatus.PENDING);
        d.setDescription(req.getDescription());
        // Freeze the monthly tier contribution at creation time. Paying within a month
        // won't shift the user's tier — only an explicit edit (totalAmount / dueDate)
        // recomputes via updateDebt below.
        d.setMonthlyPayment(deriveMonthlyContribution(
                d.getTotalAmount(), d.getPaidAmount(), d.getDueDate()));
        return DebtResponse.from(debtRepository.save(d));
    }

    @Transactional
    public DebtResponse updateDebt(Long id, DebtRequest req) {
        Debt d = debtRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Debt", id));
        boolean termsChanged = !d.getTotalAmount().equals(req.getTotalAmount())
                || !java.util.Objects.equals(d.getDueDate(), req.getDueDate());
        d.setCreditorName(trim(req.getCreditorName()));
        linkCreditor(d, req.getLenderId());
        d.setTotalAmount(req.getTotalAmount());
        if (req.getPaidAmount() != null) d.setPaidAmount(req.getPaidAmount());
        d.setCurrency(req.getCurrency());
        d.setBorrowedDate(req.getBorrowedDate());
        d.setDueDate(req.getDueDate());
        // Only overwrite the payment-start when the request actually carries one; an
        // omitted value leaves the stored month (and any legacy null) untouched.
        if (req.getPaymentStartDate() != null) {
            d.setPaymentStartDate(req.getPaymentStartDate().withDayOfMonth(1));
        }
        if (req.getStatus() != null) d.setStatus(req.getStatus());
        d.setDescription(req.getDescription());
        // Only recompute the frozen monthly when the loan terms genuinely changed.
        // Plain edits (description, status, paidAmount via /repay) leave it stable.
        if (termsChanged || d.getMonthlyPayment() == null) {
            d.setMonthlyPayment(deriveMonthlyContribution(
                    d.getTotalAmount(), d.getPaidAmount(), d.getDueDate()));
        }
        return DebtResponse.from(debtRepository.save(d));
    }

    /**
     * Change ONLY the monthly repayment plan. Separate from updateLoanTaken so adjusting the
     * amount can't touch the loan's terms — that path re-derives monthlyPayment whenever the
     * total or due date look changed, which has nothing to do with the plan.
     *
     * @param amount a positive amount makes the loan MONTHLY at it; null or non-positive clears the
     *               plan, and the loan is paid back ASAP.
     */
    @Transactional
    public LoanTakenResponse setLoanTakenPlan(Long id, BigDecimal amount) {
        LoanTaken l = loanTakenRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("LoanTaken", id));
        applyRepayment(l, null, amount);
        return LoanTakenResponse.from(loanTakenRepository.save(l));
    }

    @Transactional
    public void deleteDebt(Long id) {
        Debt d = debtRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Debt", id));
        if (d.getPaidAmount() != null && d.getPaidAmount().signum() > 0) {
            throw new IllegalArgumentException(
                    "Cannot delete a debt with payments recorded. Clear the paid amount first.");
        }
        debtRepository.delete(d);
    }

    // ---- Loans Given ----

    @Transactional(readOnly = true)
    public List<LoanGivenResponse> getAllLoansGiven() {
        return loanGivenRepository.findAllByOrderByExpectedReturnDateAsc().stream().map(LoanGivenResponse::from).toList();
    }

    @Transactional
    public LoanGivenResponse createLoanGiven(LoanGivenRequest req) {
        LoanGiven l = saveLoanGiven(new LoanGiven(), req, null);
        if (Boolean.TRUE.equals(req.getMoveMoney())) {
            // Out of the wallet in the same step: the LOAN_GIVEN transaction the record then
            // originates from, as if it had been recorded first (TransactionService's path).
            Transaction tx = mirrorTransaction(TransactionType.EXPENSE, TransactionSubType.LOAN_GIVEN,
                    l.getTotalAmount(), l.getCurrency(), l.getLentDate(), req.getCardId(), null, l.getDebtorName());
            l.setOriginatingTransactionId(tx.getId());
            l = loanGivenRepository.save(l);
        }
        return LoanGivenResponse.from(l);
    }

    public LoanGiven createLoanGivenFromTransaction(LoanGivenRequest req, Long transactionId) {
        return saveLoanGiven(new LoanGiven(), req, transactionId);
    }

    private LoanGiven saveLoanGiven(LoanGiven l, LoanGivenRequest req, Long transactionId) {
        l.setDebtorName(trim(req.getDebtorName()));
        linkBorrower(l, req.getBorrowerId());
        l.setTotalAmount(req.getTotalAmount());
        l.setReceivedAmount(req.getReceivedAmount() != null ? req.getReceivedAmount() : BigDecimal.ZERO);
        l.setCurrency(req.getCurrency());
        l.setLentDate(req.getLentDate());
        l.setExpectedReturnDate(req.getExpectedReturnDate());
        l.setStatus(req.getStatus() != null ? req.getStatus() : RecordStatus.PENDING);
        l.setDescription(req.getDescription());
        if (transactionId != null) l.setOriginatingTransactionId(transactionId);
        return loanGivenRepository.save(l);
    }

    /**
     * Lend more to a borrower who asked again. Raises the existing loan's total rather than
     * opening a second record, so one borrower stays one row and the outstanding figure is
     * the whole of what they owe.
     */
    @Transactional
    public void addToLoanGiven(Long id, BigDecimal amount) {
        LoanGiven l = loanGivenRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("LoanGiven", id));
        BigDecimal current = l.getTotalAmount() != null ? l.getTotalAmount() : BigDecimal.ZERO;
        l.setTotalAmount(current.add(amount));
        // Lending again reopens a loan that had been fully repaid.
        if (l.getStatus() == RecordStatus.PAID) l.setStatus(RecordStatus.PENDING);
        loanGivenRepository.save(l);
    }

    /** Reverse of {@link #addToLoanGiven} — used when a top-up transaction is edited or deleted. */
    @Transactional
    public void removeFromLoanGiven(Long id, BigDecimal amount) {
        LoanGiven l = loanGivenRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("LoanGiven", id));
        BigDecimal current = l.getTotalAmount() != null ? l.getTotalAmount() : BigDecimal.ZERO;
        BigDecimal next = current.subtract(amount);
        l.setTotalAmount(next.signum() < 0 ? BigDecimal.ZERO : next);
        loanGivenRepository.save(l);
    }

    @Transactional
    public LoanGivenResponse updateLoanGiven(Long id, LoanGivenRequest req) {
        LoanGiven l = loanGivenRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("LoanGiven", id));
        l.setDebtorName(trim(req.getDebtorName()));
        linkBorrower(l, req.getBorrowerId());
        l.setTotalAmount(req.getTotalAmount());
        if (req.getReceivedAmount() != null) l.setReceivedAmount(req.getReceivedAmount());
        l.setCurrency(req.getCurrency());
        l.setLentDate(req.getLentDate());
        l.setExpectedReturnDate(req.getExpectedReturnDate());
        if (req.getStatus() != null) l.setStatus(req.getStatus());
        l.setDescription(req.getDescription());
        // The transaction it came from follows — unless money was lent again on top of it, when the
        // total is no longer that one transaction's amount.
        syncMirror(l.getOriginatingTransactionId(), l.getTotalAmount(), l.getLentDate(),
                !transactionRepository.existsByLoanGivenId(l.getId()));
        return LoanGivenResponse.from(loanGivenRepository.save(l));
    }

    @Transactional
    public void deleteLoanGiven(Long id) {
        LoanGiven l = loanGivenRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("LoanGiven", id));
        if (l.getReceivedAmount() != null && l.getReceivedAmount().signum() > 0) {
            throw new IllegalArgumentException(
                    "Cannot delete a lent loan with payments received. Clear received amount first.");
        }
        deleteMirror(l.getOriginatingTransactionId());
        loanGivenRepository.delete(l);
    }

    // ---- Loans Taken ----

    @Transactional(readOnly = true)
    public List<LoanTakenResponse> getAllLoansTaken() {
        return loanTakenRepository.findAllByOrderByDueDateAsc().stream().map(LoanTakenResponse::from).toList();
    }

    @Transactional
    public LoanTakenResponse createLoanTaken(LoanTakenRequest req) {
        LoanTaken l = saveLoanTaken(new LoanTaken(), req, null);
        if (Boolean.TRUE.equals(req.getMoveMoney())) {
            // Into the wallet in the same step: the LOAN_RECEIVED transaction the record then
            // originates from, as if it had been recorded first (TransactionService's path).
            Transaction tx = mirrorTransaction(TransactionType.INCOME, TransactionSubType.LOAN_RECEIVED,
                    l.getTotalAmount(), l.getCurrency(), l.getBorrowedDate(), req.getCardId(), null, l.getLenderName());
            l.setOriginatingTransactionId(tx.getId());
            l = loanTakenRepository.save(l);
        }
        return LoanTakenResponse.from(l);
    }

    public LoanTaken createLoanTakenFromTransaction(LoanTakenRequest req, Long transactionId) {
        return saveLoanTaken(new LoanTaken(), req, transactionId);
    }

    private LoanTaken saveLoanTaken(LoanTaken l, LoanTakenRequest req, Long transactionId) {
        l.setLenderName(trim(req.getLenderName()));
        linkLender(l, req.getLenderId());
        l.setTotalAmount(req.getTotalAmount());
        l.setPaidAmount(req.getPaidAmount() != null ? req.getPaidAmount() : BigDecimal.ZERO);
        l.setCurrency(req.getCurrency());
        l.setBorrowedDate(req.getBorrowedDate());
        l.setDueDate(req.getDueDate());
        applyRepayment(l, req.getRepaymentType(), req.getPlannedMonthlyPayment());
        l.setPaymentStartDate(resolvePaymentStart(req.getPaymentStartDate(), req.getBorrowedDate()));
        l.setStatus(req.getStatus() != null ? req.getStatus() : RecordStatus.PENDING);
        l.setDescription(req.getDescription());
        if (transactionId != null) l.setOriginatingTransactionId(transactionId);
        // Always frozen on save through this path: createLoanTaken (new entity) and
        // createLoanTakenFromTransaction (new entity) both create fresh records.
        if (l.getMonthlyPayment() == null) {
            l.setMonthlyPayment(deriveMonthlyContribution(
                    l.getTotalAmount(), l.getPaidAmount(), l.getDueDate()));
        }
        return loanTakenRepository.save(l);
    }

    @Transactional
    public LoanTakenResponse updateLoanTaken(Long id, LoanTakenRequest req) {
        LoanTaken l = loanTakenRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("LoanTaken", id));
        boolean termsChanged = !l.getTotalAmount().equals(req.getTotalAmount())
                || !java.util.Objects.equals(l.getDueDate(), req.getDueDate());
        l.setLenderName(trim(req.getLenderName()));
        linkLender(l, req.getLenderId());
        l.setTotalAmount(req.getTotalAmount());
        if (req.getPaidAmount() != null) l.setPaidAmount(req.getPaidAmount());
        l.setCurrency(req.getCurrency());
        l.setBorrowedDate(req.getBorrowedDate());
        l.setDueDate(req.getDueDate());
        applyRepayment(l, req.getRepaymentType(), req.getPlannedMonthlyPayment());
        if (req.getPaymentStartDate() != null) {
            l.setPaymentStartDate(req.getPaymentStartDate().withDayOfMonth(1));
        }
        if (req.getStatus() != null) l.setStatus(req.getStatus());
        l.setDescription(req.getDescription());
        if (termsChanged || l.getMonthlyPayment() == null) {
            l.setMonthlyPayment(deriveMonthlyContribution(
                    l.getTotalAmount(), l.getPaidAmount(), l.getDueDate()));
        }
        syncMirror(l.getOriginatingTransactionId(), l.getTotalAmount(), l.getBorrowedDate(), true);
        return LoanTakenResponse.from(loanTakenRepository.save(l));
    }

    @Transactional
    public void deleteLoanTaken(Long id) {
        LoanTaken l = loanTakenRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("LoanTaken", id));
        if (l.getPaidAmount() != null && l.getPaidAmount().signum() > 0) {
            throw new IllegalArgumentException(
                    "Cannot delete a borrowed loan with payments recorded. Clear the paid amount first.");
        }
        deleteMirror(l.getOriginatingTransactionId());
        loanTakenRepository.delete(l);
    }

    // ---- Bank Loans ----

    @Transactional(readOnly = true)
    public List<BankLoanResponse> getAllBankLoans() {
        return bankLoanRepository.findAll().stream().map(BankLoanResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<String> getBankNameSuggestions(String query) {
        return bankLoanRepository.findDistinctBankNames(query == null ? "" : query.trim());
    }

    @Transactional
    public BankLoanResponse createBankLoan(BankLoanRequest req) {
        BankLoan b = new BankLoan();
        applyBankLoan(b, req);
        return BankLoanResponse.from(bankLoanRepository.save(b));
    }

    @Transactional
    public BankLoanResponse updateBankLoan(Long id, BankLoanRequest req) {
        BankLoan b = bankLoanRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("BankLoan", id));
        applyBankLoan(b, req);
        return BankLoanResponse.from(bankLoanRepository.save(b));
    }

    private void applyBankLoan(BankLoan b, BankLoanRequest req) {
        b.setBankName(req.getBankName());
        b.setLoanName(req.getLoanName());
        b.setTotalAmount(req.getTotalAmount());
        b.setCurrency(req.getCurrency());
        b.setTakenDate(req.getTakenDate());
        b.setEndDate(req.getEndDate());
        b.setMonthlyPayment(req.getMonthlyPayment());
    }

    @Transactional
    public void deleteBankLoan(Long id) {
        if (!bankLoanRepository.existsById(id)) throw new ResourceNotFoundException("BankLoan", id);
        bankLoanRepository.deleteById(id);
    }

    // ---- Monthly Payments ----

    @Transactional(readOnly = true)
    public List<MonthlyPaymentResponse> getAllMonthlyPayments() {
        return monthlyPaymentRepository.findAllByOrderByDueDayAsc().stream()
                .map(this::enrichMonthlyPayment).toList();
    }

    @Transactional
    public MonthlyPaymentResponse createMonthlyPayment(MonthlyPaymentRequest req) {
        MonthlyPayment m = new MonthlyPayment();
        applyMonthlyPayment(m, req);
        return enrichMonthlyPayment(monthlyPaymentRepository.save(m));
    }

    @Transactional
    public MonthlyPaymentResponse updateMonthlyPayment(Long id, MonthlyPaymentRequest req) {
        MonthlyPayment m = monthlyPaymentRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("MonthlyPayment", id));
        applyMonthlyPayment(m, req);
        return enrichMonthlyPayment(monthlyPaymentRepository.save(m));
    }

    @Transactional
    public void deleteMonthlyPayment(Long id) {
        if (!monthlyPaymentRepository.existsById(id)) throw new ResourceNotFoundException("MonthlyPayment", id);
        monthlyPaymentRepository.deleteById(id);
    }

    @Transactional
    public MonthlyPaymentResponse payMonthlyPayment(Long id, MonthlyPaymentPayRequest req) {
        settingsService.assertStableIncomeSet();
        monthCloseService.assertMonthOpen(req.getPaymentDate());
        MonthlyPayment m = monthlyPaymentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MonthlyPayment", id));

        BigDecimal amount = req.getAmount();
        Mode mode = req.getMode();

        Transaction tx = new Transaction();
        tx.setType(TransactionType.EXPENSE);
        tx.setSubType(TransactionSubType.REGULAR_EXPENSE);
        tx.setAmount(amount);
        tx.setCurrency(m.getCurrency());
        tx.setDescription(m.getName());
        tx.setTransactionDate(req.getPaymentDate());
        tx.setMonthlyPaymentId(m.getId());
        if (m.getCategory() != null) tx.setCategory(m.getCategory());

        switch (mode) {
            case CASH -> {
                tx.setCard(null);
                tx.setCashAmount(amount);
            }
            case CARD -> {
                Card card = resolveCardForPayment(req.getCardId(), m.getCurrency());
                cardService.assertSufficientBalance(card, amount);
                tx.setCard(card);
                tx.setCashAmount(BigDecimal.ZERO);
            }
            case BOTH -> {
                BigDecimal cash = req.getCashAmount();
                if (cash == null || cash.signum() <= 0 || cash.compareTo(amount) >= 0) {
                    throw new IllegalArgumentException(
                            "Cash portion must be greater than 0 and less than the total amount for split payments.");
                }
                Card card = resolveCardForPayment(req.getCardId(), m.getCurrency());
                cardService.assertSufficientBalance(card, amount.subtract(cash));
                tx.setCard(card);
                tx.setCashAmount(cash);
            }
        }

        transactionRepository.save(tx);

        if (Boolean.TRUE.equals(req.getUpdateAmountForFuture())) {
            m.setAmount(amount);
        }
        m.setNextDueDate(nextDueAfterPayment(m.getDueDay(), req.getPaymentDate()));
        monthlyPaymentRepository.save(m);

        return enrichMonthlyPayment(m);
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> getMonthlyPaymentPayments(Long id) {
        if (!monthlyPaymentRepository.existsById(id)) throw new ResourceNotFoundException("MonthlyPayment", id);
        return transactionRepository.findByMonthlyPaymentIdOrderByTransactionDateDesc(id).stream()
                .map(TransactionResponse::from).toList();
    }

    private Card resolveCardForPayment(Long cardId, Currency expectedCurrency) {
        if (cardId == null) throw new IllegalArgumentException("Card is required for CARD or BOTH payment mode.");
        Card card = cardRepository.findById(cardId)
                .orElseThrow(() -> new ResourceNotFoundException("Card", cardId));
        if (card.getCurrency() != expectedCurrency) {
            throw new IllegalArgumentException(
                    "Card currency (" + card.getCurrency() + ") does not match subscription currency (" + expectedCurrency + ")");
        }
        return card;
    }

    /**
     * The bill's next due date after a payment: its due day in the month AFTER the one the payment
     * is dated in — the month every other screen (the Plan, the advisor) counts that payment toward.
     *
     * <p>This used to add one month to the STORED date on every payment. So each extra payment in
     * the same month — the rest of a partial payment, or a payment re-recorded after deleting the
     * first — pushed it a further month out, and so did a date already set a month ahead when the
     * bill was created (the web form had a "Next due date" field until 2026-09-07). A bill paid
     * once in September then read "next due in November". Anchoring on the payment's own month
     * makes it idempotent.
     */
    static LocalDate nextDueAfterPayment(Integer dueDay, LocalDate paymentDate) {
        YearMonth next = YearMonth.from(paymentDate).plusMonths(1);
        int day = dueDay == null ? paymentDate.getDayOfMonth() : dueDay;
        return next.atDay(Math.min(Math.max(day, 1), next.lengthOfMonth()));
    }

    private MonthlyPaymentResponse enrichMonthlyPayment(MonthlyPayment m) {
        BigDecimal totalPaid = transactionRepository.sumAmountByMonthlyPaymentId(m.getId());
        long paymentCount = transactionRepository.countByMonthlyPaymentId(m.getId());
        return MonthlyPaymentResponse.from(m, totalPaid, paymentCount);
    }

    private void applyMonthlyPayment(MonthlyPayment m, MonthlyPaymentRequest req) {
        m.setName(req.getName());
        m.setAmount(req.getAmount());
        m.setCurrency(req.getCurrency());
        m.setDueDay(req.getDueDay());
        m.setActive(req.getActive() != null ? req.getActive() : true);
        m.setDescription(req.getDescription());
        m.setNextDueDate(req.getNextDueDate());
        m.setSubscribedSince(req.getSubscribedSince());
        if (req.getCategoryId() != null) {
            m.setCategory(categoryRepository.findById(req.getCategoryId()).orElse(null));
        } else {
            m.setCategory(null);
        }
    }

    // ---- Donations ----

    /**
     * @param month YYYY-MM to scope the list to; blank → all time. The tab's headline can only
     *              agree with the month's Donation bucket if it is reading the same month.
     */
    @Transactional(readOnly = true)
    public List<DonationResponse> getAllDonations(String month) {
        if (month == null || month.isBlank()) {
            return donationRepository.findAllByOrderByDonationDateDesc().stream()
                    .map(DonationResponse::from).toList();
        }
        YearMonth ym = parseMonth(month);
        return donationRepository
                .findByDonationDateBetweenOrderByDonationDateDesc(ym.atDay(1), ym.atEndOfMonth())
                .stream().map(DonationResponse::from).toList();
    }

    @Transactional
    public DonationResponse createDonation(DonationRequest req) {
        // Direct creation (not via the Transaction modal). Mirror to a real EXPENSE
        // Transaction so the donation appears in the Transactions list and in the
        // bucket payment history together with txs created from the other side.
        Transaction tx = createBucketTransaction(
                TransactionSubType.DONATION, false,
                req.getAmount(), req.getCurrency(),
                req.getDonationDate(), req.getCardId(), req.getCategoryId(),
                donationTitle(req.getDescription(), Boolean.TRUE.equals(req.getAnonymous()),
                        req.getRecipientName()));
        return DonationResponse.from(saveDonation(new Donation(), req, tx.getId()));
    }

    public Donation createDonationFromTransaction(DonationRequest req, Long transactionId) {
        return saveDonation(new Donation(), req, transactionId);
    }

    private Donation saveDonation(Donation d, DonationRequest req, Long transactionId) {
        d.setRecipientName(req.getRecipientName());
        d.setAmount(req.getAmount());
        d.setCurrency(req.getCurrency());
        d.setDonationDate(req.getDonationDate());
        d.setDescription(req.getDescription());
        d.setAnonymous(req.getAnonymous() != null && req.getAnonymous());
        if (transactionId != null) d.setOriginatingTransactionId(transactionId);
        return donationRepository.save(d);
    }

    @Transactional
    public DonationResponse updateDonation(Long id, DonationRequest req) {
        Donation d = donationRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Donation", id));
        // Both dates, as TransactionService does: a donation may be edited neither OUT of a closed
        // month nor INTO one. createDonation is already gated (via createBucketTransaction), and a
        // closed month reports its frozen Donation total while the plan re-sums the rows live — so
        // an ungated edit here is the one way left to make those two disagree for ever.
        monthCloseService.assertMonthOpen(d.getDonationDate());
        monthCloseService.assertMonthOpen(req.getDonationDate());
        // A donation is one payment recorded in two places: this row is what the Donation bucket
        // sums, and its DONATION transaction is what the wallet balance and the Transactions list
        // read. Editing only this row left those two on the old figure, so the transaction moves
        // with it — the rule EmergencyService.update follows for the Emergencies tab.
        boolean anonymous = req.getAnonymous() != null
                ? req.getAnonymous() : Boolean.TRUE.equals(d.getAnonymous());
        donationMirror(d).ifPresent(tx -> moveDonationMirror(tx, req,
                donationTitle(req.getDescription(), anonymous, req.getRecipientName())));
        d.setRecipientName(req.getRecipientName());
        d.setAmount(req.getAmount());
        d.setCurrency(req.getCurrency());
        d.setDonationDate(req.getDonationDate());
        d.setDescription(req.getDescription());
        if (req.getAnonymous() != null) d.setAnonymous(req.getAnonymous());
        return DonationResponse.from(donationRepository.save(d));
    }

    @Transactional
    public void deleteDonation(Long id) {
        Donation d = donationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Donation", id));
        monthCloseService.assertMonthOpen(d.getDonationDate());
        // Deleting the row alone dropped the payment from the Donation bucket but left it spent:
        // the wallet stayed debited and the Transactions list kept it. The transaction goes too,
        // which returns the money to the wallet it came from.
        donationMirror(d).ifPresent(tx -> {
            // Its own month, which an edit made before the two were kept together can have left
            // different from the donation's — the wallet movement being undone happened there.
            monthCloseService.assertMonthOpen(tx.getTransactionDate());
            transactionRepository.delete(tx);
        });
        donationRepository.delete(d);
    }

    /**
     * The DONATION transaction a donation mirrors, when it has one. Rows created before the link
     * existed have none and are edited list-only rather than refused. A transaction re-filed under
     * another sub-type is no longer this donation's money (re-filing deletes the donation), so it is
     * never touched from here.
     */
    private Optional<Transaction> donationMirror(Donation d) {
        if (d.getOriginatingTransactionId() == null) return Optional.empty();
        return transactionRepository.findById(d.getOriginatingTransactionId())
                .filter(tx -> tx.getSubType() == TransactionSubType.DONATION);
    }

    /**
     * Carry a Donations-tab edit onto the transaction behind it: amount, date and title. The wallet
     * stays the one the money left — the Donations form offers no way to change it.
     */
    private void moveDonationMirror(Transaction tx, DonationRequest req, String title) {
        monthCloseService.assertMonthOpen(tx.getTransactionDate());
        Card card = tx.getCard();
        BigDecimal cash = tx.getCashAmount() == null ? BigDecimal.ZERO : tx.getCashAmount();
        boolean allCash = card == null || cash.compareTo(tx.getAmount()) >= 0;
        boolean allCard = card != null && cash.signum() == 0;
        if (!allCash && !allCard && req.getAmount().compareTo(tx.getAmount()) != 0) {
            // A new total does not say whether the cash part or the card part changed.
            throw new IllegalArgumentException(
                    "This donation was paid partly in cash and partly by card, so its amount can't be "
                            + "changed here. Edit it from Transactions, where both parts can be set.");
        }
        if (card != null) {
            if (card.getCurrency() != req.getCurrency()) {
                throw new IllegalArgumentException(
                        "Card currency (" + card.getCurrency() + ") does not match payment currency (" + req.getCurrency() + ")");
            }
            // Growing the amount spends more from the same card, so re-check the difference.
            if (allCard) cardService.assertSufficientBalance(card, req.getAmount().subtract(tx.getAmount()));
        }
        tx.setAmount(req.getAmount());
        tx.setCurrency(req.getCurrency());
        tx.setTransactionDate(req.getDonationDate());
        tx.setDescription(title);
        // Cash rows carry the whole amount as cash (the buildTransaction convention); a card row
        // keeps none, and a split keeps its cash part because its total cannot have changed.
        if (allCash) tx.setCashAmount(req.getAmount());
        transactionRepository.save(tx);
    }

    /** How a donation's transaction is titled: the description typed, else who received it. */
    private static String donationTitle(String description, boolean anonymous, String recipientName) {
        return description != null && !description.isBlank()
                ? description
                : "Donation to " + (anonymous ? "Anonymous" : recipientName);
    }

    // ---- Investments ----

    /**
     * @param month YYYY-MM to scope the list to; blank → all time. Investments are long-lived
     *              holdings, so the all-time list stays the default — a month is only useful for
     *              "what did I put aside in September", never for the portfolio itself.
     */
    @Transactional(readOnly = true)
    public List<InvestmentResponse> getAllInvestments(String month) {
        if (month == null || month.isBlank()) {
            return investmentRepository.findAllByOrderByPurchaseDateDesc().stream()
                    .map(InvestmentResponse::from).toList();
        }
        YearMonth ym = parseMonth(month);
        return investmentRepository
                .findByPurchaseDateBetweenOrderByPurchaseDateDesc(ym.atDay(1), ym.atEndOfMonth())
                .stream().map(InvestmentResponse::from).toList();
    }

    @Transactional
    public InvestmentResponse createInvestment(InvestmentRequest req) {
        // Opening balance: an investment the user already owned before tracking. Record it for
        // net-worth / portfolio purposes only — DON'T mirror a transaction (no wallet is debited,
        // nothing shows as spent now) and it won't count toward this month's Investments bucket.
        if (Boolean.TRUE.equals(req.getOpeningBalance())) {
            return InvestmentResponse.from(saveInvestment(new Investment(), req, null));
        }
        // Only an opening balance may start at 0 (a goal with nothing saved yet): a funded one
        // moves money out of a wallet, and a 0 transaction would be noise in every list.
        if (req.getInvestedAmount().signum() <= 0) {
            throw new IllegalArgumentException("The amount must be more than 0.");
        }
        // Direct creation. Mirror to an EXPENSE Transaction so the investment also shows in the
        // transactions list and in the bucket payment history. Emergency-fund investments book an
        // EMERGENCY_CONTRIBUTION (counts toward the Emergency bucket); the rest book INVESTMENT.
        String description = (req.getDescription() != null && !req.getDescription().isBlank())
                ? req.getDescription()
                : "Investment — " + req.getName();
        TransactionSubType sub = Boolean.TRUE.equals(req.getEmergencyFund())
                ? TransactionSubType.EMERGENCY_CONTRIBUTION : TransactionSubType.INVESTMENT;
        Transaction tx = createBucketTransaction(
                sub, Boolean.TRUE.equals(req.getSavingsGoal()),
                req.getInvestedAmount(), req.getCurrency(),
                req.getPurchaseDate(), req.getCardId(), req.getCategoryId(),
                description);
        Investment saved = saveInvestment(new Investment(), req, tx.getId());
        // Link the mirror tx back to the investment so the initial funding shows up in the
        // contributions history and never outlives the record: deleting either side now
        // removes the other (see deleteInvestment / TransactionService.reverseAutoCreated).
        tx.setInvestmentId(saved.getId());
        transactionRepository.save(tx);
        return InvestmentResponse.from(saved);
    }

    public Investment createInvestmentFromTransaction(InvestmentRequest req, Long transactionId) {
        return saveInvestment(new Investment(), req, transactionId);
    }

    private Investment saveInvestment(Investment i, InvestmentRequest req, Long transactionId) {
        applyInvestment(i, req);
        if (transactionId != null) i.setOriginatingTransactionId(transactionId);
        return investmentRepository.save(i);
    }

    /**
     * Edit a holding's own fields — name, broker, target, the emergency-fund / savings-goal flags.
     *
     * <p>Deliberately NOT gated on the month being open. A holding is a long-lived record spanning
     * many months, most of them open, with no single date to gate on: a blanket gate would
     * permanently block renaming or re-targeting a holding the moment any month closed. What made
     * an ungated edit dangerous was that the Investments-vs-Savings split was re-derived on every
     * read from these flags, so ticking one emptied a CLOSED month's bucket. The split is now
     * recorded on each funding transaction as it is written (see {@link AllocationBucket}), so
     * this edit only steers money added from here on. Every figure a month shows is computed from
     * those transactions, and they are created, edited and deleted only by month-gated paths
     * ({@link #createInvestment}, {@link #contributeToInvestment}, {@link #deleteInvestment},
     * TransactionService).
     */
    @Transactional
    public InvestmentResponse updateInvestment(Long id, InvestmentRequest req) {
        Investment i = investmentRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Investment", id));
        applyInvestment(i, req);
        return InvestmentResponse.from(investmentRepository.save(i));
    }

    @Transactional
    public void deleteInvestment(Long id) {
        Investment i = investmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Investment", id));
        // The investment's OWN mirror transaction goes with it — the mirror image of
        // TransactionService's "delete the tx → delete the investment" direction — so it must
        // not count as an external reference, or nothing created from a bucket/Investments
        // form could ever be deleted. Contributions and other linked rows still block: those
        // are separate money movements the user has to remove deliberately.
        Long originatingTxId = i.getOriginatingTransactionId();
        long refs = transactionRepository.findByInvestmentIdOrderByTransactionDateDesc(id).stream()
                .filter(t -> !java.util.Objects.equals(t.getId(), originatingTxId))
                .count();
        if (refs > 0) {
            throw new IllegalArgumentException(
                    "Cannot delete: " + refs + " transaction(s) are linked to this investment.");
        }
        // Removing the mirror row also refunds the wallet (balances are summed from
        // transactions). Legacy rows whose mirror was never linked are cleaned up here too.
        if (originatingTxId != null) {
            transactionRepository.findById(originatingTxId).ifPresent(tx -> {
                monthCloseService.assertMonthOpen(tx.getTransactionDate());
                transactionRepository.delete(tx);
            });
        }
        investmentRepository.delete(i);
    }

    @Transactional
    public void addFundsToInvestment(Long id, BigDecimal additionalAmount) {
        Investment i = investmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Investment", id));
        i.setInvestedAmount(i.getInvestedAmount().add(additionalAmount));
        investmentRepository.save(i);
    }

    /**
     * Take money out of an investment into a wallet (the owner's "take 1M from IMAN"): an INCOME
     * INVESTMENT_WITHDRAWAL transaction linked to the holding — so deleting it puts the money back
     * and editing it re-applies it (TransactionService) — then the holding goes down by it. Never
     * more than the holding's value. The money is the owner's own coming back: it is not income in
     * any figure, and it takes nothing off what was set aside in the month the holding was funded.
     */
    @Transactional
    public InvestmentResponse withdrawFromInvestment(Long id, InvestmentWithdrawRequest req) {
        Investment i = investmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Investment", id));
        if (req.getCurrency() != i.getCurrency()) {
            throw new IllegalArgumentException(
                    "Withdrawal currency (" + req.getCurrency() + ") does not match investment currency (" + i.getCurrency() + ")");
        }
        BigDecimal value = i.getCurrentValue() != null ? i.getCurrentValue() : i.getInvestedAmount();
        if (req.getAmount().compareTo(value == null ? BigDecimal.ZERO : value) > 0) {
            throw new IllegalArgumentException(String.format(
                    "Only %s %s is in %s — you can take out at most that.", value, i.getCurrency(), i.getName()));
        }
        String description = req.getDescription() != null && !req.getDescription().isBlank()
                ? req.getDescription() : "Taken from " + i.getName();
        Transaction tx = mirrorTransaction(TransactionType.INCOME, TransactionSubType.INVESTMENT_WITHDRAWAL,
                req.getAmount(), req.getCurrency(), req.getDate(), req.getCardId(), null, description);
        tx.setInvestmentId(i.getId());
        transactionRepository.save(tx);
        takeOut(i, req.getAmount());
        return InvestmentResponse.from(investmentRepository.save(i));
    }

    /** A withdrawal recorded (or grown) from the Transactions page: the holding goes down by it. */
    @Transactional
    public void applyWithdrawal(Long id, BigDecimal amount) {
        investmentRepository.findById(id).ifPresent(i -> {
            takeOut(i, amount);
            investmentRepository.save(i);
        });
    }

    /** A withdrawal deleted (or shrunk): the money goes back into the holding. */
    @Transactional
    public void reverseWithdrawal(Long id, BigDecimal amount) {
        investmentRepository.findById(id).ifPresent(i -> {
            i.setInvestedAmount(i.getInvestedAmount().add(amount));
            if (i.getCurrentValue() != null) i.setCurrentValue(i.getCurrentValue().add(amount));
            investmentRepository.save(i);
        });
    }

    /** Invested total down by {@code amount}, never below 0; a tracked value down by it too. */
    private static void takeOut(Investment i, BigDecimal amount) {
        BigDecimal invested = i.getInvestedAmount().subtract(amount);
        i.setInvestedAmount(invested.signum() < 0 ? BigDecimal.ZERO : invested);
        if (i.getCurrentValue() != null) {
            BigDecimal value = i.getCurrentValue().subtract(amount);
            i.setCurrentValue(value.signum() < 0 ? BigDecimal.ZERO : value);
        }
    }

    /** Subtract from an investment's invested total (used when a fund-add transaction is removed or shrunk). */
    @Transactional
    public void removeFundsFromInvestment(Long id, BigDecimal amount) {
        Investment i = investmentRepository.findById(id).orElse(null);
        if (i == null) return;
        BigDecimal next = i.getInvestedAmount().subtract(amount);
        if (next.signum() < 0) next = BigDecimal.ZERO;
        i.setInvestedAmount(next);
        investmentRepository.save(i);
    }

    private void applyInvestment(Investment i, InvestmentRequest req) {
        i.setName(req.getName());
        i.setType(req.getType());
        i.setInvestedAmount(req.getInvestedAmount());
        i.setCurrency(req.getCurrency());
        i.setPurchaseDate(req.getPurchaseDate());
        i.setBroker(req.getBroker());
        i.setDescription(req.getDescription());
        i.setEmergencyFund(Boolean.TRUE.equals(req.getEmergencyFund()));
        // A savings goal is never the emergency fund — emergencyFund wins if both are set.
        i.setSavingsGoal(Boolean.TRUE.equals(req.getSavingsGoal()) && !Boolean.TRUE.equals(req.getEmergencyFund()));
        i.setTargetAmount(req.getTargetAmount());
        // A goal's deadline, monthly payment and payment start month change only when the request
        // carries them: the bot edits a holding by echoing the fields it knows, which predate these
        // three, so "left out" must keep what is stored. Sent — null included — they are set; a null
        // clears (a cleared start month means the purchase month). The start month is stored as the
        // 1st of its month (Investment.setPaymentStartDate).
        if (req.targetDateGiven()) i.setTargetDate(req.getTargetDate());
        if (req.monthlyContributionGiven()) i.setMonthlyContribution(req.getMonthlyContribution());
        if (req.paymentStartDateGiven()) i.setPaymentStartDate(req.getPaymentStartDate());
        // currentValue is optional: null = "tracks investedAmount" (the response mapper falls back).
        i.setCurrentValue(req.getCurrentValue());
        i.setOpeningBalance(Boolean.TRUE.equals(req.getOpeningBalance()));
    }

    /**
     * Contribute additional money to an existing investment / savings goal. Mirrors a real
     * EXPENSE Transaction (sub-type INVESTMENT) from the chosen wallet so the contribution
     * appears in the Transactions list and reduces spendable balance, links it to the
     * investment via investmentId for the contributions history, and bumps the invested
     * total (and currentValue, when it is being tracked explicitly).
     */
    @Transactional
    public InvestmentResponse contributeToInvestment(Long id, InvestmentContributeRequest req) {
        // Guard the noWallet path too: a record-only contribution still lands on this date's
        // month figures, so a closed month must reject it just like a wallet-backed one
        // (the wallet path re-asserts inside createBucketTransaction; that's harmless).
        settingsService.assertStableIncomeSet();
        monthCloseService.assertMonthOpen(req.getDate());
        Investment i = investmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Investment", id));
        if (req.getCurrency() != i.getCurrency()) {
            throw new IllegalArgumentException(
                    "Contribution currency (" + req.getCurrency() + ") does not match investment currency (" + i.getCurrency() + ")");
        }
        // noWallet: the money came from outside the tracked wallets (e.g. it's already sitting in
        // the investment account). Bump the invested total but DON'T create a transaction or debit
        // a wallet. With a wallet, mirror a real EXPENSE so it shows in the transactions list.
        if (!Boolean.TRUE.equals(req.getNoWallet())) {
            String description = (req.getDescription() != null && !req.getDescription().isBlank())
                    ? req.getDescription()
                    : "Contribution — " + i.getName();
            // Emergency-fund investments book an EMERGENCY_CONTRIBUTION so the top-up counts toward
            // the Emergency allocation bucket (by transaction date); everything else books INVESTMENT.
            TransactionSubType sub = Boolean.TRUE.equals(i.getEmergencyFund())
                    ? TransactionSubType.EMERGENCY_CONTRIBUTION : TransactionSubType.INVESTMENT;
            Transaction tx = createBucketTransaction(
                    sub, Boolean.TRUE.equals(i.getSavingsGoal()),
                    req.getAmount(), req.getCurrency(),
                    req.getDate(), req.getCardId(), req.getCategoryId(),
                    description);
            tx.setInvestmentId(i.getId());
            transactionRepository.save(tx);
        }

        i.setInvestedAmount(i.getInvestedAmount().add(req.getAmount()));
        // Only adjust currentValue when it is being tracked explicitly; a null currentValue
        // keeps "tracks investedAmount" semantics and grows automatically via the fallback.
        if (i.getCurrentValue() != null) {
            i.setCurrentValue(i.getCurrentValue().add(req.getAmount()));
        }
        return InvestmentResponse.from(investmentRepository.save(i));
    }

    /** Update only an investment's current/market value to reflect platform growth (no transaction). */
    @Transactional
    public InvestmentResponse updateInvestmentValue(Long id, InvestmentValueRequest req) {
        Investment i = investmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Investment", id));
        i.setCurrentValue(req.getCurrentValue());
        return InvestmentResponse.from(investmentRepository.save(i));
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> getInvestmentContributions(Long id) {
        if (!investmentRepository.existsById(id)) throw new ResourceNotFoundException("Investment", id);
        return transactionRepository.findByInvestmentIdOrderByTransactionDateDesc(id).stream()
                .map(TransactionResponse::from).toList();
    }

    // ---- Repayments ----

    @Transactional
    public LoanTakenResponse repayLoanTaken(Long id, RepaymentRequest req) {
        settingsService.assertStableIncomeSet();
        monthCloseService.assertMonthOpen(req.getPaymentDate());
        LoanTaken loan = loanTakenRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("LoanTaken", id));

        BigDecimal remaining = loan.getTotalAmount().subtract(loan.getPaidAmount());
        if (req.getAmount().compareTo(remaining) > 0) {
            throw new IllegalArgumentException(
                    String.format("Payment of %s %s exceeds remaining balance of %s %s",
                            req.getAmount(), loan.getCurrency(), remaining, loan.getCurrency()));
        }

        loan.setPaidAmount(loan.getPaidAmount().add(req.getAmount()));
        loan.setStatus(loan.getPaidAmount().compareTo(loan.getTotalAmount()) >= 0
                ? RecordStatus.PAID : RecordStatus.PARTIALLY_PAID);
        loanTakenRepository.save(loan);

        Transaction tx = newRepaymentTx(TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT,
                req, "Loan repayment to " + loan.getLenderName(), loan.getCurrency());
        tx.setRepaidLoanTakenId(loan.getId());
        transactionRepository.save(tx);

        return LoanTakenResponse.from(loan);
    }

    @Transactional
    public DebtResponse repayDebt(Long id, RepaymentRequest req) {
        settingsService.assertStableIncomeSet();
        monthCloseService.assertMonthOpen(req.getPaymentDate());
        Debt debt = debtRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Debt", id));

        BigDecimal remaining = debt.getTotalAmount().subtract(debt.getPaidAmount());
        if (req.getAmount().compareTo(remaining) > 0) {
            throw new IllegalArgumentException(
                    String.format("Payment of %s %s exceeds remaining balance of %s %s",
                            req.getAmount(), debt.getCurrency(), remaining, debt.getCurrency()));
        }

        debt.setPaidAmount(debt.getPaidAmount().add(req.getAmount()));
        debt.setStatus(debt.getPaidAmount().compareTo(debt.getTotalAmount()) >= 0
                ? RecordStatus.PAID : RecordStatus.PARTIALLY_PAID);
        debtRepository.save(debt);

        Transaction tx = newRepaymentTx(TransactionType.EXPENSE, TransactionSubType.LOAN_REPAYMENT,
                req, "Debt repayment to " + debt.getCreditorName(), debt.getCurrency());
        tx.setRepaidDebtId(debt.getId());
        transactionRepository.save(tx);

        return DebtResponse.from(debt);
    }

    @Transactional
    public LoanGivenResponse markLoanGivenReturned(Long id, RepaymentRequest req) {
        settingsService.assertStableIncomeSet();
        monthCloseService.assertMonthOpen(req.getPaymentDate());
        LoanGiven loan = loanGivenRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("LoanGiven", id));

        BigDecimal pending = loan.getTotalAmount().subtract(loan.getReceivedAmount());
        if (req.getAmount().compareTo(pending) > 0) {
            throw new IllegalArgumentException(
                    String.format("Amount of %s %s exceeds pending %s %s",
                            req.getAmount(), loan.getCurrency(), pending, loan.getCurrency()));
        }

        loan.setReceivedAmount(loan.getReceivedAmount().add(req.getAmount()));
        loan.setStatus(loan.getReceivedAmount().compareTo(loan.getTotalAmount()) >= 0
                ? RecordStatus.PAID : RecordStatus.PARTIALLY_PAID);
        loanGivenRepository.save(loan);

        Transaction tx = newRepaymentTx(TransactionType.INCOME, TransactionSubType.LOAN_RETURNED_TO_ME,
                req, "Loan returned by " + loan.getDebtorName(), loan.getCurrency());
        tx.setRepaidLoanGivenId(loan.getId());
        transactionRepository.save(tx);

        return LoanGivenResponse.from(loan);
    }

    // ---- "Already paid" marks (no transaction, no money movement) ----

    /**
     * Record an "already paid" mark for a month. Creates no Transaction and moves no money;
     * the tier engine simply counts the mark toward the relevant "paid this month" total.
     * For PERSONAL_LOAN / DEBT we ALSO bump the entity's paidAmount (mirroring a real
     * repayment) so the remaining balance and tier shift accordingly.
     */
    @Transactional
    public MarkPaidResponse markPaid(MarkPaidRequest req) {
        String kind = req.getKind() == null ? "" : req.getKind().trim().toUpperCase();
        if (!MARK_KINDS.contains(kind)) {
            throw new IllegalArgumentException("Unknown mark kind: " + req.getKind());
        }
        YearMonth ym = parseMonth(req.getMonth());
        LocalDate monthFirst = ym.atDay(1);
        settingsService.assertStableIncomeSet();
        monthCloseService.assertMonthOpen(monthFirst);

        BigDecimal amount = req.getAmount();
        Currency currency = req.getCurrency();
        String bucket = null;

        switch (kind) {
            case "PERSONAL_LOAN" -> {
                LoanTaken loan = loanTakenRepository.findById(requireRef(req.getRefId(), "loan"))
                        .orElseThrow(() -> new ResourceNotFoundException("LoanTaken", req.getRefId()));
                assertMarkCurrency(currency, loan.getCurrency(), "loan");
                BigDecimal remaining = loan.getTotalAmount().subtract(loan.getPaidAmount());
                if (amount.compareTo(remaining) > 0) {
                    throw new IllegalArgumentException(String.format(
                            "Amount of %s %s exceeds remaining balance of %s %s",
                            amount, currency, remaining, currency));
                }
                loan.setPaidAmount(loan.getPaidAmount().add(amount));
                loan.setStatus(loan.getPaidAmount().compareTo(loan.getTotalAmount()) >= 0
                        ? RecordStatus.PAID : RecordStatus.PARTIALLY_PAID);
                loanTakenRepository.save(loan);
            }
            case "DEBT" -> {
                Debt debt = debtRepository.findById(requireRef(req.getRefId(), "debt"))
                        .orElseThrow(() -> new ResourceNotFoundException("Debt", req.getRefId()));
                assertMarkCurrency(currency, debt.getCurrency(), "debt");
                BigDecimal remaining = debt.getTotalAmount().subtract(debt.getPaidAmount());
                if (amount.compareTo(remaining) > 0) {
                    throw new IllegalArgumentException(String.format(
                            "Amount of %s %s exceeds remaining balance of %s %s",
                            amount, currency, remaining, currency));
                }
                debt.setPaidAmount(debt.getPaidAmount().add(amount));
                debt.setStatus(debt.getPaidAmount().compareTo(debt.getTotalAmount()) >= 0
                        ? RecordStatus.PAID : RecordStatus.PARTIALLY_PAID);
                debtRepository.save(debt);
            }
            case "SUBSCRIPTION" -> {
                MonthlyPayment mp = monthlyPaymentRepository.findById(requireRef(req.getRefId(), "subscription"))
                        .orElseThrow(() -> new ResourceNotFoundException("MonthlyPayment", req.getRefId()));
                assertMarkCurrency(currency, mp.getCurrency(), "subscription");
            }
            case "BANK" -> {
                if (req.getRefId() != null) {
                    BankLoan b = bankLoanRepository.findById(req.getRefId())
                            .orElseThrow(() -> new ResourceNotFoundException("BankLoan", req.getRefId()));
                    assertMarkCurrency(currency, b.getCurrency(), "bank loan");
                }
            }
            case "BUCKET" -> {
                bucket = req.getBucket() == null ? "" : req.getBucket().trim().toUpperCase();
                if (!MARK_BUCKETS.contains(bucket)) {
                    throw new IllegalArgumentException("Unknown bucket: " + req.getBucket());
                }
            }
            default -> throw new IllegalArgumentException("Unknown mark kind: " + req.getKind());
        }

        MarkPaid mark = new MarkPaid();
        mark.setKind(kind);
        mark.setRefId("BUCKET".equals(kind) ? null : req.getRefId());
        mark.setBucket(bucket);
        mark.setMonth(monthFirst);
        mark.setAmount(amount);
        mark.setCurrency(currency);
        mark.setNote(req.getNote() != null && req.getNote().isBlank() ? null : req.getNote());
        return MarkPaidResponse.from(markPaidRepository.save(mark));
    }

    /**
     * The marks recorded for a month. A mark is the only "paid" figure with no transaction behind
     * it, so it is also the only one the user cannot find in any list — which is exactly how a
     * mistyped amount ends up silently inflating the allocation forever. This is that list.
     *
     * @param month YYYY-MM; blank → the current month, same rule as {@link #markPaid}.
     */
    @Transactional(readOnly = true)
    public List<MarkPaidResponse> listMarks(String month) {
        return markPaidRepository.findByMonthOrderByIdDesc(parseMonth(month).atDay(1)).stream()
                .map(MarkPaidResponse::from).toList();
    }

    /**
     * Undo an "already paid" mark. BUCKET / SUBSCRIPTION / BANK marks are pure bookkeeping and just
     * disappear, but a PERSONAL_LOAN / DEBT mark bumped the entity's paidAmount when it was created
     * (mirroring a real repayment), so deleting it must back that bump out — otherwise the balance
     * stays permanently understated and the month's ask shrinks with it.
     */
    @Transactional
    public void deleteMark(Long id) {
        MarkPaid mark = markPaidRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MarkPaid", id));
        // The close snapshot never counted this mark, but the tier and ledger history for that
        // month did — so a closed month stays locked here exactly as it is for transactions.
        monthCloseService.assertMonthOpen(mark.getMonth());

        if (mark.getRefId() != null) {
            switch (mark.getKind() == null ? "" : mark.getKind()) {
                case "PERSONAL_LOAN" -> loanTakenRepository.findById(mark.getRefId()).ifPresent(loan -> {
                    loan.setPaidAmount(clampZero(loan.getPaidAmount().subtract(mark.getAmount())));
                    loan.setStatus(repaymentStatus(loan.getPaidAmount(), loan.getTotalAmount()));
                    loanTakenRepository.save(loan);
                });
                case "DEBT" -> debtRepository.findById(mark.getRefId()).ifPresent(debt -> {
                    debt.setPaidAmount(clampZero(debt.getPaidAmount().subtract(mark.getAmount())));
                    debt.setStatus(repaymentStatus(debt.getPaidAmount(), debt.getTotalAmount()));
                    debtRepository.save(debt);
                });
                default -> { /* SUBSCRIPTION / BANK marks bumped nothing to reverse */ }
            }
        }
        markPaidRepository.delete(mark);
    }

    /** Where a repayment total leaves a loan / debt after money is added or backed out. */
    private static RecordStatus repaymentStatus(BigDecimal paid, BigDecimal total) {
        if (paid.signum() <= 0) return RecordStatus.PENDING;
        return paid.compareTo(total) >= 0 ? RecordStatus.PAID : RecordStatus.PARTIALLY_PAID;
    }

    private static BigDecimal clampZero(BigDecimal v) {
        return v == null || v.signum() < 0 ? BigDecimal.ZERO : v;
    }

    private static Long requireRef(Long refId, String what) {
        if (refId == null) throw new IllegalArgumentException("refId (the " + what + " id) is required.");
        return refId;
    }

    /** A mark carries no FX — its amount must already be in the target's own currency. */
    private static void assertMarkCurrency(Currency reqCurrency, Currency entityCurrency, String what) {
        if (reqCurrency != entityCurrency) {
            throw new IllegalArgumentException(
                    "Amount currency (" + reqCurrency + ") does not match the " + what + " currency (" + entityCurrency + ").");
        }
    }

    private static YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) return YearMonth.now();
        try {
            return YearMonth.parse(month.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("month must be in YYYY-MM format (got: " + month + ")");
        }
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> getLoanTakenRepayments(Long id) {
        if (!loanTakenRepository.existsById(id)) throw new ResourceNotFoundException("LoanTaken", id);
        return transactionRepository.findByRepaidLoanTakenIdOrderByTransactionDateDesc(id).stream()
                .map(TransactionResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> getLoanGivenRepayments(Long id) {
        if (!loanGivenRepository.existsById(id)) throw new ResourceNotFoundException("LoanGiven", id);
        return transactionRepository.findByRepaidLoanGivenIdOrderByTransactionDateDesc(id).stream()
                .map(TransactionResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> getDebtRepayments(Long id) {
        if (!debtRepository.existsById(id)) throw new ResourceNotFoundException("Debt", id);
        return transactionRepository.findByRepaidDebtIdOrderByTransactionDateDesc(id).stream()
                .map(TransactionResponse::from).toList();
    }

    /** Build (but do not save) a repayment transaction, with card + cash split already filled in. */
    private Transaction newRepaymentTx(TransactionType type, TransactionSubType subType,
                                       RepaymentRequest req, String description,
                                       uz.tracker.trackerproject.enums.Currency currency) {
        Transaction tx = new Transaction();
        tx.setType(type);
        tx.setSubType(subType);
        tx.setAmount(req.getAmount());
        tx.setCurrency(currency);
        tx.setDescription(description);
        tx.setTransactionDate(req.getPaymentDate());
        attachCardAndCategory(tx, req);
        // No card → fully cash; otherwise card-side payment (no split — UI gives the user
        // a single payment-source choice for repayments, not Card+Cash split).
        // Only guard balance for EXPENSE outflows; an INCOME mark-returned deposits INTO the
        // card, so it must not require the destination card to be pre-funded (mirrors
        // TransactionService.checkCardBalance, which skips the check when type != EXPENSE).
        if (type == TransactionType.EXPENSE && tx.getCard() != null) cardService.assertSufficientBalance(tx.getCard(), req.getAmount());
        tx.setCashAmount(tx.getCard() == null ? req.getAmount() : BigDecimal.ZERO);
        return tx;
    }

    /**
     * Create a bare EXPENSE Transaction tied to a guidance bucket (donation /
     * investment / emergency). Used by the direct CRUD endpoints to mirror the
     * record into the Transaction stream so it shows up in the global transactions
     * list. NO {@code originatingTransactionId} loop — caller passes the new tx's
     * id into the bucket entity itself.
     *
     * @param savingsGoalTarget whether an INVESTMENT row funds a savings goal; ignored for every
     *        other sub-type, whose bucket follows from the sub-type alone.
     */
    private Transaction createBucketTransaction(
            TransactionSubType subType, boolean savingsGoalTarget,
            BigDecimal amount, uz.tracker.trackerproject.enums.Currency currency,
            java.time.LocalDate date, Long cardId, Long categoryId, String description) {
        Transaction tx = newWalletTransaction(TransactionType.EXPENSE, subType, amount, currency, date,
                cardId, categoryId, description);
        // Record the bucket now, while the holding's flags are the ones the user is actually
        // funding. Deriving it later from a flag that stays editable for the life of the holding
        // is what let one checkbox move money out of a closed month (see AllocationBucket).
        tx.setAllocationBucket(AllocationBucket.forSubType(subType, savingsGoalTarget));
        return transactionRepository.save(tx);
    }

    /**
     * The wallet transaction a borrowed or lent loan originates from when it is recorded with
     * {@code moveMoney}: what TransactionService books for a LOAN_RECEIVED / LOAN_GIVEN, so deleting or
     * editing either side then behaves exactly as for a loan recorded through a transaction.
     */
    private Transaction mirrorTransaction(TransactionType type, TransactionSubType subType, BigDecimal amount,
                                          Currency currency, LocalDate date, Long cardId, Long categoryId,
                                          String description) {
        return transactionRepository.save(newWalletTransaction(type, subType, amount, currency, date,
                cardId, categoryId, description));
    }

    /**
     * A new transaction moving {@code amount} into (INCOME) or out of (EXPENSE) a wallet — the card, or
     * cash when {@code cardId} is null — behind the same gates as every write: a stable income, an open
     * month, a card in the payment's currency, and, for money going out of a card, enough on it.
     */
    private Transaction newWalletTransaction(TransactionType type, TransactionSubType subType, BigDecimal amount,
                                             Currency currency, LocalDate date, Long cardId, Long categoryId,
                                             String description) {
        settingsService.assertStableIncomeSet();
        monthCloseService.assertMonthOpen(date);
        Transaction tx = new Transaction();
        tx.setType(type);
        tx.setSubType(subType);
        tx.setAmount(amount);
        tx.setCurrency(currency);
        tx.setDescription(description);
        tx.setTransactionDate(date);
        if (cardId != null) {
            Card card = cardRepository.findById(cardId)
                    .orElseThrow(() -> new ResourceNotFoundException("Card", cardId));
            if (card.getCurrency() != currency) {
                throw new IllegalArgumentException(
                        "Card currency (" + card.getCurrency() + ") does not match payment currency (" + currency + ")");
            }
            if (type == TransactionType.EXPENSE) cardService.assertSufficientBalance(card, amount);
            tx.setCard(card);
            tx.setCashAmount(BigDecimal.ZERO);
        } else {
            tx.setCard(null);
            tx.setCashAmount(amount);
        }
        // Category: explicit override wins; otherwise auto-pick the single Category whose
        // applicableSubType matches (same rule as repayments) so Overview pays aren't uncategorised.
        if (categoryId != null) {
            categoryRepository.findById(categoryId).ifPresent(tx::setCategory);
        } else {
            List<Category> matches = categoryRepository.findByApplicableSubTypeAndParentIsNull(subType);
            if (matches.size() == 1) tx.setCategory(matches.get(0));
        }
        return tx;
    }

    /**
     * Keep the transaction a loan originates from in step with an edit of the loan — the reverse of
     * TransactionService, which moves the loan when its transaction is edited. The amount and date
     * follow only when {@code amountFollows} (money lent again on top of a loan makes its total more
     * than that one transaction); either change is gated on the old and the new month being open,
     * and more money out of a card on there being enough on it. The transaction's description is its
     * own (it may have been written on the transaction), so it is left alone.
     */
    private void syncMirror(Long txId, BigDecimal amount, LocalDate date, boolean amountFollows) {
        if (txId == null) return;
        Transaction tx = transactionRepository.findById(txId).orElse(null);
        if (tx == null) return;
        boolean moved = amountFollows && (tx.getAmount() == null || tx.getAmount().compareTo(amount) != 0
                || !java.util.Objects.equals(tx.getTransactionDate(), date));
        if (!moved) return;
        monthCloseService.assertMonthOpen(tx.getTransactionDate());
        monthCloseService.assertMonthOpen(date);
        BigDecimal more = amount.subtract(tx.getAmount() == null ? BigDecimal.ZERO : tx.getAmount());
        if (tx.getType() == TransactionType.EXPENSE && tx.getCard() != null && more.signum() > 0) {
            cardService.assertSufficientBalance(tx.getCard(), more);
        }
        tx.setAmount(amount);
        tx.setTransactionDate(date);
        if (tx.getCard() == null) tx.setCashAmount(amount);
        transactionRepository.save(tx);
    }

    /**
     * Deleting a loan takes the transaction it originates from with it — the money goes back — as
     * deleting that transaction already takes the loan (TransactionService.reverseAutoCreated).
     */
    private void deleteMirror(Long txId) {
        if (txId == null) return;
        transactionRepository.findById(txId).ifPresent(tx -> {
            monthCloseService.assertMonthOpen(tx.getTransactionDate());
            transactionRepository.delete(tx);
        });
    }

    private void attachCardAndCategory(Transaction tx, RepaymentRequest req) {
        if (req.getCardId() != null) {
            // A supplied cardId means a card-sourced payment; an unresolvable id must fail
            // fast (404) rather than silently downgrade the payment to cash and corrupt the
            // cash/card balance aggregates. Matches resolveCardForPayment / createBucketTransaction.
            Card card = cardRepository.findById(req.getCardId())
                    .orElseThrow(() -> new ResourceNotFoundException("Card", req.getCardId()));
            if (card.getCurrency() != tx.getCurrency()) {
                throw new IllegalArgumentException(
                        "Card currency (" + card.getCurrency() + ") does not match payment currency (" + tx.getCurrency() + ")");
            }
            tx.setCard(card);
        }
        if (req.getCategoryId() != null) {
            categoryRepository.findById(req.getCategoryId()).ifPresent(tx::setCategory);
        } else if (tx.getSubType() != null) {
            // Mirror the modal's "auto-select-single" behaviour: if exactly one Category
            // declares applicableSubType = tx.subType, use it. Stays null on 0 or >1 hits.
            List<Category> matches = categoryRepository.findByApplicableSubTypeAndParentIsNull(tx.getSubType());
            if (matches.size() == 1) tx.setCategory(matches.get(0));
        }
    }

    /**
     * Compute the per-month tier contribution for a personal loan / debt. Stored on the
     * entity at creation so subsequent payments don't shift the user's tier mid-month.
     *
     * Returns 0 when there's nothing left to pay. When dueDate is null or in the past,
     * the entire remaining balance lands on a single month (divisor = 1) — the user can
     * resolve this by setting a future dueDate when editing the loan.
     */
    // ---- People and repayment types ----

    /**
     * A borrowed loan's repayment type and plan, kept together: the type sent, else the one the plan
     * implies (a plan means MONTHLY — how the bot, which never sends a type, keeps working). A
     * MONTHLY loan must have a plan; an ASAP loan keeps none.
     */
    static void applyRepayment(LoanTaken l, RepaymentType requested, BigDecimal plan) {
        boolean hasPlan = plan != null && plan.signum() > 0;
        RepaymentType type = requested != null ? requested : hasPlan ? RepaymentType.MONTHLY : RepaymentType.ASAP;
        if (type == RepaymentType.MONTHLY && !hasPlan) {
            throw new IllegalArgumentException("A MONTHLY loan needs its monthly payment (plannedMonthlyPayment).");
        }
        l.setRepaymentType(type);
        l.setPlannedMonthlyPayment(type == RepaymentType.MONTHLY ? plan : null);
    }

    /**
     * Point a borrowed loan at its lender: the person {@code lenderId} — whose name the loan then
     * takes — else the one its own name belongs to, added when new (the bot sends names only).
     */
    private void linkLender(LoanTaken l, Long lenderId) {
        Counterparty c = person(lenderId, l.getLenderName(), CounterpartyKind.LENDER, "Lender name");
        if (c == null) return;
        l.setLenderId(c.getId());
        if (lenderId != null) l.setLenderName(c.getName());
    }

    /** The same for a debt's creditor, on the lenders list. */
    private void linkCreditor(Debt d, Long lenderId) {
        Counterparty c = person(lenderId, d.getCreditorName(), CounterpartyKind.LENDER, "Creditor name");
        if (c == null) return;
        d.setLenderId(c.getId());
        if (lenderId != null) d.setCreditorName(c.getName());
    }

    /** The same for the borrower of money lent, on the borrowers list. */
    private void linkBorrower(LoanGiven l, Long borrowerId) {
        Counterparty c = person(borrowerId, l.getDebtorName(), CounterpartyKind.BORROWER, "Borrower name");
        if (c == null) return;
        l.setBorrowerId(c.getId());
        if (borrowerId != null) l.setDebtorName(c.getName());
    }

    /**
     * Re-link a borrowed loan whose name was just changed from its transaction (TransactionService
     * patches the name in place). A blank name keeps the link it had.
     */
    public void relinkLender(LoanTaken l) {
        if (Counterparty.keyOf(l.getLenderName()) != null) linkLender(l, null);
    }

    /** The same for money lent whose borrower's name changed from its transaction. */
    public void relinkBorrower(LoanGiven l) {
        if (Counterparty.keyOf(l.getDebtorName()) != null) linkBorrower(l, null);
    }

    private Counterparty person(Long id, String name, CounterpartyKind kind, String what) {
        if (id != null) return counterpartyService.require(id, kind);
        if (Counterparty.keyOf(name) == null) throw new IllegalArgumentException(what + " is required.");
        return counterpartyService.findOrCreate(name, kind);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    /**
     * Normalise the payment-start to the first day of its month. When the request omits
     * it, default to the month AFTER the borrowed date (or after today if borrowedDate is
     * null) — i.e. "repayments start next month" — so borrowing today doesn't move the
     * current month's tier.
     */
    private LocalDate resolvePaymentStart(LocalDate requested, LocalDate borrowedDate) {
        if (requested != null) return requested.withDayOfMonth(1);
        LocalDate base = borrowedDate != null ? borrowedDate : LocalDate.now();
        return base.plusMonths(1).withDayOfMonth(1);
    }

    private BigDecimal deriveMonthlyContribution(
            BigDecimal totalAmount, BigDecimal paidAmount, java.time.LocalDate dueDate) {
        BigDecimal remaining = (totalAmount == null ? BigDecimal.ZERO : totalAmount)
                .subtract(paidAmount == null ? BigDecimal.ZERO : paidAmount);
        if (remaining.signum() <= 0) return BigDecimal.ZERO;
        long months = 1L;
        if (dueDate != null) {
            long between = java.time.temporal.ChronoUnit.MONTHS.between(java.time.LocalDate.now(), dueDate);
            months = Math.max(1L, between);
        }
        return remaining.divide(BigDecimal.valueOf(months), java.math.MathContext.DECIMAL64);
    }
}
