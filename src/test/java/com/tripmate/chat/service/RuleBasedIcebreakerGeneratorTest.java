package com.tripmate.chat.service;

import com.tripmate.vibe.entity.BudgetBand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedIcebreakerGeneratorTest {

    private final RuleBasedIcebreakerGenerator generator = new RuleBasedIcebreakerGenerator();

    @ParameterizedTest
    @EnumSource(BudgetBand.class)
    void suggestsThreeDistinctLinesForEveryBudget(BudgetBand band) {
        List<String> suggestions = generator.suggest(context(UUID.randomUUID(), band, false));

        assertThat(suggestions).hasSize(3).doesNotHaveDuplicates().allSatisfy(line -> assertThat(line).isNotBlank());
    }

    @Test
    void theSameConversationGetsTheSameSuggestions() {
        UUID conversationId = UUID.randomUUID();

        assertThat(generator.suggest(context(conversationId, BudgetBand.MID, true)))
                .isEqualTo(generator.suggest(context(conversationId, BudgetBand.MID, true)));
    }

    @Test
    void theFirstLineGreetsTheOtherPersonFromTheAskersSide() {
        assertThat(generator.suggest(context(UUID.randomUUID(), BudgetBand.MID, true)).getFirst())
                .startsWith("Hi Ravi!").contains("What made you pick this trip to Hampi");
        assertThat(generator.suggest(context(UUID.randomUUID(), BudgetBand.MID, false)).getFirst())
                .startsWith("Hi Ravi!").contains("What got you planning a trip to Hampi");
    }

    private static IcebreakerGenerator.Context context(UUID conversationId, BudgetBand band, boolean host) {
        return new IcebreakerGenerator.Context(conversationId, "Hampi", LocalDate.of(2026, 12, 10),
                LocalDate.of(2026, 12, 13), band, host, "Ravi");
    }
}
