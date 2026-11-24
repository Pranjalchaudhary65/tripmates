package com.tripmate.chat.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Opening lines built from the trip's own facts: where, when, how long, on
 * what budget, and which side of the trip the asker is on. Three are chosen
 * per conversation, the same three every time it is opened, from a pool
 * that always has at least that many.
 */
@Component
@ConditionalOnProperty(name = "tripmate.chat.icebreakers", havingValue = RuleBasedIcebreakerGenerator.ID)
public class RuleBasedIcebreakerGenerator implements IcebreakerGenerator {

    public static final String ID = "rule-based";

    static final int SUGGESTIONS = 3;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<String> suggest(Context context) {
        String place = context.destinationName();
        long days = ChronoUnit.DAYS.between(context.startDate(), context.endDate()) + 1;
        String month = context.startDate().getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);

        List<String> pool = new ArrayList<>();
        if (context.viewerIsHost()) {
            pool.add("Hi " + context.otherFirstName() + "! What made you pick this trip to " + place + "?");
            pool.add("Is there anything you would really like to fit into the plan for " + place + "?");
        } else {
            pool.add("Hi " + context.otherFirstName() + "! What got you planning a trip to " + place + "?");
            pool.add("Have you been to " + place + " before, or is it new for you too?");
        }
        pool.add("What is the one thing you most want to do in " + place + "?");
        pool.add(place + " in " + month + " sounds great. Have you travelled there at this time of year?");
        pool.add(days <= 3
                ? "With " + days + (days == 1 ? " day" : " days") + ", are you thinking a packed plan or a slow one?"
                : days + " days is a good stretch. Slow mornings or early starts?");
        pool.add(switch (context.budgetBand()) {
            case SHOESTRING -> "Hostels and street food, or a mix with a few treats?";
            case MID -> "Any stays or food spots you already have your eye on?";
            case COMFORT -> "Do you prefer one comfortable base or moving around between stays?";
            case LUXURY -> "Any places to stay you have been wanting to try?";
        });
        pool.add("How do you usually like to split costs on a trip?");
        pool.add("Are you more of a planner, or do you like to figure things out on the way?");

        int start = Math.floorMod(context.conversationId().hashCode(), pool.size());
        List<String> chosen = new ArrayList<>(SUGGESTIONS);
        chosen.add(pool.get(0));
        for (int i = 0; chosen.size() < SUGGESTIONS; i++) {
            String candidate = pool.get(1 + Math.floorMod(start + i, pool.size() - 1));
            if (!chosen.contains(candidate)) {
                chosen.add(candidate);
            }
        }
        return chosen;
    }
}
