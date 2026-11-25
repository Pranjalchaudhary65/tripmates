package com.tripmate.safety;

import com.tripmate.safety.service.SafetyModerationEvents;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DESIGN.md, Trust Score Engine, safety carve-out: safety actions are always
 * free. Held as facts about the source: the safety module depends on
 * identity, trips and the platform only (never meet-up, never the trust
 * score), names no trust input, and its one outbox publisher is a
 * moderation flag that takes only IDs.
 */
class SafetyModuleBoundaryTest {

    private static final Path MAIN = Path.of("src", "main", "java", "com", "tripmate");

    private static final Path SAFETY = MAIN.resolve("safety");

    private static final Pattern IMPORT = Pattern.compile("import\\s+(?:static\\s+)?com\\.tripmate\\.(\\w+)\\.");

    @Test
    void safetyImportsOnlyIdentityTripsAndThePlatform() throws IOException {
        assertThat(safetyFiles().stream()
                .flatMap(path -> importedModules(path).stream()
                        .filter(module -> !List.of("safety", "identity", "trips", "platform").contains(module))
                        .map(module -> path.getFileName() + " imports " + module))
                .toList()).isEmpty();
    }

    @Test
    void nothingInSafetyNamesATrustInput() throws IOException {
        Pattern trust = Pattern.compile("TrustEvents|TrustInput|GHOST|trust_score|AGGREGATE_USER");
        assertThat(safetyFiles().stream().filter(path -> trust.matcher(read(path)).find()).toList()).isEmpty();
    }

    @Test
    void theOnlyOutboxEventIsAModerationFlagCarryingIds() {
        List<Method> methods = Arrays.stream(SafetyModerationEvents.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList();
        assertThat(methods).extracting(Method::getName).containsExactly("sosRaised");
        assertThat(methods.getFirst().getParameterTypes()).containsOnly(UUID.class);
    }

    @Test
    void noOtherModuleImportsSafety() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            assertThat(files.filter(path -> path.toString().endsWith(".java") && !path.startsWith(SAFETY))
                    .filter(path -> importedModules(path).contains("safety"))
                    .toList()).isEmpty();
        }
    }

    private static List<Path> safetyFiles() throws IOException {
        try (Stream<Path> files = Files.walk(SAFETY)) {
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
