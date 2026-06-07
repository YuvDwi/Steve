package com.steve.ai.llm.react;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildDesignFormatterTest {

    @Test
    void chineseStringsRoundTripAsUtf8() {
        String s = "设计图";
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        assertEquals(9, bytes.length, "设计图 = 3 chars × 3 bytes UTF-8");
        assertEquals(s, new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void localeRootFormatProducesLfNotCrLf() {
        // %n in String.format(Locale.ROOT, ...) on Windows produces \r\n, so the
        // formatter must use literal \n instead.
        String withPercentN = String.format(Locale.ROOT, "a%n b", 1);
        assertTrue(withPercentN.contains("\r"), "demonstrates %n is unsafe on Windows: " + withPercentN);

        String withLiteralN = String.format(Locale.ROOT, "a\n b", 1);
        assertFalse(withLiteralN.contains("\r"), "literal \\n must not introduce CR");
        assertTrue(withLiteralN.endsWith("\n b"));
    }

    @Test
    void headerLiteralContainsExpectedChineseAndNoCarriageReturn() {
        // The exact substring that appeared as mojibake in the log
        String headerFragment = "设计图 #e155251a ==========\n项目: 玩家指令\"房子_1\"";
        byte[] bytes = headerFragment.getBytes(StandardCharsets.UTF_8);
        String roundTripped = new String(bytes, StandardCharsets.UTF_8);
        assertEquals(headerFragment, roundTripped);
        assertFalse(headerFragment.contains("\r"));
        assertTrue(headerFragment.contains("设计图"));
        assertTrue(headerFragment.contains("项目"));
        assertTrue(headerFragment.contains("玩家指令"));
    }
}
