package org.example.fitfetch.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the enum against the check constraint that mirrors it.
 *
 * <p>{@code ck_fetched_jobs_normalize_status} lists its values, so a constant
 * added here without a migration naming it there fails at runtime, on the first
 * job the pass tries to write, rather than at build time. Nothing else catches
 * it: there is no database in the test run, so no migration is ever executed.
 */
class NormalizeStatusTest {

    /** The values listed by the constraint, wherever it was last redefined. */
    private static final Pattern CONSTRAINT = Pattern.compile(
            "ADD\\s+CONSTRAINT\\s+ck_fetched_jobs_normalize_status\\s+CHECK\\s*\\(\\s*normalize_status\\s+IN\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("Every status is a value the check constraint allows, and the constraint invents none")
    void testConstraintMatchesTheEnum() throws IOException {
        String sql = latestConstraint().orElseThrow(
                () -> new AssertionError("no migration defines ck_fetched_jobs_normalize_status"));
        List<String> allowed = Arrays.stream(sql.split(","))
                .map(value -> value.trim().replace("'", ""))
                .sorted()
                .toList();
        List<String> declared = Arrays.stream(NormalizeStatus.values())
                .map(Enum::name)
                .sorted()
                .toList();

        assertEquals(declared, allowed,
                "NormalizeStatus and ck_fetched_jobs_normalize_status have drifted; "
                        + "add a migration redefining the constraint");
    }

    /**
     * @return the value list of the constraint as the highest-numbered migration
     *         that defines it leaves it, since a later one may redefine it
     */
    private Optional<String> latestConstraint() throws IOException {
        Path migrations = new ClassPathResource("db/migration").getFile().toPath();
        try (Stream<Path> files = Files.list(migrations)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparingInt(NormalizeStatusTest::version))
                    .map(NormalizeStatusTest::constraintIn)
                    .flatMap(Optional::stream)
                    .reduce((earlier, later) -> later);
        }
    }

    /** @return the version number of a Flyway migration file, for ordering */
    private static int version(Path file) {
        Matcher number = Pattern.compile("^V(\\d+)__").matcher(file.getFileName().toString());
        return number.find() ? Integer.parseInt(number.group(1)) : Integer.MAX_VALUE;
    }

    private static Optional<String> constraintIn(Path file) {
        String sql;
        try {
            sql = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + file, e);
        }
        Matcher match = CONSTRAINT.matcher(sql);
        String last = null;
        while (match.find()) {
            last = match.group(1);
        }
        return Optional.ofNullable(last);
    }
}
