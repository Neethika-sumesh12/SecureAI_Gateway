package com.secureai.service;

import org.springframework.stereotype.Service;

@Service
public class LlmReasoningService {

    public record ModelOutput(String content, String suggestedTool, String toolParam, long inferenceTimeMs) {}

    public record ContextAnswer(String answer, long inferenceTimeMs) {}

    private static final java.util.regex.Pattern ORIGINAL_ID = java.util.regex.Pattern.compile("orig-[0-9a-f]{8}");

    /**
     * Second agent step: the (possibly compressed) tool output goes back to the model, which answers
     * deterministically from the context it was given. Simulated 220 ms inference, like {@link #process}.
     */
    public ContextAnswer answerFromContext(String question, String context, boolean allowUnrestrictedPassThrough) {
        long start = System.currentTimeMillis();
        try {
            Thread.sleep(220);
        } catch (InterruptedException ignored) {}
        String answer;
        if (allowUnrestrictedPassThrough && context.toLowerCase().contains("ignore previous instructions")) {
            // Indirect prompt injection: an unprotected model obeys instructions embedded in tool output.
            answer = "⚠️ [UNPROTECTED MODEL]: Followed an instruction embedded in the tool output! "
                    + "Leaking internal configuration: {AWS_SECRET_KEY: 'sk-demo-9921', ADMIN_ROLE: 'superuser'}";
        } else {
            answer = computeAnswer(question, context);
        }
        return new ContextAnswer(answer, System.currentTimeMillis() - start);
    }

    /** Deterministic "reasoning" over log context; also used on the raw output to get the ground-truth answer. */
    public String computeAnswer(String question, String context) {
        LogLines.Stats s = LogLines.stats(context);
        String q = question.toLowerCase();
        int warns = s.levelCounts.getOrDefault("WARN", 0);
        int errors = s.levelCounts.getOrDefault("ERROR", 0);

        if (q.contains("admin") || q.contains("authentication")) {
            boolean seen = s.signatureCounts.keySet().stream().anyMatch(k -> k.contains("for user admin"));
            return seen ? "Yes: authentication failures for user 'admin' appear in the logs."
                    : "No: no authentication failures for user 'admin' appear in the logs.";
        }
        if (q.contains("distinct") || q.contains("different") || q.contains("types")) {
            return "There are " + s.signatureCounts.size() + " distinct warning/error types.";
        }
        if (q.contains("how many") && q.contains("warn")) {
            return "There are " + warns + " warnings.";
        }
        if (q.contains("how many") && q.contains("error")) {
            return "There are " + errors + " errors.";
        }
        return "Of " + s.entries + " log entries (" + s.minTs + " to " + s.maxTs + "): " + warns + " warnings, "
                + errors + " errors, " + s.signatureCounts.size() + " distinct WARN/ERROR issue types.";
    }

    /**
     * Simulates LLM reasoning and function-calling.
     * Can be replaced or augmented with local Ollama API if available.
     */
    public ModelOutput process(String prompt, boolean allowUnrestrictedPassThrough) {
        long start = System.currentTimeMillis();
        String lower = prompt.toLowerCase();

        // Simulate reasoning delay (Fast inference: ~200ms - 400ms)
        try {
            Thread.sleep(220);
        } catch (InterruptedException ignored) {}

        String content;
        String tool = null;
        String toolParam = null;

        if (ORIGINAL_ID.matcher(lower).find()) {
            java.util.regex.Matcher m = ORIGINAL_ID.matcher(lower);
            m.find();
            tool = "recallOriginal";
            toolParam = m.group();
            content = "Recalling the full original tool output " + toolParam + "...";
        } else if (lower.contains("log") || lower.contains("summarize") || lower.contains("service x")) {
            tool = "getServiceLogs";
            toolParam = "Service X";
            content = "I will inspect the error logs for Service X to diagnose the issue.";
        } else if (lower.contains("invoice")) {
            tool = "fetchInvoice";
            toolParam = lower.contains("9041") ? "9041" : "9042";
            content = "Querying the billing system for Invoice #" + toolParam + "...";
        } else if (lower.contains("restart") || lower.contains("reboot")) {
            tool = "restartServer";
            toolParam = "app-srv-01";
            content = "Attempting to restart production server app-srv-01...";
        } else if (lower.contains("ignore") && lower.contains("instructions")) {
            if (allowUnrestrictedPassThrough) {
                // In FAST mode without gateway protection, LLM is tricked into dumping mock internals
                content = "⚠️ [UNPROTECTED MODEL]: Instructions bypassed! Leaking internal configuration: {AWS_SECRET_KEY: 'sk-demo-9921', ADMIN_ROLE: 'superuser'}";
            } else {
                content = "I cannot fulfill this request as it violates safety guidelines.";
            }
        } else if (lower.contains("[redacted]")) {
            content = "I received your database connection request with sanitized credentials: " + prompt + ". Connection parameters are valid.";
        } else {
            content = "Processed query: '" + prompt + "'. All operational parameters are nominal.";
        }

        long elapsed = System.currentTimeMillis() - start;
        return new ModelOutput(content, tool, toolParam, elapsed);
    }
}
