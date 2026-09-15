package org.example.fitfetch.profile;

import org.example.fitfetch.skills.SkillCanonicalizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the candidate profile. */
@Configuration
public class ProfileConfig {

    /**
     * @param skills maps profile skills to the canonical names jobs use
     * @return the loader
     */
    @Bean
    public ProfileLoader profileLoader(SkillCanonicalizer skills) {
        return new ProfileLoader(skills);
    }

    /**
     * Loaded at startup, so an invalid profile stops the app with every problem
     * listed rather than surfacing when the first match is scored.
     *
     * @param path   {@code app.profile.path}, a file path; blank for none
     * @param loader reads and checks the file
     * @return the profile source
     */
    @Bean
    public ProfileSource profileSource(@Value("${app.profile.path:}") String path, ProfileLoader loader) {
        return ProfileSource.load(path, loader);
    }
}
