package uz.tracker.trackerproject.controller;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uz.tracker.trackerproject.dto.response.AnalyticsBreakdownResponse;
import uz.tracker.trackerproject.exception.GlobalExceptionHandler;
import uz.tracker.trackerproject.service.AnalyticsBreakdownService;
import uz.tracker.trackerproject.service.BreakdownFixtures;
import uz.tracker.trackerproject.service.LevelService;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/v1/analytics/breakdown: what is parsed, what is left to default, what is a 400, and the JSON's names. */
class AnalyticsBreakdownControllerTest {

    private final AnalyticsBreakdownService service = mock(AnalyticsBreakdownService.class);
    private final LevelService levels = mock(LevelService.class);
    private final AnalyticsBreakdownController controller = new AnalyticsBreakdownController(service, levels);

    @Test
    void theMonthsAndTheDayArePassedOnParsed_afterTheLevelIsEvaluatedForThatDay() {
        AnalyticsBreakdownResponse body = AnalyticsBreakdownResponse.builder().build();
        when(service.breakdown(any(), any(), any())).thenReturn(body);

        assertThat(controller.breakdown("2025-11", " 2026-10 ", "2026-10-02").getBody()).isSameAs(body);

        InOrder order = inOrder(levels, service);
        order.verify(levels).refreshQuietly(LocalDate.of(2026, 10, 2));
        order.verify(service).breakdown(YearMonth.of(2025, 11), YearMonth.of(2026, 10), LocalDate.of(2026, 10, 2));
    }

    @Test
    void aRangeLeftOutIsLeftForTheServiceToDefault_andTheDayIsToday() {
        controller.breakdown(null, "", null);

        verify(service).breakdown(isNull(), isNull(), any(LocalDate.class));
    }

    /** IllegalArgumentException is what GlobalExceptionHandler turns into the uniform 400. */
    @Test
    void whatCannotBeParsedIsRefusedBeforeAnythingIsReadOrEvaluated() {
        assertThatThrownBy(() -> controller.breakdown("2026-13", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("from must be YYYY-MM, got: 2026-13");
        assertThatThrownBy(() -> controller.breakdown(null, "October", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("to must be YYYY-MM, got: October");
        assertThatThrownBy(() -> controller.breakdown(null, null, "02.10.2026"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("date must be YYYY-MM-DD, got: 02.10.2026");
        verifyNoInteractions(service, levels);
    }

    // ── Over HTTP, on the owner's live data ──

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new AnalyticsBreakdownController(BreakdownFixtures.ownerLive(), null))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void everyBadRangeIsA400() throws Exception {
        String url = "/api/v1/analytics/breakdown";
        mvc.perform(get(url).param("from", "2026-13").param("date", "2026-10-02")).andExpect(status().isBadRequest());
        mvc.perform(get(url).param("date", "2 Oct")).andExpect(status().isBadRequest());
        mvc.perform(get(url).param("to", "2026-11").param("date", "2026-10-02"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("to can't be after the month of date (2026-10), got: 2026-11"));
        mvc.perform(get(url).param("from", "2026-10").param("to", "2026-09").param("date", "2026-10-02"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("from can't be after to, got: 2026-10 and 2026-09"));
        mvc.perform(get(url).param("from", "2024-10").param("to", "2026-10").param("date", "2026-10-02"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The range can't be longer than 24 months, got: 25"));
        mvc.perform(get(url).param("from", "2024-11").param("to", "2026-10").param("date", "2026-10-02"))
                .andExpect(status().isOk());
    }

    @Test
    void theJsonUsesTheSpecsNames() throws Exception {
        mvc.perform(get("/api/v1/analytics/breakdown").param("date", "2026-10-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-10"))
                .andExpect(jsonPath("$.history.start").value("2026-09"))
                .andExpect(jsonPath("$.months[0].tracked").value(true))
                .andExpect(jsonPath("$.months[0].complete").value(false))
                .andExpect(jsonPath("$.months[0].daysInMonth").value(31))
                .andExpect(jsonPath("$.expected.flow.walletChange").value(nullValue()))
                .andExpect(jsonPath("$.expected.flow.payForOtherMonths").value(nullValue()))
                .andExpect(jsonPath("$.expected.paidOffLoans").value(1955000))
                .andExpect(jsonPath("$.setAside[2].key").value("group:GOALS"))
                .andExpect(jsonPath("$.setAside[2].isNew").value(true))
                .andExpect(jsonPath("$.out[?(@.key == 'loans')].children[0].key").value("loan:BANK:1"))
                .andExpect(jsonPath("$.out[?(@.key == 'loans')].children[1].closed").value(true))
                .andExpect(jsonPath("$.out[0].history.categoryId").value(9))
                .andExpect(jsonPath("$.out[0].history.investmentId").doesNotExist())
                .andExpect(jsonPath("$.goals[2].months[0].reachedEnd").value(1000000));
    }
}
