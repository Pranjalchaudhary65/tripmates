package com.tripmate.meetup;

import com.tripmate.meetup.service.MeetupTrustEvents;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DESIGN.md, Module boundaries: the private comfort rating must be
 * unreachable from any code that feeds the trust score or a profile. That is
 * held as a fact about the source, not a habit: no module depends on
 * meet-up at all, and nothing outside it names the rating or its table.
 */
class MeetupModuleBoundaryTest {

    private static final Path MAIN = Path.of("src", "main", "java", "com", "tripmate");

    private static final Path MEETUP = MAIN.resolve("meetup");

    @Test
    void noOtherModuleImportsTheMeetupModule() throws IOException {
        assertThat(filesOutsideMeetupMatching(Pattern.compile("import\\s+com\\.tripmate\\.meetup\\.")))
                .as("files outside meetup that import it").isEmpty();
    }

    @Test
    void nothingOutsideTheMeetupModuleNamesTheComfortRating() throws IOException {
        assertThat(filesOutsideMeetupMatching(Pattern.compile("(?i)meetup_?feedback|comfort_?rating")))
                .as("files outside meetup that name the rating").isEmpty();
    }

    /**
     * The meet-up's one way into the trust score is MeetupTrustEvents, and it
     * can say exactly two things, both about turning up. A method that could
     * carry a rating or a safety cancellation would have to be added here.
     */
    @Test
    void theOnlyTrustInputsAreNoShowsAndLateChangeOfPlanCancellations() {
        assertThat(Arrays.stream(MeetupTrustEvents.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName))
                .containsExactlyInAnyOrder("noShow", "lateConvenienceCancel");
        for (Method method : MeetupTrustEvents.class.getDeclaredMethods()) {
            assertThat(method.getParameterTypes()).as(method.getName()).containsOnly(UUID.class);
        }
    }
    private static List<Path> filesOutsideMeetupMatching(Pattern pattern) throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.startsWith(MEETUP))
                    .filter(path -> {
                        try {
                            return pattern.matcher(Files.readString(path)).find();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
        }
    }
}
