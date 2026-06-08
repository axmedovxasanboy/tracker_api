package uz.tracker.trackerproject.enums;

public enum TransactionSubType {
    // Income
    REGULAR_INCOME,
    LOAN_RECEIVED,       // Borrowed money → creates LoanTaken
    LOAN_RETURNED_TO_ME, // Someone paid back a loan I gave → updates LoanGiven

    // Expense
    REGULAR_EXPENSE,
    LOAN_GIVEN,          // Lent money to someone → creates LoanGiven
    LOAN_REPAYMENT,      // Paying back what I owe → updates Debt / LoanTaken
    BANK_LOAN_PAYMENT,   // Monthly bank loan instalment
    INVESTMENT,          // Invested money → creates Investment
    STOCK_PURCHASE,      // Money moved to stocks (tracked elsewhere) → funds the Stocks bucket
    DONATION,            // Donated money → creates Donation
    EMERGENCY_CONTRIBUTION, // Money set aside for emergency fund → creates Emergency
    EVERYDAY_SPENDING,   // Month-end reconciliation plug: the untracked everyday spend that
                         // brings a wallet's computed balance down to the entered real balance
                         // (booked as INCOME when the entered balance is HIGHER — a surplus)

    // Internal card transfers
    TRANSFER_OUT,        // Money leaving a card (paired with TRANSFER_IN on destination)
    TRANSFER_IN,         // Money arriving on a card (paired with TRANSFER_OUT on source)

    // Currency / wallet exchanges. Like TRANSFER_* but allowed across currencies (and across
    // cash ↔ card), with potentially different from/to amounts because of the FX rate.
    EXCHANGE_OUT,        // Source side of an exchange (cash or card)
    EXCHANGE_IN          // Destination side of an exchange (cash or card)
}
