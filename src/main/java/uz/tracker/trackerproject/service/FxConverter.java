package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.enums.Currency;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Single source of truth for currency conversion. Uses the Settings row's FX rates
 * (UZS-anchored: 1 USD = X UZS, 1 EUR = Y UZS) with hard-coded defaults so the
 * overview/tier math doesn't blow up before the user has set rates.
 *
 * The defaults are conservative middle-of-the-road values — they exist so the
 * first-boot experience doesn't show NaN, not so the user can skip configuring rates.
 */
@Component
@RequiredArgsConstructor
public class FxConverter {

    /** Fallback rate when Settings.usdToUzs is null or non-positive. */
    public static final BigDecimal DEFAULT_USD_TO_UZS = new BigDecimal("12500");
    /** Fallback rate when Settings.eurToUzs is null or non-positive. */
    public static final BigDecimal DEFAULT_EUR_TO_UZS = new BigDecimal("13500");

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_EVEN);

    private final SettingsService settingsService;

    public BigDecimal toUzs(BigDecimal amount, Currency from) {
        if (amount == null || amount.signum() == 0) return BigDecimal.ZERO;
        return switch (from) {
            case UZS -> amount;
            case USD -> amount.multiply(usdToUzs(), MC);
            case EUR -> amount.multiply(eurToUzs(), MC);
        };
    }

    public BigDecimal fromUzs(BigDecimal uzs, Currency to) {
        if (uzs == null || uzs.signum() == 0) return BigDecimal.ZERO;
        return switch (to) {
            case UZS -> uzs;
            case USD -> uzs.divide(usdToUzs(), MC);
            case EUR -> uzs.divide(eurToUzs(), MC);
        };
    }

    /** Convert between any two currencies via UZS. */
    public BigDecimal convert(BigDecimal amount, Currency from, Currency to) {
        if (from == to) return amount == null ? BigDecimal.ZERO : amount;
        return fromUzs(toUzs(amount, from), to);
    }

    private BigDecimal usdToUzs() {
        Settings s = settingsService.getOrCreate();
        BigDecimal r = s.getUsdToUzs();
        return (r != null && r.signum() > 0) ? r : DEFAULT_USD_TO_UZS;
    }

    private BigDecimal eurToUzs() {
        Settings s = settingsService.getOrCreate();
        BigDecimal r = s.getEurToUzs();
        return (r != null && r.signum() > 0) ? r : DEFAULT_EUR_TO_UZS;
    }
}
