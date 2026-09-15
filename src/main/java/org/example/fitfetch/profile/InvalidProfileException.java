package org.example.fitfetch.profile;

import java.nio.file.Path;
import java.util.List;

/**
 * A profile file that cannot be used, with every problem found in it, so they
 * can all be fixed in one go.
 */
public class InvalidProfileException extends RuntimeException {

    private final List<String> problems;

    /**
     * @param source   the file
     * @param problems what is wrong with it, one per entry
     */
    public InvalidProfileException(Path source, List<String> problems) {
        super("Profile " + source + " is invalid:\n  - " + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

    /** @return what is wrong with the file, one per entry */
    public List<String> problems() {
        return problems;
    }
}
