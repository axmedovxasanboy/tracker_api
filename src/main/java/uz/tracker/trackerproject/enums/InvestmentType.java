package uz.tracker.trackerproject.enums;

public enum InvestmentType {
    // STOCKS and CRYPTO were removed — they're tracked in dedicated apps now. Legacy rows
    // carrying those values are migrated to OTHER on boot (see DataSeeder). Stocks are tracked
    // via the STOCK_PURCHASE transaction sub-type + the "Stocks" category instead.
    REAL_ESTATE,
    BONDS,
    MUTUAL_FUND,
    GOLD,
    OTHER
}
