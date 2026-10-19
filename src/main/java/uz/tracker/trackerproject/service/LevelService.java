package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.request.CustomSavingRequest;
import uz.tracker.trackerproject.dto.request.LevelRulesRequest;
import uz.tracker.trackerproject.dto.response.LevelNoticeResponse;
import uz.tracker.trackerproject.dto.response.LevelRoad;
import uz.tracker.trackerproject.dto.response.LevelsResponse;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.LevelChange;
import uz.tracker.trackerproject.entity.LevelRuleVersion;
import uz.tracker.trackerproject.entity.SituationPercents;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.InvestmentType;
import uz.tracker.trackerproject.exception.ResourceNotFoundException;
import uz.tracker.trackerproject.repository.InvestmentRepository;
import uz.tracker.trackerproject.repository.LevelChangeRepository;
import uz.tracker.trackerproject.repository.LevelRuleVersionRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;

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
    /** Fixes each month's rule on its first day. Field-injected: absent where the service is built by hand. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MonthRuleRecorder monthRules;
    /** The own savings' pots, and the money that moved through them. Field-injected, like {@link #monthRules}. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private InvestmentRepository investmentRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TransactionRepository transactionRepository;

    // ── GET /levels ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public LevelsResponse levels(LocalDate date) {
        YearMonth current = YearMonth.from(date);
        BigDecimal income = overviewService.stableIncomeFor(current);
        boolean hasIncome = income != null && income.signum() > 0;
        Integer level = hasIncome ? overviewService.levelFor(current) : null;
        Integer base = hasIncome ? overviewService.baseLevelFor(current) : null;
        SavingsRules.Book book = overviewService.ruleBook();
        List<Investment> own = overviewService.customSavings();
        List<Long> ownIds = own.stream().map(Investment::getId).toList();

        String situation = null;
        LevelsResponse.Percents percents = null;
        if (hasIncome) {
            OverviewTierResponse tier = overviewService.getTierIgnoringSubscriptions(current, Currency.UZS, date);
            situation = SavingsRules.situationOf(tier.getAllocation() == null ? null : tier.getAllocation().getScenarioKey());
            if (situation != null && level != null) {
                SavingsRules.Version v = book.version(level, current);
                percents = percents(v.percents(situation), v.custom(situation), ownIds);
            }
        }
        YearMonth since = level != null && level == SavingsRules.TOP_LEVEL
                ? OverviewService.level5Since(current, overviewService.levelChanges()) : null;

        List<LevelsResponse.Level> levels = new ArrayList<>();
        for (int l = 1; l <= SavingsRules.TOP_LEVEL; l++) {
            List<LevelsResponse.Version> vs = new ArrayList<>();
            for (SavingsRules.Version v : book.versions(l)) vs.add(version(v, ownIds));
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
                .customSavings(own.stream().map(s -> LevelsResponse.CustomSaving.builder()
                        .id(s.getId()).name(s.getName()).hasMoney(hasMoney(s)).build()).toList())
                .build();
    }

    private static LevelsResponse.Version version(SavingsRules.Version v, List<Long> ownIds) {
        Map<String, LevelsResponse.Percents> rules = new LinkedHashMap<>();
        for (String s : SavingsRules.SITUATIONS) rules.put(s, percents(v.percents(s), v.custom(s), ownIds));
        return LevelsResponse.Version.builder().from(v.from().toString()).cutoff(v.cutoff()).rules(rules).build();
    }

    private static LevelsResponse.Percents percents(SavingsRules.Percents p) {
        return percents(p, Map.of(), List.of());
    }

    /** The three, and every own saving's — 0 for one this situation does not ask. */
    private static LevelsResponse.Percents percents(SavingsRules.Percents p, Map<Long, BigDecimal> custom, List<Long> ownIds) {
        Map<Long, BigDecimal> own = new LinkedHashMap<>();
        for (Long id : ownIds) own.put(id, custom.getOrDefault(id, BigDecimal.ZERO));
        return LevelsResponse.Percents.builder().donation(p.donation()).emergency(p.emergency())
                .investments(p.investments()).custom(own).build();
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
        List<Long> ownIds = overviewService.customSavings().stream().map(Investment::getId).toList();
        for (Map.Entry<String, LevelRulesRequest.Percents> e : changed.entrySet()) {
            if (!SavingsRules.SITUATIONS.contains(e.getKey())) {
                throw new IllegalArgumentException("Unknown situation: " + e.getKey() + ". Known: " + SavingsRules.SITUATIONS + ".");
            }
            if (e.getValue() == null) throw new IllegalArgumentException(e.getKey() + ": the percentages are missing.");
            checkPercent(e.getKey(), "donation", e.getValue().getDonation());
            checkPercent(e.getKey(), "emergency", e.getValue().getEmergency());
            checkPercent(e.getKey(), "investments", e.getValue().getInvestments());
            Map<Long, BigDecimal> custom = e.getValue().getCustom() == null ? Map.of() : e.getValue().getCustom();
            for (Map.Entry<Long, BigDecimal> c : custom.entrySet()) {
                if (!ownIds.contains(c.getKey())) throw new IllegalArgumentException("There is no saving " + c.getKey() + ".");
                checkPercent(e.getKey(), "saving " + c.getKey(), c.getValue());
            }
        }

        // Every changed situation's total is checked before anything is written.
        SavingsRules.Version inForce = overviewService.ruleBook().version(level, from);
        LevelRuleVersion stored = versions.findByLevelAndFromMonth(level, from.atDay(1)).orElse(null);
        SavingsRules.Version before = stored != null ? SavingsRules.Version.of(stored) : inForce;
        for (Map.Entry<String, LevelRulesRequest.Percents> e : changed.entrySet()) {
            SavingsRules.Percents now = before.percents(e.getKey());
            BigDecimal total = pick(e.getValue().getDonation(), now.donation())
                    .add(pick(e.getValue().getEmergency(), now.emergency()))
                    .add(pick(e.getValue().getInvestments(), now.investments()));
            Map<Long, BigDecimal> sent = e.getValue().getCustom() == null ? Map.of() : e.getValue().getCustom();
            for (Long id : ownIds) {
                BigDecimal p = sent.get(id) != null ? sent.get(id) : before.custom(e.getKey()).get(id);
                total = total.add(nz(p));
            }
            if (total.compareTo(HUNDRED) > 0) {
                throw new IllegalArgumentException(e.getKey() + ": all savings together can be at most 100%, got "
                        + total.stripTrailingZeros().toPlainString() + "%.");
            }
        }

        LevelRuleVersion row = versionAt(level, from);
        if (req.getCutoff() != null) row.setCutoff(req.getCutoff());
        for (Map.Entry<String, LevelRulesRequest.Percents> e : changed.entrySet()) {
            SituationPercents now = row.getRules().get(e.getKey());
            SavingsRules.Percents fallback = inForce.percents(e.getKey());
            BigDecimal d = pick(e.getValue().getDonation(), now == null ? fallback.donation() : now.getDonation());
            BigDecimal em = pick(e.getValue().getEmergency(), now == null ? fallback.emergency() : now.getEmergency());
            BigDecimal inv = pick(e.getValue().getInvestments(), now == null ? fallback.investments() : now.getInvestments());
            row.getRules().put(e.getKey(), new SituationPercents(d, em, inv));
            if (e.getValue().getCustom() != null) {
                for (Map.Entry<Long, BigDecimal> c : e.getValue().getCustom().entrySet()) {
                    if (c.getValue() != null) row.getCustomPercents().put(LevelRuleVersion.cell(e.getKey(), c.getKey()), c.getValue());
                }
            }
            checkTotal(row, e.getKey(), ownIds);
        }
        versions.save(row);
        return levels(date);
    }

    /**
     * Level {@code level}'s version starting at {@code from}: the stored one, else a new one copying
     * the version in force then — its split line, the three and every own saving's percentages.
     * Every level gets its first version stored before another is added — or the new one would
     * become the first, and the months before it would read it.
     */
    private LevelRuleVersion versionAt(int level, YearMonth from) {
        seedVersions();
        LevelRuleVersion row = versions.findByLevelAndFromMonth(level, from.atDay(1)).orElse(null);
        if (row != null) return row;
        SavingsRules.Version inForce = overviewService.ruleBook().version(level, from);
        row = new LevelRuleVersion();
        row.setLevel(level);
        row.setFromMonth(from.atDay(1));
        row.setCutoff(inForce.cutoff());
        Map<String, SituationPercents> copy = new LinkedHashMap<>();
        Map<String, BigDecimal> own = new LinkedHashMap<>();
        for (String s : SavingsRules.SITUATIONS) {
            SavingsRules.Percents p = inForce.percents(s);
            copy.put(s, new SituationPercents(p.donation(), p.emergency(), p.investments()));
            inForce.custom(s).forEach((id, pct) -> own.put(LevelRuleVersion.cell(s, id), pct));
        }
        row.setRules(copy);
        row.setCustomPercents(own);
        return versions.save(row);
    }

    /** Everything one situation asks in a version — the three and every live own saving — is at most 100%. */
    private static void checkTotal(LevelRuleVersion row, String situation, List<Long> ownIds) {
        SituationPercents p = row.getRules().get(situation);
        BigDecimal total = p == null ? BigDecimal.ZERO : nz(p.getDonation()).add(nz(p.getEmergency())).add(nz(p.getInvestments()));
        for (Long id : ownIds) total = total.add(nz(row.getCustomPercents().get(LevelRuleVersion.cell(situation, id))));
        if (total.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("Level " + row.getLevel() + " from " + YearMonth.from(row.getFromMonth())
                    + ", " + situation + ": all savings together can be at most 100%, this makes "
                    + total.stripTrailingZeros().toPlainString() + "%.");
        }
    }

    // ── The owner's own savings: POST/PUT/DELETE /levels/savings ──────────────

    /**
     * A new saving beside the three: its own pot (a holding nobody bought — nothing leaves a wallet
     * until it is put in), asked {@code percent} in every situation of every level from {@code from}
     * on. Each percentage can be changed on its own after, like the three's.
     */
    @Transactional
    public LevelsResponse createSaving(CustomSavingRequest req, LocalDate date) {
        if (req == null) throw new IllegalArgumentException("A body is required: name, percent, from.");
        String name = savingName(req.getName(), null);
        BigDecimal percent = req.getPercent() == null ? BigDecimal.ZERO : req.getPercent();
        checkPercent("every situation", name, percent);
        YearMonth from = savingFrom(req.getFrom(), date);
        checkRoom(percent, from);
        Investment pot = new Investment();
        pot.setName(name);
        pot.setType(InvestmentType.OTHER);
        pot.setInvestedAmount(BigDecimal.ZERO);
        pot.setCurrency(Currency.UZS);
        pot.setPurchaseDate(from.atDay(1));
        pot.setOpeningBalance(true);
        pot.setEmergencyFund(false);
        pot.setSavingsGoal(false);
        pot.setCustomSaving(true);
        pot = investmentRepository.save(pot);
        askEverywhereFrom(pot.getId(), percent, from);
        return levels(date);
    }

    /** Rename one. */
    @Transactional
    public LevelsResponse renameSaving(Long id, CustomSavingRequest req, LocalDate date) {
        Investment pot = saving(id);
        pot.setName(savingName(req == null ? null : req.getName(), id));
        investmentRepository.save(pot);
        return levels(date);
    }

    /** Ask nothing more of it from {@code from} on, at every level; the months before keep their asks, the pot its money. */
    @Transactional
    public LevelsResponse stopSaving(Long id, CustomSavingRequest req, LocalDate date) {
        saving(id);
        askEverywhereFrom(id, BigDecimal.ZERO, savingFrom(req == null ? null : req.getFrom(), date));
        return levels(date);
    }

    /**
     * Gone everywhere — its pot and its percentages in every version, past months' included. Only
     * while no money ever moved through it: one that did is stopped instead, so its history stays.
     */
    @Transactional
    public LevelsResponse deleteSaving(Long id, LocalDate date) {
        Investment pot = saving(id);
        if (hasMoney(pot)) {
            throw new IllegalArgumentException(pot.getName() + " has money in its history — stop it from a month instead.");
        }
        for (LevelRuleVersion row : versions.findAllByOrderByLevelAscFromMonthAsc()) {
            if (row.getCustomPercents().keySet().removeIf(k -> {
                SavingsRules.Cell c = SavingsRules.Cell.parse(k);
                return c != null && id.equals(c.savingId());
            })) versions.save(row);
        }
        investmentRepository.delete(pot);
        return levels(date);
    }

    /**
     * Before a new saving is made: every situation of every level, in the version in force at
     * {@code from} and every later one, has room for {@code percent} more — at most 100% together.
     */
    private void checkRoom(BigDecimal percent, YearMonth from) {
        if (percent.signum() <= 0) return;
        SavingsRules.Book book = overviewService.ruleBook();
        java.util.Set<Long> live = overviewService.customSavings().stream().map(Investment::getId)
                .collect(java.util.stream.Collectors.toSet());
        for (int level = 1; level <= SavingsRules.TOP_LEVEL; level++) {
            List<SavingsRules.Version> checked = new ArrayList<>(book.versions(level).stream()
                    .filter(v -> v.from().isAfter(from)).toList());
            checked.add(book.version(level, from));
            for (SavingsRules.Version v : checked) {
                for (String s : SavingsRules.SITUATIONS) {
                    BigDecimal total = v.percents(s).total().add(percent);
                    for (Map.Entry<Long, BigDecimal> e : v.custom(s).entrySet()) {
                        if (live.contains(e.getKey())) total = total.add(nz(e.getValue()));
                    }
                    if (total.compareTo(HUNDRED) > 0) {
                        throw new IllegalArgumentException("That would make Level " + level + "'s savings "
                                + total.stripTrailingZeros().toPlainString() + "% in one of its situations — "
                                + "together they can be at most 100%. Try a smaller percentage.");
                    }
                }
            }
        }
    }

    /** {@code savingId} asks {@code percent} in every situation from {@code from} on: the version then, and every later one, at every level. */
    private void askEverywhereFrom(Long savingId, BigDecimal percent, YearMonth from) {
        List<Long> ownIds = overviewService.customSavings().stream().map(Investment::getId).toList();
        if (!ownIds.contains(savingId)) ownIds = java.util.stream.Stream.concat(ownIds.stream(), java.util.stream.Stream.of(savingId)).toList();
        for (int level = 1; level <= SavingsRules.TOP_LEVEL; level++) {
            versionAt(level, from);
            for (LevelRuleVersion row : versions.findByLevelOrderByFromMonthAsc(level)) {
                if (YearMonth.from(row.getFromMonth()).isBefore(from)) continue;
                for (String s : SavingsRules.SITUATIONS) {
                    row.getCustomPercents().put(LevelRuleVersion.cell(s, savingId), percent);
                    checkTotal(row, s, ownIds);
                }
                versions.save(row);
            }
        }
    }

    private Investment saving(Long id) {
        Investment pot = id == null ? null : investmentRepository.findById(id).orElse(null);
        if (pot == null || !pot.isCustomSaving()) throw new ResourceNotFoundException("Saving", id);
        return pot;
    }

    /** Trimmed, 1–60 characters, not another saving's name (any case). */
    private String savingName(String name, Long self) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) throw new IllegalArgumentException("Give the saving a name.");
        if (n.length() > 60) throw new IllegalArgumentException("A name can be at most 60 characters.");
        boolean taken = overviewService.customSavings().stream()
                .anyMatch(s -> !s.getId().equals(self) && s.getName().trim().equalsIgnoreCase(n));
        if (taken) throw new IllegalArgumentException("You already have a saving called " + n + ".");
        return n;
    }

    /** The month a saving starts or stops: from the first tracked month up to 12 ahead; absent → this month. */
    private YearMonth savingFrom(String value, LocalDate date) {
        YearMonth current = YearMonth.from(date);
        YearMonth from = month("from", value);
        if (from == null) from = current;
        YearMonth first = overviewService.firstLevelMonth(current);
        if (from.isBefore(first)) throw new IllegalArgumentException("It can apply from " + first + " at the earliest, not " + from + ".");
        if (from.isAfter(current.plusMonths(MAX_MONTHS_AHEAD))) {
            throw new IllegalArgumentException("It can apply up to " + current.plusMonths(MAX_MONTHS_AHEAD) + " (12 months ahead), not " + from + ".");
        }
        return from;
    }

    /** Any money moved in or out of it, or held in it. */
    private boolean hasMoney(Investment pot) {
        if (nz(pot.getInvestedAmount()).signum() > 0 || nz(pot.getCurrentValue()).signum() > 0) return true;
        List<uz.tracker.trackerproject.entity.Transaction> moved = transactionRepository == null ? null
                : transactionRepository.findByInvestmentIdOrderByTransactionDateDesc(pot.getId());
        return moved != null && !moved.isEmpty();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
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
        // After the Level 5 run, so a start or an end due on the 1st is in the month's level.
        if (monthRules == null) return;
        try {
            monthRules.fix(date);
        } catch (RuntimeException e) {
            log.warn("Could not fix the savings rule for {}: {}", YearMonth.from(date), e.toString());
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
