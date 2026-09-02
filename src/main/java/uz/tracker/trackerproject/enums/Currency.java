package uz.tracker.trackerproject.enums;

/**
 * The app is UZS-only. The type is kept (every money-bearing entity still stores its
 * currency) so that adding real multi-currency support later is a matter of extending
 * this enum rather than reshaping the schema — but nothing may be stored in any other
 * currency, and there is no conversion anywhere in the codebase.
 */
public enum Currency {
    UZS
}
