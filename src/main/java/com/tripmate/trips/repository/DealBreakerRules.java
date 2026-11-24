package com.tripmate.trips.repository;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The hard half of matching, in SQL: the deal-breakers on both sides of a
 * viewer and a trip's host. Browse ANDs every rule into its WHERE clause; a
 * single trip's check selects each rule as a column to say which one failed.
 * Both read the same fragments, so the list and the detail screen can never
 * disagree about what a deal-breaker rules out.
 *
 * <p>The fragments expect {@code t} (trips), {@code h} (the host in
 * user_public_facts) and the {@code :viewer} parameter, and {@link #JOINS}
 * adds {@code v} (the viewer's facts), {@code vd} and {@code hd} (the viewer's
 * and host's deal-breakers). Someone who never set deal-breakers has no row
 * in deal_breaker_facts and is read as having no limits.
 *
 * <ul>
 *   <li>Smoking and drinking: NO ("a trip without it") and OK ("it will be
 *       part of my trip") rule each other out, whichever side holds which;
 *       EITHER goes with both.</li>
 *   <li>Gender: SAME needs both people's gender on record and equal. Someone
 *       who prefers not to say cannot meet a SAME preference, theirs or the
 *       other person's, since it cannot be checked.</li>
 *   <li>Trust: each side's trust score must reach the other's minimum.</li>
 * </ul>
 */
public final class DealBreakerRules {

    /** One deal-breaker, named from the viewer's side. */
    public enum Rule {
        SMOKING, DRINKING, MY_GENDER_PREFERENCE, HOST_GENDER_PREFERENCE, MY_TRUST_MINIMUM, HOST_TRUST_MINIMUM
    }

    static final String JOINS = """
            JOIN user_public_facts v ON v.user_id = :viewer
            LEFT JOIN deal_breaker_facts vd ON vd.user_id = :viewer
            LEFT JOIN deal_breaker_facts hd ON hd.user_id = t.host_user_id""";

    /** Each rule as a condition that is true when the rule is met. */
    static final Map<Rule, String> PASSES = passes();

    /** Every rule met. */
    static final String ALL_PASS = Arrays.stream(Rule.values())
            .map(rule -> "(" + PASSES.get(rule) + ")")
            .collect(Collectors.joining(" AND "));

    private DealBreakerRules() {
    }

    /** The column a single-trip check selects a rule as. */
    static String column(Rule rule) {
        return "passes_" + rule.name().toLowerCase(Locale.ROOT);
    }

    private static Map<Rule, String> passes() {
        Map<Rule, String> passes = new EnumMap<>(Rule.class);
        passes.put(Rule.SMOKING, habit("smoking"));
        passes.put(Rule.DRINKING, habit("drinking"));
        passes.put(Rule.MY_GENDER_PREFERENCE, """
                COALESCE(vd.gender_preference, 'ANY') = 'ANY'
                OR (v.gender = h.gender AND v.gender <> 'PREFER_NOT_TO_SAY')""");
        passes.put(Rule.HOST_GENDER_PREFERENCE, """
                COALESCE(hd.gender_preference, 'ANY') = 'ANY'
                OR (v.gender = h.gender AND h.gender <> 'PREFER_NOT_TO_SAY')""");
        passes.put(Rule.MY_TRUST_MINIMUM, "h.trust_score >= COALESCE(vd.min_trust_score, 0)");
        passes.put(Rule.HOST_TRUST_MINIMUM, "v.trust_score >= COALESCE(hd.min_trust_score, 0)");
        return Map.copyOf(passes);
    }

    /** The column name is one of two fixed words above, never input. */
    private static String habit(String column) {
        String viewer = "COALESCE(vd." + column + ", 'EITHER')";
        String host = "COALESCE(hd." + column + ", 'EITHER')";
        return "NOT ((" + viewer + " = 'NO' AND " + host + " = 'OK') OR (" + viewer + " = 'OK' AND " + host
                + " = 'NO'))";
    }
}
