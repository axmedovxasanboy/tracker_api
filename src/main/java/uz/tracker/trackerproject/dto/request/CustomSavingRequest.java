package uz.tracker.trackerproject.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One of the owner's own savings, made or changed on Savings rules: {@code POST /levels/savings}
 * (name, percent, from), {@code PUT /levels/savings/{id}} (name), {@code POST /levels/savings/{id}/stop}
 * (from). What a call does not use it ignores.
 */
@Getter @Setter
public class CustomSavingRequest {
    private String name;
    /** Asked in every situation of every level from {@code from} on; each can be changed after. */
    private BigDecimal percent;
    /** 'YYYY-MM'; absent → the current month. */
    private String from;
}
