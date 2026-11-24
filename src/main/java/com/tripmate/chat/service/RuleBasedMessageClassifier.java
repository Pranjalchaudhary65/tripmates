package com.tripmate.chat.service;

import com.tripmate.chat.entity.FlagType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Fixed patterns for the three things the masked chat watches for, in
 * English and common Hinglish. Each rule carries a confidence; a message
 * gets the strongest match of each type.
 *
 * <p>Built to catch the usual shapes of a scam or a push off the app
 * ("send 2000 advance on gpay", "whatsapp me", "come to my hotel room"),
 * not to understand language: it will miss rephrasings and sometimes flag
 * innocent text, which is why a flag warns and queues rather than blocks,
 * and only the high-confidence rules feed the trust score.
 */
@Component
@ConditionalOnProperty(name = "tripmate.chat.classifier", havingValue = RuleBasedMessageClassifier.ID)
public class RuleBasedMessageClassifier implements MessageClassifier {

    public static final String ID = "rule-based";

    /** An amount or a way to move one. A bare number is not enough: dates and prices are everywhere in trip talk. */
    private static final String MONEY_WORDS = "(?:money|cash|paisa|paise|rupees?|(?:rs\\.?|inr|₹)\\s?\\d"
            + "|\\d[\\d,]*\\s?(?:rs|rupees|/-)|upi|gpay|g-pay|google pay|paytm|phonepe|phone pe|bhim|bank|account"
            + "|a/c|ifsc)";

    /**
     * Money asked for, not money talked about: splitting a hostel bill is
     * normal, so a rule needs the money to be going to the writer.
     */
    private static final List<Rule> RULES = List.of(
            new Rule(FlagType.MONEY_REQUEST, "0.950", "\\b(?:send|transfer|pay|deposit|lend|bhej(?:o|do)?)\\s+"
                    + "(?:me|mujhe|to me|into my|to my|in my)\\b.{0,40}" + MONEY_WORDS),
            new Rule(FlagType.MONEY_REQUEST, "0.900", "\\b(?:pay|send|transfer|bhejo|de do)\\b.{0,15}\\b(?:advance"
                    + "|token amount|booking amount|security deposit|registration fee)\\b"),
            new Rule(FlagType.MONEY_REQUEST, "0.800", "\\b(?:my|mera|meri)\\s+(?:upi|gpay|paytm|phonepe|bank"
                    + "|account number|ifsc)\\b"),
            new Rule(FlagType.MONEY_REQUEST, "0.700", "\\b(?:need|want|chahiye)\\b.{0,20}\\b(?:money|cash|paisa|loan)\\b"),

            new Rule(FlagType.OFF_PLATFORM_PUSH, "0.800", "\\b(?:whats\\s?app|wapp|telegram|signal app|insta(?:gram)?"
                    + "|snap(?:chat)?|facebook|messenger|wechat|imo app)\\b"),
            new Rule(FlagType.OFF_PLATFORM_PUSH, "0.800", "\\b(?:call|text|ping|dm|message|sms|msg)\\s+(?:me|karo)\\s+"
                    + "(?:on|at|directly|outside|privately)\\b"),
            new Rule(FlagType.OFF_PLATFORM_PUSH, "0.750", "\\b(?:my|your|apna|mera|tumhara)\\s+(?:number|no\\.?"
                    + "|mobile|phone|cell|handle|id)\\b"),
            new Rule(FlagType.OFF_PLATFORM_PUSH, "0.900", "\\b(?:skip|don'?t need|no need for|forget)\\b.{0,25}"
                    + "\\b(?:app|meet-?up|check-?in|verification)\\b"),
            new Rule(FlagType.OFF_PLATFORM_PUSH, "0.900", "\\b(?:meet|come|aao|aa jao|stay)\\b.{0,30}\\b(?:my|mere"
                    + "|your)\\s+(?:place|room|flat|house|home|hotel|ghar|car)\\b"),

            new Rule(FlagType.HARASSMENT, "0.950", "\\b(?:i(?:'ll| will| am going to)|gonna)\\s+(?:kill|hurt|find"
                    + "|beat|rape|stalk)\\s+you\\b"),
            new Rule(FlagType.HARASSMENT, "0.950", "\\b(?:send|share|bhejo)\\b.{0,20}\\b(?:nudes?|naked|sexy"
                    + "|private) (?:pics?|photos?|pictures?|videos?)\\b"),
            new Rule(FlagType.HARASSMENT, "0.850", "\\b(?:bitch|slut|whore|bastard|cunt|motherfucker|fuck you"
                    + "|randi|chutiya|madarchod|behenchod|bhenchod|harami|kutti|kamina)\\b"));

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Label> classify(String text, boolean hadContactDetails) {
        String lower = text.toLowerCase(Locale.ROOT);
        List<Label> labels = new ArrayList<>();
        if (hadContactDetails) {
            labels.add(new Label(FlagType.OFF_PLATFORM_PUSH, new BigDecimal("0.900")));
        }
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(lower).find()) {
                labels.add(new Label(rule.type(), rule.confidence()));
            }
        }
        List<Label> strongestPerType = new ArrayList<>();
        labels.stream()
                .sorted(Comparator.comparing(Label::confidence).reversed())
                .forEach(label -> {
                    if (strongestPerType.stream().noneMatch(kept -> kept.type() == label.type())) {
                        strongestPerType.add(label);
                    }
                });
        return strongestPerType;
    }

    private record Rule(FlagType type, BigDecimal confidence, Pattern pattern) {

        Rule(FlagType type, String confidence, String regex) {
            this(type, new BigDecimal(confidence), Pattern.compile(regex));
        }
    }
}
