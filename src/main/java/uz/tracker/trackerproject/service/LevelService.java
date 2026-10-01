package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.request.LevelRulesRequest;
import uz.tracker.trackerproject.dto.response.LevelNoticeResponse;
import uz.tracker.trackerproject.dto.response.LevelRoad;
import uz.tracker.trackerproject.dto.response.LevelsResponse;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse;
import uz.tracker.trackerproject.entity.LevelChange;
import uz.tracker.trackerproject.entity.LevelRuleVersion;
import uz.tracker.trackerproject.entity.SituationPercents;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.exception.ResourceNotFoundException;
import uz.tracker.trackerproject.repository.LevelChangeRepository;
import uz.tracker.trackerproject.repository.LevelRuleVersionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Levels and their savings rules (LEVELS-ALLOCATION-SPEC §3): what {@code /api/v1/levels} shows, a
 * rules change from a month on, removing one, the Level 5 notice, and the boot seeding. The engine's
 * reading of all this lives in {@link OverviewService} ({@code ruleBook}, {@code levelOf},
 * {@code standing}); this service writes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LevelService {

    public static final String WEB = "WEB";
    public static final String BOT = "BOT";
    /** How far ahead a rules change may be recorded — as for the monthly income. */
    static final int MAX_MONTHS_AHEAD = 12;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final OverviewService overviewService;
    private final LevelRuleVersionRepository versions;
    private final LevelChangeRepository changes;
    private final LevelChangeRecorder recorder;

    // ── GET /levels ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public LevelsResponse levels(LocalDate date) {
        YearMonth current = YearMonth.from(date);
        BigDecimal income = overviewService.stableIncomeFor(current);
        boolean hasIncome = income != null && income.signum() > 0;
        Integer level = hasIncome ? overviewService.levelFor(current) : null;
        Integer base = hasIncome ? overviewService.baseLevelFor(current) : null;
        SavingsRules.Book book = overviewService.ruleBook();

        String situation = null;
        LevelsResponse.Percents percents = null;
        if (hasIncome) {
            OverviewTierResponse tier = overviewService.getTierIgnoringSubscriptions(current, Currency.UZS, date);
            situation = SavingsRules.situationOf(tier.getAllocation() == null ? null : tier.getAllocation().getScenarioKey());
            if (situation != null && level != null) percents = percents(book.version(level, current).percents(situation));
        }
        YearMonth since = level != null && level == SavingsRules.TOP_LEVEL
                ? OverviewService.level5Since(current, overviewService.levelChanges()) : null;

        List<LevelsResponse.Level> levels = new ArrayList<>();
        for (int l = 1; l <= SavingsRules.TOP_LEVEL; l++) {
            List<LevelsResponse.Version> vs = new ArrayList<>();
            for (SavingsRules.Version v : book.versions(l)) vs.add(version(v));
            levels.add(LevelsResponse.Level.builder()
                    .level(l)
                    .leftFrom(OverviewService.levelIncomeLow(l))
                    .leftTo(OverviewService.levelIncomeHigh(l))
                    .inForce(book.version(l, current).from().toString())
                    .versions(vs)
                    .build());
        }
        return LevelsResponse.builder()
                .month(current.toString())
                .level(level)
                .baseLevel(base)
                .leftAfterBills(hasIncome ? income.subtract(overviewService.activeBillsUzs()) : null)
                .situation(situation)
                .percents(percents)
                .level5Since(since == null ? null : since.toString())
                .road(overviewService.road(current, date, level, base))
                .firstMonth(overviewService.firstLevelMonth(current).toString())
                .levels(levels)
                .build();
    }

    private static LevelsResponse.Version version(SavingsRules.Version v) {
        Map<String, LevelsResponse.Percents> rules = new LinkedHashMap<>();
        for (String s : SavingsRules.SITUATIONS) rules.put(s, percents(v.percents(s)));
        return LevelsResponse.Version.builder().from(v.from().toString()).cutoff(v.cutoff()).rules(rules).build();
    }

    private static LevelsResponse.Percents percents(SavingsRules.Percents p) {
        return LevelsResponse.Percents.builder().donation(p.donation()).emergency(p.emergency())
                .investments(p.investments()).build();
    }

    // ── PUT /levels/{level}/rules ─────────────────────────────────────────────

    /**
     * One change to a level's rules from {@code req.from} on: the version at that month takes it — a
     * new one starts as a copy of the version in force then. Later versions are kept and do not
     * inherit it. Months before it keep their percentages.
     */
    @Transactional
    public LevelsResponse saveRules(String levelParam, LevelRulesRequest req, LocalDate date) {
        int level = level(levelParam);
        if (req == null) throw new IllegalArgumentException("A body is required: from, cutoff, rules.");
        YearMonth current = YearMonth.from(date);
        YearMonth from = month("from", req.getFrom());
        if (from == null) from = current;
        YearMonth first = overviewService.firstLevelMonth(current);
        if (from.isBefore(first)) {
            throw new IllegalArgumentException("A rules change can apply from " + first + " at the earliest (the first month tracked), not " + from + ".");
        }
        YearMonth last = current.plusMonths(MAX_MONTHS_AHEAD);
        if (from.isAfter(last)) {
            throw new IllegalArgumentException("A rules change can apply up to " + last + " (12 months ahead), not " + from + ".");
        }
        if (req.getCutoff() != null && req.getCutoff().signum() < 0) {
            throw new IllegalArgumentException("cutoff can't be negative, got: " + req.getCutoff().toPlainString());
        }
        Map<String, LevelRulesRequest.Percents> changed = req.getRules() == null ? Map.of() : req.getRules();
        for (Map.Entry<String, LevelRulesRequest.Percents> e : changed.entrySet()) {
            if (!SavingsRules.SITUATIONS.contains(e.getKey())) {
                throw new IllegalArgumentException("Unknown situation: " + e.getKey() + ". Known: " + SavingsRules.SITUATIONS + ".");
            }
            if (e.getValue() == null) throw new IllegalArgumentException(e.getKey() + ": the percentages are missing.");
            checkPercent(e.getKey(), "donation", e.getValue().getDonation());
            checkPercent(e.getKey(), "emergency", e.getValue().getEmergency());
            checkPercent(e.getKey(), "investments", e.getValue().getInvestments());
        }

        // Every level must have its first version stored before another is added — or the new one
        // would become the first, and the months before it would read it.
        seedVersions();
        SavingsRules.Version inForce = overviewService.ruleBook().version(level, from);
        LevelRuleVersion row = versions.findByLevelAndFromMonth(level, from.atDay(1)).orElse(null);
        if (row == null) {
            row = new LevelRuleVersion();
            row.setLevel(level);
            row.setFromMonth(from.atDay(1));
            row.setCutoff(inForce.cutoff());
            Map<String, SituationPercents> copy = new LinkedHashMap<>();
            for (String s : SavingsRules.SITUATIONS) {
                SavingsRules.Percents p = inForce.percents(s);
                copy.put(s, new SituationPercents(p.donation(), p.emergency(), p.investments()));
            }
            row.setRules(copy);
        }
        if (req.getCutoff() != null) row.setCutoff(req.getCutoff());
        for (Map.Entry<String, LevelRulesRequest.Percents> e : changed.entrySet()) {
            SituationPercents now = row.getRules().get(e.getKey());
            SavingsRules.Percents fallback = inForce.percents(e.getKey());
            BigDecimal d = pick(e.getValue().getDonation(), now == null ? fallback.donation() : now.getDonation());
            BigDecimal em = pick(e.getValue().getEmergency(), now == null ? fallback.emergency() : now.getEmergency());
            BigDecimal inv = pick(e.getValue().getInvestments(), now == null ? fallback.investments() : now.getInvestments());
            if (d.add(em).add(inv).compareTo(HUNDRED) > 0) {
                throw new IllegalArgumentException(e.getKey() + ": the three together can be at most 100%, got "
                        + d.add(em).add(inv).stripTrailingZeros().toPlainString() + "%.");
            }
            row.getRules().put(e.getKey(), new SituationPercents(d, em, inv));
        }
        versions.save(row);
        return levels(date);
    }

    // ── DELETE /levels/{level}/rules/{month} ──────────────────────────────────

    /** Remove one version: the months it covered fall back to the version before it. Never the only one. */
    @Transactional
    public LevelsResponse deleteRules(String levelParam, String monthParam, LocalDate date) {
        int level = level(levelParam);
        YearMonth month = month("month", monthParam);
        if (month == null) throw new IllegalArgumentException("month must be YYYY-MM, got: " + monthParam);
        seedVersions();
        LevelRuleVersion row = versions.findByLevelAndFromMonth(level, month.atDay(1))
                .orElseThrow(() -> new ResourceNotFoundException("Level " + level + " has no rules version from " + month + "."));
        if (versions.countByLevel(level) <= 1) {
            throw new IllegalArgumentException("This is Level " + level + "'s only rules version — change it instead.");
        }
        // The months before a level's second version read its first; removing the first would hand
        // them the next one's numbers and move their targets and carry.
        List<LevelRuleVersion> all = versions.findByLevelOrderByFromMonthAsc(level);
        if (!all.isEmpty() && all.getFirst().getFromMonth().equals(row.getFromMonth())) {
            throw new IllegalArgumentException("This is Level " + level + "'s first rules version — the months before "
                    + "the next change read it. Change it instead.");
        }
        versions.delete(row);
        versions.flush();
        return levels(date);
    }

    // ── The notice ────────────────────────────────────────────────────────────

    /** The oldest start or end of Level 5 {@code client} has not seen; empty when there is none. */
    @Transactional(readOnly = true)
    public Optional<LevelNoticeResponse> notice(String clientParam) {
        String client = client(clientParam);
        for (LevelChange c : changes.findAllByOrderByMonthAscIdAsc()) {
            boolean seen = WEB.equals(client) ? c.getSeenWebAt() != null : c.getSeenBotAt() != null;
            if (!seen) return Optional.of(notice(c));
        }
        return Optional.empty();
    }

    private LevelNoticeResponse notice(LevelChange c) {
        YearMonth from = YearMonth.from(c.getMonth());
        LocalDate today = LocalDate.now();
        List<LevelRoad.MonthPay> months = new ArrayList<>();
        for (int i = OverviewService.LEVEL5_MONTHS_NEEDED; i >= 1; i--) {
            YearMonth m = from.minusMonths(i);
            months.add(LevelRoad.MonthPay.builder().month(m.toString()).pay(overviewService.payFor(m, today)).build());
        }
        OverviewTierResponse tier = overviewService.getTierIgnoringSubscriptions(from, Currency.UZS, from.atDay(1));
        String situation = SavingsRules.situationOf(tier.getAllocation() == null ? null : tier.getAllocation().getScenarioKey());
        SavingsRules.Percents p = situation == null || c.getLevel() == null ? null
                : overviewService.ruleBook().version(c.getLevel(), from).percents(situation);
        BigDecimal income = overviewService.stableIncomeFor(from);
        LevelsResponse.Percents amounts = p == null || income == null ? null : LevelsResponse.Percents.builder()
                .donation(share(income, p.donation())).emergency(share(income, p.emergency()))
                .investments(share(income, p.investments())).build();
        return LevelNoticeResponse.builder()
                .id(c.getId()).kind(c.getKind()).level(c.getLevel()).previousLevel(c.getPreviousLevel())
                .from(from.toString()).months(months).situation(situation)
                .percents(p == null ? null : percents(p)).amounts(amounts)
                .build();
    }

    /** Mark one change seen by {@code client} — its own copy only. */
    @Transactional
    public void seen(Long id, String clientParam) {
        String client = client(clientParam);
        LevelChange c = changes.findById(id).orElseThrow(() -> new ResourceNotFoundException("LevelChange", id));
        if (WEB.equals(client) && c.getSeenWebAt() == null) c.setSeenWebAt(LocalDateTime.now());
        if (BOT.equals(client) && c.getSeenBotAt() == null) c.setSeenBotAt(LocalDateTime.now());
        changes.save(c);
    }

    // ── The Level 5 run, evaluated ────────────────────────────────────────────

    /**
     * Record a start or an end of Level 5 that is due today — before a read that shows the level.
     * Only when {@code date} really is today (give or take the day the owner's clock may be ahead of
     * the server's): a page asked for another day must not write the record. In its own transaction
     * (LevelChangeRecorder); a failure is logged and never fails the read.
     */
    public void refreshQuietly(LocalDate date) {
        if (date == null || Math.abs(ChronoUnit.DAYS.between(LocalDate.now(), date)) > 1) return;
        try {
            recorder.refresh(date);
        } catch (RuntimeException e) {
            log.warn("Could not evaluate the Level 5 run for {}: {}", date, e.toString());
        }
    }

    // ── Boot seeding ──────────────────────────────────────────────────────────

    /**
     * Each level with no stored version gets its first one, from the first month (the tracking start,
     * else the earliest transaction's month, else this month), with the numbers the engine has been
     * reading — Level 1's built-in table and its split line, Levels 2–5 the old store's rules or Level
     * 1's numbers (§1.4, §6). Idempotent: a level with any version is left alone.
     *
     * @return how many versions it wrote
     */
    @Transactional
    public int seedVersions() {
        YearMonth first = null;
        int written = 0;
        for (int level = 1; level <= SavingsRules.TOP_LEVEL; level++) {
            if (versions.countByLevel(level) > 0) continue;
            if (first == null) first = overviewService.firstLevelMonth(YearMonth.now());
            SavingsRules.Version v = overviewService.seedVersion(level, first);
            LevelRuleVersion row = new LevelRuleVersion();
            row.setLevel(level);
            row.setFromMonth(v.from().atDay(1));
            row.setCutoff(v.cutoff());
            Map<String, SituationPercents> rules = new LinkedHashMap<>();
            for (String s : SavingsRules.SITUATIONS) {
                SavingsRules.Percents p = v.percents(s);
                rules.put(s, new SituationPercents(p.donation(), p.emergency(), p.investments()));
            }
            row.setRules(rules);
            versions.save(row);
            written++;
        }
        return written;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int level(String level) {
        try {
            int l = Integer.parseInt(level.trim());
            if (l >= 1 && l <= SavingsRules.TOP_LEVEL) return l;
        } catch (RuntimeException ignored) {
            // falls through to the 400 below
        }
        throw new IllegalArgumentException("level must be 1 to 5, got: " + level);
    }

    private static YearMonth month(String name, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return YearMonth.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must be YYYY-MM, got: " + value);
        }
    }

    static String client(String client) {
        if (WEB.equals(client) || BOT.equals(client)) return client;
        throw new IllegalArgumentException("client must be WEB or BOT, got: " + client);
    }

    /** 0–100, at most one decimal; null (left out) is fine — it keeps the one in force. */
    private static void checkPercent(String situation, String bucket, BigDecimal v) {
        if (v == null) return;
        if (v.signum() < 0 || v.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException(situation + " " + bucket + ": a percentage must be 0 to 100, got "
                    + v.stripTrailingZeros().toPlainString() + ".");
        }
        if (v.stripTrailingZeros().scale() > 1) {
            throw new IllegalArgumentException(situation + " " + bucket + ": at most one decimal, got "
                    + v.stripTrailingZeros().toPlainString() + ".");
        }
    }

    private static BigDecimal pick(BigDecimal sent, BigDecimal kept) {
        return sent != null ? sent : kept == null ? BigDecimal.ZERO : kept;
    }

    private static BigDecimal share(BigDecimal income, BigDecimal percent) {
        return income.multiply(percent == null ? BigDecimal.ZERO : percent).divide(HUNDRED, 0, RoundingMode.HALF_UP);
    }
}
