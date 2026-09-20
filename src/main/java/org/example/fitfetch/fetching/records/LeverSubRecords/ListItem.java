package org.example.fitfetch.fetching.records.LeverSubRecords;

/**
 * One entry of a Lever posting's {@code lists} array: a titled block of bullet
 * points, such as "Responsibilities" or "Requirements".
 *
 * <p>These are published text, not decoration. Lever's own editor keeps them
 * out of {@code description}: across 7,351 sampled postings that carry lists,
 * {@code description} contained all of the list content on
 * <strong>none of them</strong>. Dropping them would discard most of what a
 * posting actually says about the job, which is why
 * {@link org.example.fitfetch.fetching.records.LeverJobEntry#content()} renders
 * them back in.
 *
 * @param text    the block's heading, e.g. {@code "Responsibilities"}; may be
 *                {@code null} or blank on a posting that left it unnamed
 * @param content the bullets as a bare run of {@code <li>} elements, with no
 *                enclosing {@code <ul>} &mdash; Lever stores the wrapper
 *                implicitly. There is no plain-text variant of this field
 *                anywhere in the payload, which is a further reason
 *                {@code content()} works in HTML rather than in Lever's plain
 *                renderings. May be {@code null} or blank
 */
public record ListItem(
        String text,
        String content
) {

    /**
     * Renders this block as standalone HTML: an {@code <h3>} heading followed by
     * {@code content} wrapped in the {@code <ul>} Lever leaves off.
     *
     * <p>The wrapper is not cosmetic. {@code content} is a bare {@code <li>} run,
     * and {@code <li>} and {@code <h3>} are both in
     * {@link org.example.fitfetch.utilities.JdPreProcess}'s block selector, so
     * once wrapped each bullet becomes its own line in the text the model reads
     * &mdash; one bullet, one requirement.
     *
     * @return the rendered block, or {@code ""} if there are no bullets to
     *         render; a blank heading is simply omitted rather than emitted as
     *         an empty {@code <h3>}
     */
    public String toHtml() {
        if (content == null || content.isBlank()) {
            return "";
        }
        String heading = (text == null || text.isBlank()) ? "" : "<h3>" + text + "</h3>";
        return heading + "<ul>" + content + "</ul>";
    }
}
