package org.example.fitfetch.location;

/**
 * The granularity of a location specifier, which is what decides whether it can
 * be geocoded to a useful point or only to a centroid.
 *
 * <p>The distinction carries real weight: a {@link #COUNTRY} specifier geocodes
 * to a national centroid that is almost never near anyone &mdash; Canada's
 * falls in Nunavut &mdash; which is exactly why country-scoped remote roles are
 * either redirected to the search origin or left to fall outside the radius.
 */
public enum SpecifierType {

    /** A street address. */
    ADDRESS,

    /** A city or town. */
    CITY,

    /** A first-level subdivision: a US state, a Canadian province. */
    STATE,

    /** A sovereign country. */
    COUNTRY,

    /** A multi-country grouping such as EMEA, APAC or North America. */
    MACRO_REGION
}
