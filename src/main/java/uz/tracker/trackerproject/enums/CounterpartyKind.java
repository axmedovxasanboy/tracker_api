package uz.tracker.trackerproject.enums;

/** Which list a person is on: the people the owner borrows from, or the people who borrow from them. */
public enum CounterpartyKind {
    /** Lends to the owner: linked from borrowed money (LoanTaken) and debts (Debt). */
    LENDER,
    /** Borrows from the owner: linked from money lent (LoanGiven). */
    BORROWER
}
