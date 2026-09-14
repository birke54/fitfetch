package org.example.fitfetch.utilities;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JdPreProcessTest {

    @Test
    @DisplayName("HTML keeps one line per block and loses its tags")
    void testHtmlBlocksBecomeLines() {
        String html = "<h2>About the role</h2><p>You will build <b>services</b>.</p>"
                + "<ul><li>5+ years of Java</li><li>Kubernetes a plus</li></ul>";

        assertEquals("About the role\nYou will build services.\n5+ years of Java\nKubernetes a plus",
                JdPreProcess.toPlainText(html));
    }

    @Test
    @DisplayName("Escaped HTML, as some boards send it, is decoded and then treated as HTML")
    void testEscapedHtml() {
        String escaped = "&lt;p&gt;Design APIs&lt;/p&gt;&lt;ul&gt;&lt;li&gt;Go &amp;amp; Rust&lt;/li&gt;&lt;/ul&gt;";

        assertEquals("Design APIs\nGo & Rust", JdPreProcess.toPlainText(escaped));
    }

    @Test
    @DisplayName("Plain text only has its whitespace normalized")
    void testPlainText() {
        String nbsp = String.valueOf((char) 0xA0);
        String plain = "  Build   things" + nbsp + "fast\t\n\n\n  Ship  them  ";

        assertEquals("Build things fast\nShip them", JdPreProcess.toPlainText(plain));
    }

    @Test
    @DisplayName("Missing or blank input is empty, not an error")
    void testBlank() {
        assertEquals("", JdPreProcess.toPlainText(null));
        assertEquals("", JdPreProcess.toPlainText("   "));
        assertEquals("", JdPreProcess.toPlainText("<p> </p>"));
    }
}
