package org.example.fitfetch;

import org.example.fitfetch.utilities.TitleFilter;
import org.junit.jupiter.api.Test;

public class TitleFilterTest {

    @Test
    void titleFilterAllowedTest() {
        assert TitleFilter.keep("Senior Support Engineer");
        assert TitleFilter.keep("Cloud infrastructure Engineer");
        assert TitleFilter.keep("Backend Software Engineer");
        assert TitleFilter.keep("Systems Development Engineer");
        assert TitleFilter.keep("Application Software Engineer");
        assert TitleFilter.keep("Senior Backend Engineer");
        assert TitleFilter.keep("Senior Software Engineer");
        assert TitleFilter.keep("Software Engineer General");
        assert TitleFilter.keep("Senior Java Developer - Enterprise API Integration");
        assert TitleFilter.keep("Software Engineer, Market Data");
        assert TitleFilter.keep("Software Engineer, Infrastructure and Platform");
        assert TitleFilter.keep("Backend Engineer (Typescript)");
        assert TitleFilter.keep("Senior Full-Stack Engineer");
        assert TitleFilter.keep("Senior SRE");
        assert TitleFilter.keep("Site Reliability Engineer II");
        assert TitleFilter.keep("Workday Integration Engineer");
        assert TitleFilter.keep("Dev Engineer I-II");
        assert TitleFilter.keep("Senior Software Developer - Sales Center Engineering");
    }

    @Test
    void titleFilterNotAllowedTakesPrecedence() {
        assert !TitleFilter.keep("Mobile Engineer (iOS & Android)");
        assert !TitleFilter.keep("Software Engineer (iOS & Android)");
        assert !TitleFilter.keep("Front End Engineer");
        assert !TitleFilter.keep("Frontend / Full-Stack Engineer");
        assert !TitleFilter.keep("Staff Software Engineer - Front End");
    }
}
