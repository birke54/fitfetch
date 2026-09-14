package org.example.fitfetch.utilities;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;

/**
 * Turns a job description as a board publishes it into line-structured plain
 * text for the model.
 *
 * <p>Boards send descriptions as plain text, as HTML, or as HTML escaped a second
 * time ({@code &lt;p&gt;}). Markup costs tokens and carries no requirements, but
 * the block structure does carry meaning &mdash; one bullet is one requirement
 * &mdash; so block boundaries are kept as newlines while tags are dropped.
 *
 * <p>This is a stateless holder; all members are static.
 */
public final class JdPreProcess {

    /** Block-level tags after which a newline is forced to preserve structure. */
    private static final String BLOCK_SELECTOR =
            "p, li, div, ul, ol, h1, h2, h3, h4, h5, h6, tr, section, br, header, footer";

    private JdPreProcess() {
    }

    /**
     * @param raw the job description as received: plain text, HTML or escaped
     *            HTML
     * @return line-structured plain text with entities decoded and whitespace
     *         normalized; empty for {@code null} or blank input
     */
    public static String toPlainText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        String working = raw;

        // 1. Double-escaped input: decode &lt;p&gt; to <p> so jsoup sees real
        //    tags. Harmless on text that is already plain.
        if (working.contains("&lt;") || working.contains("&gt;") || working.contains("&amp;")) {
            working = Parser.unescapeEntities(working, false);
        }

        // 2. Plain text needs only its whitespace normalized.
        if (!looksLikeHtml(working)) {
            return normalizeWhitespace(working);
        }

        // 3. Parse and force block boundaries to newlines. Document.text()
        //    collapses all whitespace, newlines included, so the appended markers
        //    would not survive it; wholeText() keeps them.
        Document doc = Jsoup.parse(working);
        doc.outputSettings(new Document.OutputSettings().prettyPrint(false));
        doc.select(BLOCK_SELECTOR).forEach(element -> element.appendText("\n"));

        return normalizeWhitespace(doc.wholeText());
    }

    private static boolean looksLikeHtml(String s) {
        // Cheap heuristic: a tag-shaped substring.
        return s.indexOf('<') >= 0 && s.indexOf('>') > s.indexOf('<');
    }

    /**
     * Collapses runs of spaces and tabs to one space and runs of blank lines to
     * one newline, leaving single newlines (the block boundaries) intact.
     */
    private static String normalizeWhitespace(String s) {
        String out = s.replace('\u00a0', ' ')   // non-breaking space
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{2,}", "\n");
        return out.strip();
    }
}
