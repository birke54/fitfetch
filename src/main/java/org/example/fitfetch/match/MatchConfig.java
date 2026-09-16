package org.example.fitfetch.match;

import org.example.fitfetch.skills.SkillCanonicalizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires match scoring. */
@Configuration
public class MatchConfig {

    /**
     * @param skills the same table normalization and the profile go through
     * @return the scoring rules
     */
    @Bean
    public MatchScorer matchScorer(SkillCanonicalizer skills) {
        return new MatchScorer(skills);
    }
}
