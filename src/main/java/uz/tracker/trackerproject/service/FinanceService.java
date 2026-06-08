package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.request.*;
import uz.tracker.trackerproject.dto.request.MonthlyPaymentPayRequest.Mode;
import uz.tracker.trackerproject.dto.response.*;
import uz.tracker.trackerproject.entity.*;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.RecordStatus;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.exception.ResourceNotFoundException;
import uz.tracker.trackerproject.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
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
        d.setCreditorName(req.getCreditorName());
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
        d.setCreditorName(req.getCreditorName());
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
        return LoanGivenResponse.from(saveLoanGiven(new LoanGiven(), req, null));
    }

    public LoanGiven createLoanGivenFromTransaction(LoanGivenRequest req, Long transactionId) {
        return saveLoanGiven(new LoanGiven(), req, transactionId);
    }

    private LoanGiven saveLoanGiven(LoanGiven l, LoanGivenRequest req, Long transactionId) {
        l.setDebtorName(req.getDebtorName());
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

    @Transactional
    public LoanGivenResponse updateLoanGiven(Long id, LoanGivenRequest req) {
        LoanGiven l = loanGivenRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("LoanGiven", id));
        l.setDebtorName(req.getDebtorName());
        l.setTotalAmount(req.getTotalAmount());
        if (req.getReceivedAmount() != null) l.setReceivedAmount(req.getReceivedAmount());
        l.setCurrency(req.getCurrency());
        l.setLentDate(req.getLentDate());
        l.setExpectedReturnDate(req.getExpectedReturnDate());
        if (req.getStatus() != null) l.setStatus(req.getStatus());
        l.setDescription(req.getDescription());
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
        loanGivenRepository.delete(l);
    }

    // ---- Loans Taken ----

    @Transactional(readOnly = true)
    public List<LoanTakenResponse> getAllLoansTaken() {
        return loanTakenRepository.findAllByOrderByDueDateAsc().stream().map(LoanTakenResponse::from).toList();
    }

    @Transactional
    public LoanTakenResponse createLoanTaken(LoanTakenRequest req) {
        return LoanTakenResponse.from(saveLoanTaken(new LoanTaken(), req, null));
    }

    public LoanTaken createLoanTakenFromTransaction(LoanTakenRequest req, Long transactionId) {
        return saveLoanTaken(new LoanTaken(), req, transactionId);
    }

    private LoanTaken saveLoanTaken(LoanTaken l, LoanTakenRequest req, Long transactionId) {
        l.setLenderName(req.getLenderName());
        l.setTotalAmount(req.getTotalAmount());
        l.setPaidAmount(req.getPaidAmount() != null ? req.getPaidAmount() : BigDecimal.ZERO);
        l.setCurrency(req.getCurrency());
        l.setBorrowedDate(req.getBorrowedDate());
        l.setDueDate(req.getDueDate());
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
        l.setLenderName(req.getLenderName());
        l.setTotalAmount(req.getTotalAmount());
        if (req.getPaidAmount() != null) l.setPaidAmount(req.getPaidAmount());
        l.setCurrency(req.getCurrency());
        l.setBorrowedDate(req.getBorrowedDate());
        l.setDueDate(req.getDueDate());
        if (req.getPaymentStartDate() != null) {
            l.setPaymentStartDate(req.getPaymentStartDate().withDayOfMonth(1));
        }
        if (req.getStatus() != null) l.setStatus(req.getStatus());
        l.setDescription(req.getDescription());
        if (termsChanged || l.getMonthlyPayment() == null) {
            l.setMonthlyPayment(deriveMonthlyContribution(
                    l.getTotalAmount(), l.getPaidAmount(), l.getDueDate()));
        }
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
        m.setNextDueDate(advanceDueDate(m, req.getPaymentDate()));
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

    /** Move nextDueDate one month forward, anchored to the subscription's dueDay. */
    private java.time.LocalDate advanceDueDate(MonthlyPayment m, java.time.LocalDate paymentDate) {
        java.time.LocalDate base = m.getNextDueDate() != null ? m.getNextDueDate() : paymentDate;
        java.time.LocalDate next = base.plusMonths(1);
        Integer dueDay = m.getDueDay();
        if (dueDay != null) {
            int safeDay = Math.min(dueDay, next.lengthOfMonth());
            next = next.withDayOfMonth(safeDay);
        }
        return next;
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

    @Transactional(readOnly = true)
    public List<DonationResponse> getAllDonations() {
        return donationRepository.findAllByOrderByDonationDateDesc().stream().map(DonationResponse::from).toList();
    }

    @Transactional
    public DonationResponse createDonation(DonationRequest req) {
        // Direct creation (not via the Transaction modal). Mirror to a real EXPENSE
        // Transaction so the donation appears in the Transactions list and in the
        // bucket payment history together with txs created from the other side.
        String description = (req.getDescription() != null && !req.getDescription().isBlank())
                ? req.getDescription()
                : "Donation to " + (Boolean.TRUE.equals(req.getAnonymous()) ? "Anonymous" : req.getRecipientName());
        Transaction tx = createBucketTransaction(
                TransactionSubType.DONATION,
                req.getAmount(), req.getCurrency(),
                req.getDonationDate(), req.getCardId(), req.getCategoryId(),
                description);
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
        if (!donationRepository.existsById(id)) throw new ResourceNotFoundException("Donation", id);
        donationRepository.deleteById(id);
    }

    // ---- Investments ----

    @Transactional(readOnly = true)
    public List<InvestmentResponse> getAllInvestments() {
        return investmentRepository.findAllByOrderByPurchaseDateDesc().stream().map(InvestmentResponse::from).toList();
    }

    @Transactional
    public InvestmentResponse createInvestment(InvestmentRequest req) {
        // Opening balance: an investment the user already owned before tracking. Record it for
        // net-worth / portfolio purposes only — DON'T mirror a transaction (no wallet is debited,
        // nothing shows as spent now) and it won't count toward this month's Investments bucket.
        if (Boolean.TRUE.equals(req.getOpeningBalance())) {
            return InvestmentResponse.from(saveInvestment(new Investment(), req, null));
        }
        // Direct creation. Mirror to an EXPENSE Transaction with sub-type INVESTMENT
        // so the investment also shows in the transactions list and in the bucket
        // payment history. Stock-type investments contribute to the Stocks bucket.
        String description = (req.getDescription() != null && !req.getDescription().isBlank())
                ? req.getDescription()
                : "Investment — " + req.getName();
        Transaction tx = createBucketTransaction(
                TransactionSubType.INVESTMENT,
                req.getInvestedAmount(), req.getCurrency(),
                req.getPurchaseDate(), req.getCardId(), req.getCategoryId(),
                description);
        return InvestmentResponse.from(saveInvestment(new Investment(), req, tx.getId()));
    }

    public Investment createInvestmentFromTransaction(InvestmentRequest req, Long transactionId) {
        return saveInvestment(new Investment(), req, transactionId);
    }

    private Investment saveInvestment(Investment i, InvestmentRequest req, Long transactionId) {
        applyInvestment(i, req);
        if (transactionId != null) i.setOriginatingTransactionId(transactionId);
        return investmentRepository.save(i);
    }

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
        long refs = transactionRepository.countByInvestmentId(id);
        if (refs > 0) {
            throw new IllegalArgumentException(
                    "Cannot delete: " + refs + " transaction(s) are linked to this investment.");
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
        Investment i = investmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Investment", id));
        if (req.getCurrency() != i.getCurrency()) {
            throw new IllegalArgumentException(
                    "Contribution currency (" + req.getCurrency() + ") does not match investment currency (" + i.getCurrency() + ")");
        }
        String description = (req.getDescription() != null && !req.getDescription().isBlank())
                ? req.getDescription()
                : "Contribution — " + i.getName();
        Transaction tx = createBucketTransaction(
                TransactionSubType.INVESTMENT,
                req.getAmount(), req.getCurrency(),
                req.getDate(), req.getCardId(), req.getCategoryId(),
                description);
        tx.setInvestmentId(i.getId());
        transactionRepository.save(tx);

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
        YearMonth ym = parseMarkMonth(req.getMonth());
        LocalDate monthFirst = ym.atDay(1);
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

    private static YearMonth parseMarkMonth(String month) {
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
     */
    private Transaction createBucketTransaction(
            TransactionSubType subType, BigDecimal amount, uz.tracker.trackerproject.enums.Currency currency,
            java.time.LocalDate date, Long cardId, Long categoryId, String description) {
        monthCloseService.assertMonthOpen(date);
        Transaction tx = new Transaction();
        tx.setType(TransactionType.EXPENSE);
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
            cardService.assertSufficientBalance(card, amount);
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
        return transactionRepository.save(tx);
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
