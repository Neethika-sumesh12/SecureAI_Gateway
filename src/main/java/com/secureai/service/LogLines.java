package com.secureai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared log parsing used by the compressor, the preservation validator and the mock LLM.
 *
 * Formats (all parsed line by line, so compressed output stays readable by the same parser):
 *  - LOG:    "[ts] [LEVEL] message" and compressed groups "[ts..ts] [LEVEL] (xN) message"
 *  - JSON:   one object per line inside a JSON array: {"ts"|"firstTs"/"lastTs", "level", "msg", optional "count"}
 *  - logfmt: ts=... level=... msg="..."   (parsed for ground truth, but NOT a recognized/verifiable format)
 * A trailing " (+N similar)" suffix (added by the naive compressor) adds N to the entry count.
 * Lines starting with '#' are gateway comments and are ignored everywhere.
 */
final class LogLines {

    private static final String TS = "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}";
    private static final Pattern LINE = Pattern.compile(
            "^\\[(" + TS + ")(?:\\.\\.(" + TS + "))?\\] \\[([A-Z]+)\\] (?:\\(x(\\d+)\\) )?(.*)$");
    private static final Pattern LOGFMT = Pattern.compile("^ts=(\\S+) level=(\\w+) msg=\"(.*)\"$");
    private static final Pattern SIMILAR = Pattern.compile("\\s*\\(\\+(\\d+) similar\\)$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Header line emitted by the getServiceLogs tool; not a log entry. */
    static final String TOOL_HEADER_PREFIX = "Logs for ";

    private LogLines() {}

    enum Format { LOG, JSON, UNKNOWN }

    record Entry(String firstTs, String lastTs, String level, int count, String message) {}

    static final class Stats {
        final Map<String, Integer> levelCounts = new TreeMap<>();
        /** WARN/ERROR signatures ("LEVEL|template") with total counts, in order of first appearance. */
        final Map<String, Integer> signatureCounts = new LinkedHashMap<>();
        String minTs;
        String maxTs;
        int entries;

        void add(String firstTs, String lastTs, String level, int count, String message) {
            entries += count;
            levelCounts.merge(level, count, Integer::sum);
            if (isIssue(level)) {
                signatureCounts.merge(signature(level, message), count, Integer::sum);
            }
            if (minTs == null || firstTs.compareTo(minTs) < 0) {
                minTs = firstTs;
            }
            if (maxTs == null || lastTs.compareTo(maxTs) > 0) {
                maxTs = lastTs;
            }
        }
    }

    static String signature(String level, String message) {
        return level + "|" + template(message);
    }

    static Entry parse(String line) {
        Matcher m = LINE.matcher(line);
        if (!m.matches()) {
            return null;
        }
        String first = m.group(1);
        String last = m.group(2) != null ? m.group(2) : first;
        int count = m.group(4) != null ? Integer.parseInt(m.group(4)) : 1;
        return new Entry(first, last, m.group(3), count, m.group(5));
    }

    static String template(String message) {
        return message.replaceAll("\\d+", "<N>").trim();
    }

    static boolean isContinuation(String line) {
        return !line.isEmpty() && (Character.isWhitespace(line.charAt(0)) || line.startsWith("Caused by"));
    }

    static boolean isIssue(String level) {
        return "WARN".equals(level) || "ERROR".equals(level);
    }

    private static boolean isMeta(String line) {
        return line.startsWith("#") || line.startsWith(TOOL_HEADER_PREFIX);
    }

    /** Returns the parsed JSON array (ignoring gateway comments and the tool header), or null. */
    static JsonNode jsonArray(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\\R")) {
            if (!isMeta(line)) {
                sb.append(line).append('\n');
            }
        }
        String t = sb.toString().trim();
        if (!t.startsWith("[")) {
            return null;
        }
        try {
            JsonNode n = MAPPER.readTree(t);
            return n != null && n.isArray() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    static Format detect(String text) {
        if (text == null || text.isBlank()) {
            return Format.UNKNOWN;
        }
        if (jsonArray(text) != null) {
            return Format.JSON;
        }
        return recognition(text) >= 0.9 ? Format.LOG : Format.UNKNOWN;
    }

    /** Fraction of candidate lines (non-blank, non-meta, non-continuation) that look like log entries. */
    static double recognition(String text) {
        int considered = 0;
        int matched = 0;
        for (String line : text.split("\\R")) {
            if (line.isBlank() || isMeta(line) || isContinuation(line)) {
                continue;
            }
            considered++;
            if (parse(line) != null) {
                matched++;
            }
        }
        return considered == 0 ? 0 : (double) matched / considered;
    }

    /** Statistics for any supported format (ground truth for validation and the mock LLM). */
    static Stats stats(String text) {
        Stats s = new Stats();
        for (String rawLine : text.split("\\R")) {
            if (rawLine.isBlank() || isMeta(rawLine)) {
                continue;
            }
            String line = rawLine;
            int extra = 0;
            Matcher sm = SIMILAR.matcher(line);
            if (sm.find()) {
                extra = Integer.parseInt(sm.group(1));
                line = line.substring(0, sm.start());
            }

            Entry e = parse(line);
            if (e != null) {
                s.add(e.firstTs(), e.lastTs(), e.level(), e.count() + extra, e.message());
                continue;
            }
            Matcher lf = LOGFMT.matcher(line);
            if (lf.matches()) {
                s.add(lf.group(1), lf.group(1), lf.group(2).toUpperCase(), 1 + extra, lf.group(3));
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("{")) {
                if (trimmed.endsWith(",")) {
                    trimmed = trimmed.substring(0, trimmed.length() - 1);
                }
                try {
                    JsonNode n = MAPPER.readTree(trimmed);
                    String level = n.path("level").asText("").toUpperCase();
                    String first = n.has("firstTs") ? n.get("firstTs").asText() : n.path("ts").asText("");
                    String last = n.has("lastTs") ? n.get("lastTs").asText() : first;
                    if (!level.isEmpty() && !first.isEmpty()) {
                        s.add(first, last, level, n.path("count").asInt(1) + extra, n.path("msg").asText(""));
                    }
                } catch (Exception ignored) {
                    // not a JSON entry line
                }
            }
        }
        return s;
    }
}
