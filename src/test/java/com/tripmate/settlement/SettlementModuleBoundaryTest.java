package com.tripmate.settlement;

import com.tripmate.settlement.service.SettlementTrustEvents;
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
 * DESIGN.md, Module boundaries: settlement depends on identity, trips and the
 * platform only, and reaches the trust score through the outbox alone, so
 * the trust engine can be built later as a pure consumer of its events.
 */
class SettlementModuleBoundaryTest {

    private static final Path MAIN = Path.of("src", "main", "java", "com", "tripmate");

    private static final Path SETTLEMENT = MAIN.resolve("settlement");

    private static final Pattern IMPORT = Pattern.compile("import\\s+(?:static\\s+)?com\\.tripmate\\.(\\w+)\\.");

    @Test
    void settlementImportsOnlyIdentityTripsAndThePlatform() throws IOException {
        try (Stream<Path> files = Files.walk(SETTLEMENT)) {
            List<String> foreign = files.filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> importedModules(path).stream()
                            .filter(module -> !List.of("settlement", "identity", "trips", "platform").contains(module))
                            .map(module -> path.getFileName() + " imports " + module))
                    .toList();
            assertThat(foreign).isEmpty();
        }
    }

    @Test
    void noOtherModuleImportsSettlement() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            List<Path> importers = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.startsWith(SETTLEMENT))
                    .filter(path -> importedModules(path).contains("settlement"))
                    .toList();
            assertThat(importers).isEmpty();
        }
    }

    /** The one trust input settle-up has: someone left a settlement undone. Only IDs and a role go with it. */
    @Test
    void theOnlyTrustInputIsAnOverdueSettlement() {
        List<Method> methods = Arrays.stream(SettlementTrustEvents.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList();
        assertThat(methods).extracting(Method::getName).containsExactly("settlementOverdue");
        assertThat(methods.getFirst().getParameterTypes())
                .containsExactly(UUID.class, UUID.class, SettlementTrustEvents.OverdueRole.class);
    }

    private static List<String> importedModules(Path path) {
        try {
            Matcher matcher = IMPORT.matcher(Files.readString(path));
            return matcher.results().map(result -> result.group(1)).distinct().toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
