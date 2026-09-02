package uz.tracker.trackerproject.service;

import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.repository.SettingsRepository;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Nothing may be recorded until a monthly stable income is configured — every allocation
 * figure is derived from it, so writing money first produces silently wrong numbers.
 */
class StableIncomeGuardTest {

    private SettingsService serviceWithIncome(BigDecimal income) {
        SettingsRepository repo = mock(SettingsRepository.class);
        Settings s = new Settings();
        s.setMonthlyStableIncome(income);
        when(repo.findById(any())).thenReturn(java.util.Optional.of(s));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        return new SettingsService(repo);
    }

    @Test
    void unsetIncomeBlocksWritesWithAnActionableMessage() {
        assertThatThrownBy(() -> serviceWithIncome(null).assertStableIncomeSet())
                .isInstanceOf(IllegalArgumentException.class)   // → HTTP 400
                .hasMessageContaining("Set your monthly stable income in Settings");
    }

    @Test
    void zeroIncomeIsTreatedAsUnset() {
        assertThatThrownBy(() -> serviceWithIncome(BigDecimal.ZERO).assertStableIncomeSet())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPositiveIncomeAllowsWrites() {
        assertThatCode(() -> serviceWithIncome(new BigDecimal("12000000")).assertStableIncomeSet())
                .doesNotThrowAnyException();
    }
}
