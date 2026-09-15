package org.example.fitfetch.profile;

import org.example.fitfetch.skills.SkillCanonicalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProfileSourceTest {

    @TempDir
    Path dir;

    private final ProfileLoader loader = new ProfileLoader(SkillCanonicalizer.none());

    @Test
    @DisplayName("No path configured leaves matching off rather than failing")
    void testNoPath() {
        assertTrue(ProfileSource.load("", loader).current().isEmpty());
        assertTrue(ProfileSource.load(null, loader).current().isEmpty());
    }

    @Test
    @DisplayName("No file at the path leaves matching off, as when the mounted directory is empty")
    void testMissingFile() {
        assertTrue(ProfileSource.load(dir.resolve("profile.yaml").toString(), loader).current().isEmpty());
        assertTrue(ProfileSource.load(dir.toString(), loader).current().isEmpty(), "a directory is not a profile");
    }

    @Test
    @DisplayName("A file that is there but invalid stops startup rather than being ignored")
    void testInvalidFileFails() throws IOException {
        Path file = Files.writeString(dir.resolve("profile.yaml"), "summary: {}\n");

        assertThrows(InvalidProfileException.class, () -> ProfileSource.load(file.toString(), loader));
    }

    @Test
    @DisplayName("A valid file is loaded")
    void testLoads() {
        assertTrue(ProfileSource.load(Path.of("profile", "profile.example.yaml").toString(), loader)
                .current().isPresent());
    }
}
