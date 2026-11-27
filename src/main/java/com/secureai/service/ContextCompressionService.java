package com.secureai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deterministic, rule-based context compressor (no ML).
 *
 * Two strategies:
 *  - compressNaive:   what a generic "squeeze repeated lines" tool does. Clusters lines by their first
 *                     {@value #NAIVE_PREFIX_TOKENS} tokens (timestamp and digits masked) and keeps the first line of
 *                     each cluster plus a "(+N similar)" count. Fast, format-agnostic, and lossy.
 *  - compressGuarded: structure-aware. Groups by (level, message template) so every distinct signature survives
 *                     with an exact count and time range; collapses stack traces to head + tail frames.
 *                     Formats it does not recognize fall back to the naive strategy (the validator then rejects it).
 */
@Service
public class ContextCompressionService {

    static final int NAIVE_PREFIX_TOKENS = 4;
    private static final int TRACE_HEAD = 2;
    private static final int TRACE_TAIL = 1;

    private static final Pattern TS_ANYWHERE =
            Pattern.compile("\\[?\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\]?");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------------ naive

    public String compressNaive(String raw) {
        Map<String, String> firstLine = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String line : raw.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String key = naiveKey(line);
            firstLine.putIfAbsent(key, line);
            counts.merge(key, 1, Integer::sum);
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : firstLine.entrySet()) {
            out.append(e.getValue());
            int n = counts.get(e.getKey());
            if (n > 1) {
                out.append(" (+").append(n - 1).append(" similar)");
            }
            out.append('\n');
        }
        return out.toString();
    }

    private String naiveKey(String line) {
        String masked = TS_ANYWHERE.matcher(line).replaceAll("").replaceAll("\\d+", "<N>").trim();
        String[] tokens = masked.split("\\s+");
        int n = Math.min(NAIVE_PREFIX_TOKENS, tokens.length);
        return String.join(" ", java.util.Arrays.copyOfRange(tokens, 0, n));
    }

    // ---------------------------------------------------------------- guarded

    public String compressGuarded(String raw) {
        return switch (LogLines.detect(raw)) {
            case LOG -> compressLog(raw);
            case JSON -> compressJson(raw);
            case UNKNOWN -> compressNaive(raw);
        };
    }

    private static final class Group {
        String firstTs;
        String lastTs;
        String level;
        String message;
        int count;
        String verbatim; // for lines that are not log entries
        final List<String> trace = new ArrayList<>();
    }

    private String compressLog(String raw) {
        List<String> headers = new ArrayList<>();
        Map<String, Group> groups = new LinkedHashMap<>();
        Group current = null;
        boolean currentIsFirst = false;

        for (String line : raw.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith(LogLines.TOOL_HEADER_PREFIX)) {
                headers.add(line);
                continue;
            }
            if (LogLines.isContinuation(line)) {
                if (current != null && currentIsFirst) {
                    current.trace.add(line);
                }
                continue;
            }
            LogLines.Entry e = LogLines.parse(line);
            if (e == null) {
                Group g = new Group();
                g.verbatim = line;
                groups.put("RAW|" + groups.size() + "|" + line, g);
                current = null;
                continue;
            }
            String key = LogLines.signature(e.level(), e.message());
            Group g = groups.get(key);
            if (g == null) {
                g = new Group();
                g.firstTs = e.firstTs();
                g.lastTs = e.lastTs();
                g.level = e.level();
                g.message = e.message();
                groups.put(key, g);
                currentIsFirst = true;
            } else {
                if (e.firstTs().compareTo(g.firstTs) < 0) {
                    g.firstTs = e.firstTs();
                }
                if (e.lastTs().compareTo(g.lastTs) > 0) {
                    g.lastTs = e.lastTs();
                }
                currentIsFirst = false;
            }
            g.count += e.count();
            current = g;
        }

        StringBuilder out = new StringBuilder();
        headers.forEach(h -> out.append(h).append('\n'));
        for (Group g : groups.values()) {
            if (g.verbatim != null) {
                out.append(g.verbatim).append('\n');
                continue;
            }
            String ts = g.firstTs.equals(g.lastTs) ? g.firstTs : g.firstTs + ".." + g.lastTs;
            out.append('[').append(ts).append("] [").append(g.level).append("] ");
            if (g.count > 1) {
                out.append("(x").append(g.count).append(") ");
            }
            out.append(g.message).append('\n');
            appendTrace(out, g.trace);
        }
        return out.toString();
    }

    private void appendTrace(StringBuilder out, List<String> trace) {
        if (trace.size() <= TRACE_HEAD + TRACE_TAIL) {
            trace.forEach(t -> out.append(t).append('\n'));
            return;
        }
        for (int i = 0; i < TRACE_HEAD; i++) {
            out.append(trace.get(i)).append('\n');
        }
        out.append("    ... ").append(trace.size() - TRACE_HEAD - TRACE_TAIL).append(" frames collapsed\n");
        out.append(trace.get(trace.size() - 1)).append('\n');
    }

    private String compressJson(String raw) {
        JsonNode arr = LogLines.jsonArray(raw);
        Map<String, ObjectNode> groups = new LinkedHashMap<>();
        List<JsonNode> passthrough = new ArrayList<>();
        for (JsonNode n : arr) {
            String level = n.path("level").asText("").toUpperCase();
            String first = n.has("firstTs") ? n.get("firstTs").asText() : n.path("ts").asText("");
            String last = n.has("lastTs") ? n.get("lastTs").asText() : first;
            if (level.isEmpty() || first.isEmpty()) {
                passthrough.add(n);
                continue;
            }
            String msg = n.path("msg").asText("");
            int count = n.path("count").asInt(1);
            String key = LogLines.signature(level, msg);
            ObjectNode g = groups.get(key);
            if (g == null) {
                g = MAPPER.createObjectNode();
                g.put("firstTs", first);
                g.put("lastTs", last);
                g.put("level", level);
                g.put("msg", msg);
                g.put("count", count);
                groups.put(key, g);
            } else {
                if (last.compareTo(g.get("lastTs").asText()) > 0) {
                    g.put("lastTs", last);
                }
                g.put("count", g.get("count").asInt() + count);
            }
        }
        List<String> lines = new ArrayList<>();
        groups.values().forEach(g -> lines.add(g.toString()));
        passthrough.forEach(p -> lines.add(p.toString()));

        StringBuilder out = new StringBuilder();
        for (String line : raw.split("\\R")) {
            if (line.startsWith(LogLines.TOOL_HEADER_PREFIX)) {
                out.append(line).append('\n');
            }
        }
        out.append("[\n").append(String.join(",\n", lines)).append("\n]\n");
        return out.toString();
    }
}
