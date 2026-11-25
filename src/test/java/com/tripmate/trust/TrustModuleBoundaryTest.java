package com.tripmate.trust;

import com.tripmate.chat.service.ChatTrustEvents;
import com.tripmate.identity.service.TrustInputEvents;
import com.tripmate.meetup.service.MeetupTrustEvents;
import com.tripmate.safety.service.SafetyModerationEvents;
import com.tripmate.settlement.service.SettlementTrustEvents;
import com.tripmate.trust.service.TrustScoreFormula;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DESIGN.md, Trust Score Engine: the score reacts only to what other modules
 * publish, and some things must never reach it at all, by construction
 * rather than by care: the private comfort rating, safety cancellations, SOS
 * and every other safety action. Held here as facts about the source.
 */
class TrustModuleBoundaryTest {

    private static final Path MAIN = Path.of("src", "main", "java", "com", "tripmate");

    private static final Path TRUST = MAIN.resolve("trust");

    private static final Pattern IMPORT = Pattern.compile("import\\s+(?:static\\s+)?com\\.tripmate\\.(\\w+)\\.");

    @Test
    void trustImportsOnlyIdentityTripsAndThePlatform() throws IOException {
        assertThat(trustFiles().stream()
                .flatMap(path -> importedModules(path).stream()
                        .filter(module -> !List.of("trust", "identity", "trips", "platform").contains(module))
                        .map(module -> path.getFileName() + " imports " + module))
                .toList()).isEmpty();
    }

    @Test
    void nothingInTrustReadsSafetyOrMeetupFeedbackTables() throws IOException {
        Pattern forbidden = Pattern.compile("meetup_feedback|comfort_rating|comfortRating|sos_events|checkins"
                + "|itinerary_shares|cancellation_type|CancellationType|SAFETY_");
        assertThat(trustFiles().stream().filter(path -> forbidden.matcher(read(path)).find()).toList()).isEmpty();
    }

    @Test
    void theHandlerAcceptsExactlyThePublishedTrustInputsAndNoSafetyEvent() {
        @SuppressWarnings("unchecked")
        Map<String, String> accepted = (Map<String, String>) ReflectionTestUtils.getField(
                com.tripmate.trust.service.TrustInputHandler.class, "DETAIL_FIELD");

        assertThat(accepted).containsOnlyKeys(
                TrustInputEvents.VERIFICATION_TIER_CHANGE, TrustInputEvents.VOUCH_GIVEN,
                TrustInputEvents.VOUCH_REVOKED, TrustInputEvents.VOUCH_PENALIZED,
                MeetupTrustEvents.MEETUP_NO_SHOW, MeetupTrustEvents.MEETUP_LATE_CANCEL,
                SettlementTrustEvents.SETTLEMENT_OVERDUE, ChatTrustEvents.CHAT_MESSAGE_FLAGGED);
        assertThat(accepted).doesNotContainKey(SafetyModerationEvents.SOS_RAISED);
        assertThat(accepted.keySet()).noneMatch(type -> type.startsWith("SAFETY") || type.contains("COMFORT")
                || type.contains("DISPUTE"));
    }

    @Test
    void theFormulaHasNoInputForReplyRatesOrSafetyActions() {
        assertThat(TrustScoreFormula.InputType.values()).extracting(Enum::name).containsExactlyInAnyOrder(
                "MEETUP_NO_SHOW", "MEETUP_LATE_CANCEL", "SETTLEMENT_OVERDUE", "CHAT_MESSAGE_FLAGGED",
                "VOUCH_PENALIZED");
    }

    @Test
    void noOtherModuleImportsTrust() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            assertThat(files.filter(path -> path.toString().endsWith(".java") && !path.startsWith(TRUST))
                    .filter(path -> importedModules(path).contains("trust"))
                    .toList()).isEmpty();
        }
    }

    private static List<Path> trustFiles() throws IOException {
        try (Stream<Path> files = Files.walk(TRUST)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private static List<String> importedModules(Path path) {
        Matcher matcher = IMPORT.matcher(read(path));
        return matcher.results().map(result -> result.group(1)).distinct().toList();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
