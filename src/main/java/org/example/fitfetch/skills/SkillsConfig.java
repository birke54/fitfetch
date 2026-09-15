package org.example.fitfetch.skills;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;

/** Wires the skill alias table shared by normalization and the candidate profile. */
@Configuration
public class SkillsConfig {

    /**
     * @param aliases the alias table ({@code app.skills.aliases}), loaded eagerly
     *                so a malformed or conflicting entry fails startup
     * @return the canonicalizer
     * @throws IOException if the table cannot be read
     */
    @Bean
    public SkillCanonicalizer skillCanonicalizer(@Value("${app.skills.aliases}") Resource aliases) throws IOException {
        return SkillCanonicalizer.load(aliases);
    }
}
