package org.example.fitfetch.match;

import org.example.fitfetch.normalize.Degree;
import org.example.fitfetch.normalize.EmploymentType;
import org.example.fitfetch.normalize.HardRequirements;
import org.example.fitfetch.normalize.NormalizedData;
import org.example.fitfetch.normalize.Seniority;
import org.example.fitfetch.normalize.Signal;
import org.example.fitfetch.normalize.SignalClassification;
import org.example.fitfetch.normalize.Sponsorship;
import org.example.fitfetch.normalize.Track;
import org.example.fitfetch.profile.CandidateProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.example.fitfetch.match.MatchFixtures.requirements;
import static org.junit.jupiter.api.Assertions.*;

class GatesTest {

    private static final Signal SIGNAL = new Signal(SignalClassification.REQUIRED_SKILL, "Knows Java.");

    private static final CandidateProfile.Summary IC_BACHELORS = new CandidateProfile.Summary(
            8, Seniority.SENIOR, Track.IC, Degree.BACHELORS, List.of("CKA"), false, true);

    private static CandidateProfile profile(CandidateProfile.Preferences preferences) {
        return MatchFixtures.profile(IC_BACHELORS, preferences, List.of(), "one");
    }

    private static CandidateProfile anyPreferences() {
        return profile(CandidateProfile.Preferences.ANY);
    }

    private static NormalizedData job(HardRequirements requirements) {
        return MatchFixtures.job(Seniority.SENIOR, 0, requirements, List.of(), SIGNAL);
    }

    @Test
    @DisplayName("A job requiring nothing the profile lacks passes every gate")
    void testPasses() {
        assertEquals(List.of(), Gates.failures(job(HardRequirements.NONE), anyPreferences()));
    }

    @Test
    @DisplayName("A manager role fails an individual contributor profile")
    void testTrack() {
        NormalizedData manager = new NormalizedData(Seniority.SENIOR, Track.MANAGER, EmploymentType.FULL_TIME, 0,
                HardRequirements.NONE, List.of(), List.of(SIGNAL));

        assertEquals(List.of("a people-manager role, and the profile is an individual contributor"),
                Gates.failures(manager, anyPreferences()));
    }

    @Test
    @DisplayName("An employment type the profile does not accept fails; an unstated one passes")
    void testEmploymentType() {
        CandidateProfile fullTimeOnly = profile(new CandidateProfile.Preferences(
                List.of(EmploymentType.FULL_TIME), true, true, List.of(), List.of()));
        NormalizedData contract = new NormalizedData(Seniority.SENIOR, Track.IC, EmploymentType.CONTRACT, 0,
                HardRequirements.NONE, List.of(), List.of(SIGNAL));
        NormalizedData unstated = new NormalizedData(Seniority.SENIOR, Track.IC, EmploymentType.UNSTATED, 0,
                HardRequirements.NONE, List.of(), List.of(SIGNAL));

        assertEquals(List.of("offered as contract, which the profile does not accept"),
                Gates.failures(contract, fullTimeOnly));
        assertEquals(List.of(), Gates.failures(unstated, fullTimeOnly));
        assertEquals(List.of(), Gates.failures(contract, anyPreferences()), "no preference accepts any");
    }

    @Test
    @DisplayName("Clearance, sponsorship, degree and certifications the profile does not meet each fail")
    void testHardRequirements() {
        HardRequirements everything = requirements(Degree.MASTERS, true, Sponsorship.NO,
                List.of("CKA", "AWS Certified Solutions Architect"), false, false);

        assertEquals(List.of(
                "requires a security clearance",
                "will not sponsor a visa",
                "requires a masters degree",
                "requires certifications not held: AWS Certified Solutions Architect"),
                Gates.failures(job(everything), anyPreferences()));
    }

    @Test
    @DisplayName("A degree at or below the profile's passes, and certifications match ignoring case")
    void testDegreeAndCertificationsPass() {
        HardRequirements met = requirements(Degree.BACHELORS, false, Sponsorship.UNSTATED, List.of("cka"),
                false, false);

        assertEquals(List.of(), Gates.failures(job(met), anyPreferences()));
    }

    @Test
    @DisplayName("Unstated sponsorship passes a profile that needs it; only an explicit no fails")
    void testUnstatedSponsorship() {
        assertEquals(List.of(), Gates.failures(job(HardRequirements.NONE), anyPreferences()));
    }

    @Test
    @DisplayName("Travel and on-call fail only a profile that has ruled them out")
    void testTravelAndOnCall() {
        HardRequirements travelAndOnCall = requirements(Degree.NONE, false, Sponsorship.UNSTATED, List.of(),
                true, true);
        CandidateProfile neither = profile(new CandidateProfile.Preferences(List.of(), false, false,
                List.of(), List.of()));

        assertEquals(List.of("requires travel", "includes an on-call rotation"),
                Gates.failures(job(travelAndOnCall), neither));
        assertEquals(List.of(), Gates.failures(job(travelAndOnCall), anyPreferences()));
    }
}
