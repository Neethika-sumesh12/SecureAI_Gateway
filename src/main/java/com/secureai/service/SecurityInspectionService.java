package com.secureai.service;

import com.secureai.model.RiskLevel;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SecurityInspectionService {

    // Regex / Keywords for Prompt Injection (OWASP LLM01)
    private static final List<Pattern> INJECTION_PATTERNS = Arrays.asList(
            Pattern.compile("(?i)ignore (all )?(previous|prior|above) (instructions|directions|rules)"),
            Pattern.compile("(?i)reveal (system|confidential|secret|internal) (prompt|instructions|credentials|tokens)"),
            Pattern.compile("(?i)dump (all )?(api keys|passwords|credentials|database)"),
            Pattern.compile("(?i)you are now in (god|admin|unrestricted|developer) mode"),
            Pattern.compile("(?i)bypass (security|policy|restrictions)")
    );

    // Regex for Sensitive Secrets & Credentials Leakage Detection
    private static final Pattern SECRET_PATTERN = Pattern.compile("(?i)(password|passwd|secret|api[_-]?key|bearer)\\s*[:=]\\s*([a-zA-Z0-9!@#$%^&*()_+\\-=]+)");
    private static final Pattern DB_CONN_STRING_PATTERN = Pattern.compile("(?i)db://([a-zA-Z0-9_-]+):([^@\\s]+)@");

    // Heuristics for Excessive Agency / Privileged Actions (OWASP LLM06)
    private static final List<String> HIGH_RISK_ACTION_KEYWORDS = Arrays.asList(
            "restart server", "reboot cluster", "delete database", "drop table", "shutdown", "grant admin", "modify permissions", "format disk"
    );

    public record RiskAssessment(
            RiskLevel riskLevel,
            int riskScore,
            String detectedThreat,
            boolean isPromptInjection,
            boolean containsSecrets,
            boolean requiresApproval,
            String sanitizedPrompt
    ) {}

    // ------------------------------------------------------------------
    // Tool-output inspection (indirect prompt injection + secret leakage)
    // ------------------------------------------------------------------

    /** Same injection signatures as the prompt scan, extended to swallow the rest of the line / JSON string. */
    private static final List<Pattern> TOOL_OUTPUT_INJECTION_PATTERNS = buildToolOutputPatterns();

    private static List<Pattern> buildToolOutputPatterns() {
        List<Pattern> out = new ArrayList<>();
        for (Pattern p : INJECTION_PATTERNS) {
            out.add(Pattern.compile(p.pattern() + "[^\"\\r\\n]*"));
        }
        out.add(Pattern.compile("(?i)note to (the )?(ai|assistant|model)[^\"\\r\\n]*"));
        out.add(Pattern.compile("(?i)disregard (all )?(previous|prior|above)[^\"\\r\\n]*"));
        return out;
    }

    public static final String QUARANTINE_MARKER = "[QUARANTINED: suspected indirect prompt injection]";

    public record ToolOutputInspection(String sanitized, int injectionsQuarantined, int secretsRedacted) {}

    /**
     * Inspects tool output before it can reach the model or the original store. Injection phrases are replaced
     * in place (structure of the log line is preserved); secrets are masked.
     */
    public ToolOutputInspection inspectToolOutput(String output) {
        if (output == null || output.isEmpty()) {
            return new ToolOutputInspection(output, 0, 0);
        }
        String text = output;
        int injections = 0;
        for (Pattern p : TOOL_OUTPUT_INJECTION_PATTERNS) {
            Matcher m = p.matcher(text);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                injections++;
                m.appendReplacement(sb, Matcher.quoteReplacement(QUARANTINE_MARKER));
            }
            m.appendTail(sb);
            text = sb.toString();
        }
        int secrets = countMatches(SECRET_PATTERN, text) + countMatches(DB_CONN_STRING_PATTERN, text);
        return new ToolOutputInspection(redactSecrets(text), injections, secrets);
    }

    /** Idempotent secret masking, shared by prompt scanning, original storage and recall. */
    public String redactSecrets(String text) {
        if (text == null) {
            return null;
        }
        String out = SECRET_PATTERN.matcher(text).replaceAll("$1=[REDACTED]");
        return DB_CONN_STRING_PATTERN.matcher(out).replaceAll("db://$1:[REDACTED]@");
    }

    private static int countMatches(Pattern p, String text) {
        Matcher m = p.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    public RiskAssessment inspect(String prompt, String role, boolean simulatedApproval) {
        if (prompt == null || prompt.trim().isEmpty()) {
            return new RiskAssessment(RiskLevel.LOW, 0, "None", false, false, false, prompt);
        }

        int score = 0;
        String threat = "None";
        boolean promptInjection = false;
        boolean containsSecrets = false;
        boolean requiresApproval = false;
        String sanitizedPrompt = prompt;

        // 1. Check Prompt Injection
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(prompt).find()) {
                score += 85;
                threat = "OWASP LLM01: Prompt Injection Attempt";
                promptInjection = true;
                break;
            }
        }

        // 2. Check Sensitive Secrets & Mask them
        Matcher secretMatcher = SECRET_PATTERN.matcher(sanitizedPrompt);
        if (secretMatcher.find()) {
            score += 40;
            if ("None".equals(threat)) {
                threat = "Sensitive Credential / Secret Exposure";
            }
            containsSecrets = true;
            sanitizedPrompt = secretMatcher.replaceAll("$1=[REDACTED]");
        }

        Matcher dbMatcher = DB_CONN_STRING_PATTERN.matcher(sanitizedPrompt);
        if (dbMatcher.find()) {
            score += 40;
            if ("None".equals(threat)) {
                threat = "Sensitive Database Connection String Exposure";
            }
            containsSecrets = true;
            sanitizedPrompt = dbMatcher.replaceAll("db://$1:[REDACTED]@");
        }

        // 3. Check High Risk / Destructive Actions (Excessive Agency)
        String lowerPrompt = prompt.toLowerCase();
        for (String action : HIGH_RISK_ACTION_KEYWORDS) {
            if (lowerPrompt.contains(action)) {
                score += 60;
                threat = "OWASP LLM06: High-Impact / Destructive Action";
                requiresApproval = true;
                break;
            }
        }

        // Risk Level mapping
        RiskLevel level;
        if (score >= 80) {
            level = RiskLevel.CRITICAL;
        } else if (score >= 50) {
            level = RiskLevel.HIGH;
        } else if (score >= 20) {
            level = RiskLevel.MEDIUM;
        } else {
            level = RiskLevel.LOW;
        }

        return new RiskAssessment(
                level,
                Math.min(100, score),
                threat,
                promptInjection,
                containsSecrets,
                requiresApproval,
                sanitizedPrompt
        );
    }
}
