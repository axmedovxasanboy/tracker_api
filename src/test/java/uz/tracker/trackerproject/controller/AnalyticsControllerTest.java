package uz.tracker.trackerproject.controller;

import org.junit.jupiter.api.Test;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse;
import uz.tracker.trackerproject.service.AnalyticsService;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The query string of GET /api/v1/analytics: what is parsed, what is left to default, what is a 400. */
class AnalyticsControllerTest {

    private final AnalyticsService service = mock(AnalyticsService.class);
    private final AnalyticsController controller = new AnalyticsController(service);

    @Test
    void theMonthsAndTheDayArePassedOnParsed() {
        AnalyticsResponse body = AnalyticsResponse.builder().build();
        when(service.analytics(any(), any(), any())).thenReturn(body);

        assertThat(controller.analytics("2025-10", " 2026-09 ", "2026-09-30").getBody()).isSameAs(body);

        verify(service).analytics(YearMonth.of(2025, 10), YearMonth.of(2026, 9), LocalDate.of(2026, 9, 30));
    }

    @Test
    void aRangeLeftOutIsLeftForTheServiceToDefault_andTheDayIsToday() {
        controller.analytics(null, "", null);

        verify(service).analytics(isNull(), isNull(), any(LocalDate.class));
    }

    /** IllegalArgumentException is what GlobalExceptionHandler turns into the uniform 400. */
    @Test
    void whatCannotBeParsedIsRefusedBeforeAnythingIsRead() {
        assertThatThrownBy(() -> controller.analytics("2026-13", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("from must be YYYY-MM, got: 2026-13");
        assertThatThrownBy(() -> controller.analytics(null, "September", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("to must be YYYY-MM, got: September");
        assertThatThrownBy(() -> controller.analytics(null, null, "30.09.2026"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("date must be YYYY-MM-DD, got: 30.09.2026");
        verifyNoInteractions(service);
    }
}
