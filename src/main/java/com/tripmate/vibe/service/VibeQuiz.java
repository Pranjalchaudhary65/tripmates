package com.tripmate.vibe.service;

import com.tripmate.vibe.entity.BudgetBand;
import com.tripmate.vibe.entity.ExpenseSplitStyle;
import com.tripmate.vibe.entity.Pace;
import com.tripmate.vibe.entity.PlanningStyle;
import com.tripmate.vibe.entity.VibeDimensions;
import com.tripmate.vibe.entity.WakeTime;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The vibe quiz: 14 questions, each feeding exactly one of the eight
 * dimensions, and the scoring that turns a full set of answers into a
 * profile. The questions live here, not in the client, so the server never
 * trusts a client's idea of what an answer means.
 *
 * <p>Every option carries a number on its dimension's own scale: the
 * position in the enum for the ordered categories (budget, pace, wake time,
 * planning), or 1-5 for the scales. A dimension with two questions takes the
 * mean of the two, rounded half up, so one answer pulls as hard as the other.
 * A reversed question ("time on my own matters to me") is written with its
 * options already reversed, so scoring never needs a special case.
 *
 * <p>The expense question names its category directly: the three ways of
 * splitting costs have no order, and an average of them would mean nothing.
 *
 * <p>{@link #VERSION} goes up whenever a question, an option or a number
 * changes; stored answers say which version they answered.
 */
public final class VibeQuiz {

    public static final int VERSION = 1;

    private static final List<Question> QUESTIONS = List.of(
            categorical("budget-stay", Dimension.BUDGET_BAND, "Where would you rather sleep on a trip?",
                    "Dorm bed in a hostel", "Simple guesthouse or budget hotel", "Comfortable hotel",
                    "Resort or boutique stay"),
            categorical("budget-transport", Dimension.BUDGET_BAND, "How do you like to get between cities?",
                    "Overnight bus or sleeper train", "AC train or day bus", "A flight if it saves a day",
                    "Whatever is most comfortable"),
            categorical("pace-day", Dimension.PACE, "What does a good day on a trip look like?",
                    "As many sights as I can fit in", "Two or three things, done properly",
                    "One thing, and lots of wandering"),
            categorical("pace-rest", Dimension.PACE, "On a week-long trip, how many slow days do you want?",
                    "None, I can rest at home", "One or two", "Most of them"),
            categorical("wake-morning", Dimension.WAKE_TIME, "On holiday you usually wake up…",
                    "Before 7", "Between 7 and 9", "After 9"),
            categorical("wake-sunrise", Dimension.WAKE_TIME, "A 5 am start for a sunrise trek?",
                    "I'm in", "If it's worth it", "Not a chance"),
            categorical("plan-before", Dimension.PLANNING_STYLE, "Before the trip, you have…",
                    "A day-by-day itinerary", "The bookings and a list of ideas", "A ticket out, and that's it"),
            categorical("plan-change", Dimension.PLANNING_STYLE, "The plan falls apart on day two. You feel…",
                    "Stressed until there's a new one", "Fine, we'll adjust", "Relieved, now it's an adventure"),
            scale("social-evenings", Dimension.SOCIAL_ENERGY, "I like spending evenings meeting new people.",
                    false),
            scale("social-alone", Dimension.SOCIAL_ENERGY, "I need time on my own every day of a trip.", true),
            scale("risk-adventure", Dimension.RISK_APPETITE, "Paragliding, scuba or a remote trek sounds great.",
                    false),
            scale("risk-detour", Dimension.RISK_APPETITE,
                    "I'd take an unplanned detour down a road I don't know.", false),
            scale("food-street", Dimension.FOOD_ADVENTUROUSNESS,
                    "I'd try street food from a stall I know nothing about.", false),
            new Question("expense-split", Dimension.EXPENSE_SPLIT_STYLE, "How should shared costs work?", List.of(
                    new Option("strict", "Split every bill exactly", ExpenseSplitStyle.STRICT_SPLIT.ordinal()),
                    new Option("whoever", "Whoever can pays, it evens out", ExpenseSplitStyle.WHOEVER_CAN.ordinal()),
                    new Option("alternate", "Take turns paying",
                            ExpenseSplitStyle.ONE_PAYS_ALTERNATE.ordinal()))));

    private static final Map<String, Question> BY_ID = index();

    private VibeQuiz() {
    }

    public static List<Question> questions() {
        return QUESTIONS;
    }

    public static Optional<Question> question(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /** True when {@code optionId} is one of the options of question {@code questionId}. */
    public static boolean isAnswer(String questionId, String optionId) {
        return question(questionId).flatMap(question -> question.option(optionId)).isPresent();
    }

    /**
     * @param answers question ID to option ID, every question answered with
     *                one of its own options
     * @throws IllegalArgumentException when an answer is missing or not an option
     */
    public static VibeDimensions score(Map<String, String> answers) {
        Map<Dimension, int[]> totals = new LinkedHashMap<>();
        for (Question question : QUESTIONS) {
            String optionId = answers.get(question.id());
            Option option = question.option(optionId)
                    .orElseThrow(() -> new IllegalArgumentException("No valid answer to " + question.id()));
            int[] sumAndCount = totals.computeIfAbsent(question.dimension(), dimension -> new int[2]);
            sumAndCount[0] += option.value();
            sumAndCount[1]++;
        }
        return new VibeDimensions(
                BudgetBand.values()[mean(totals, Dimension.BUDGET_BAND)],
                Pace.values()[mean(totals, Dimension.PACE)],
                WakeTime.values()[mean(totals, Dimension.WAKE_TIME)],
                PlanningStyle.values()[mean(totals, Dimension.PLANNING_STYLE)],
                mean(totals, Dimension.SOCIAL_ENERGY),
                mean(totals, Dimension.RISK_APPETITE),
                mean(totals, Dimension.FOOD_ADVENTUROUSNESS),
                ExpenseSplitStyle.values()[mean(totals, Dimension.EXPENSE_SPLIT_STYLE)]);
    }

    /** Half up, in exact arithmetic: (1 + 2) / 2 = 1.5 becomes 2, never 1 through a binary rounding error. */
    private static int mean(Map<Dimension, int[]> totals, Dimension dimension) {
        int[] sumAndCount = totals.get(dimension);
        return BigDecimal.valueOf(sumAndCount[0])
                .divide(BigDecimal.valueOf(sumAndCount[1]), 0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    /** Options valued 0, 1, 2… in the order of the dimension's enum, with IDs a, b, c… */
    private static Question categorical(String id, Dimension dimension, String text, String... labels) {
        Option[] options = new Option[labels.length];
        for (int i = 0; i < labels.length; i++) {
            options[i] = new Option(String.valueOf((char) ('a' + i)), labels[i], i);
        }
        return new Question(id, dimension, text, List.of(options));
    }

    /**
     * Five options from "Strongly disagree" (ID 1) to "Strongly agree" (ID 5).
     * Reversed, agreeing scores low: the statement points the other way.
     */
    private static Question scale(String id, Dimension dimension, String text, boolean reversed) {
        String[] labels = {"Strongly disagree", "Disagree", "Not sure", "Agree", "Strongly agree"};
        Option[] options = new Option[labels.length];
        for (int i = 0; i < labels.length; i++) {
            int value = reversed ? labels.length - i : i + 1;
            options[i] = new Option(String.valueOf(i + 1), labels[i], value);
        }
        return new Question(id, dimension, text, List.of(options));
    }

    private static Map<String, Question> index() {
        Map<String, Question> byId = new LinkedHashMap<>();
        for (Question question : QUESTIONS) {
            if (byId.put(question.id(), question) != null) {
                throw new IllegalStateException("Duplicate question " + question.id());
            }
        }
        return Map.copyOf(byId);
    }

    public enum Dimension {
        BUDGET_BAND, PACE, WAKE_TIME, PLANNING_STYLE, SOCIAL_ENERGY, RISK_APPETITE, FOOD_ADVENTUROUSNESS,
        EXPENSE_SPLIT_STYLE
    }

    /** @param value the option's number on its dimension; never sent to the client */
    public record Option(String id, String label, int value) {
    }

    public record Question(String id, Dimension dimension, String text, List<Option> options) {

        public Optional<Option> option(String optionId) {
            return options.stream().filter(option -> option.id().equals(optionId)).findFirst();
        }
    }
}
