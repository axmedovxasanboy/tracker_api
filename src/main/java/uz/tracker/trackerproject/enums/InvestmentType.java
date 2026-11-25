package uz.tracker.trackerproject.enums;

public enum InvestmentType {
    // BUSINESS and ISLAMIC_BANK (Batch 78): the owner keeps money in a business or an Islamic bank to
    // hold it, not to grow it. The constraint on investments.type is rebuilt from this enum on boot
    // (DataSeeder.rebuildInvestmentTypeCheckConstraint).
    BUSINESS,
    ISLAMIC_BANK,
    // STOCKS and CRYPTO were removed, and BONDS and MUTUAL_FUND too (Batch 79, the owner never uses them) — they're tracked in dedicated apps now. Legacy rows
    // carrying those values are migrated to OTHER on boot (see DataSeeder). Stocks are tracked
    // via the STOCK_PURCHASE transaction sub-type + the "Stocks" category instead.
    REAL_ESTATE,
    GOLD,
    OTHER
}
