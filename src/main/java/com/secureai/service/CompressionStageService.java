package com.secureai.service;

import com.secureai.model.CompressionReport;
import com.secureai.model.GatewayMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Harness-level compression stage: tool result -> inspect -> reduce -> validate -> fall back -> LLM.
 *
 * FAST     naive compression, no tool-output inspection, no validation, original stored as-is (no redaction).
 * STRICT   inspect before and after compression, always compress with the structure-aware compressor, always
 *          validate the preservation contract, fall back to the raw output on any failed check.
 * BALANCED inspect; skip inputs below secureai.compression.min-tokens (default 500; SKIPPED_SMALL); compress;
 *          validate only if the reduction exceeds secureai.compression.aggressive-savings-percent (default 80) or
 *          the format is not recognized. Otherwise the compressor is trusted and the contract is evaluated for
 *          reporting only.
 */
@Service
public class CompressionStageService {

    private final int smallThresholdTokens;
    private final double aggressiveSavingsPercent;

    /** @param context          exactly what is handed to the model
     *  @param referenceContext inspected raw output, used as ground truth for answer checks
     *  @param audit           audit-trail lines describing what the stage did */
    public record StageResult(String context, String referenceContext, CompressionReport report, List<String> audit) {}

    private final ContextCompressionService compressor;
    private final PreservationValidator validator;
    private final OriginalStore originalStore;
    private final SecurityInspectionService inspectionService;

    public CompressionStageService(ContextCompressionService compressor,
                                   PreservationValidator validator,
                                   OriginalStore originalStore,
                                   SecurityInspectionService inspectionService,
                                   @Value("${secureai.compression.min-tokens:500}") int smallThresholdTokens,
                                   @Value("${secureai.compression.aggressive-savings-percent:80.0}") double aggressiveSavingsPercent) {
        this.smallThresholdTokens = smallThresholdTokens;
        this.aggressiveSavingsPercent = aggressiveSavingsPercent;
        this.compressor = compressor;
        this.validator = validator;
        this.originalStore = originalStore;
        this.inspectionService = inspectionService;
    }

    public StageResult process(GatewayMode mode, String username, String toolName, String rawOutput) {
        List<String> audit = new ArrayList<>();
        int injections = 0;
        int secrets = 0;

        // 1. Inspect tool output (indirect prompt injection + secrets). FAST bypasses all gateway inspection.
        String raw = rawOutput;
        if (mode != GatewayMode.FAST) {
            SecurityInspectionService.ToolOutputInspection pre = inspectionService.inspectToolOutput(rawOutput);
            raw = pre.sanitized();
            injections += pre.injectionsQuarantined();
            secrets += pre.secretsRedacted();
            audit.add("Tool output inspected before compression: " + pre.injectionsQuarantined()
                    + " injection line(s) quarantined, " + pre.secretsRedacted() + " secret(s) redacted.");
        } else {
            audit.add("Tool output inspection bypassed (FAST).");
        }

        int rawTokens = TokenEstimator.estimate(raw);

        // 2. Balanced: do not compress small inputs.
        if (mode == GatewayMode.BALANCED && rawTokens < smallThresholdTokens) {
            PreservationValidator.Result r = validator.evaluate(raw, raw);
            audit.add("Compression skipped: " + rawTokens + " tokens < threshold " + smallThresholdTokens + " (SKIPPED_SMALL).");
            return result(raw, raw, "SKIPPED_SMALL", "none", false, rawTokens, rawTokens, r, r, null,
                    injections, secrets, 0, 0, 0, audit);
        }

        // 3. Reduce.
        long t0 = System.nanoTime();
        String compressed = mode == GatewayMode.FAST ? compressor.compressNaive(raw) : compressor.compressGuarded(raw);
        if (mode != GatewayMode.FAST) {
            SecurityInspectionService.ToolOutputInspection post = inspectionService.inspectToolOutput(compressed);
            compressed = post.sanitized();
            injections += post.injectionsQuarantined();
            audit.add("Tool output inspected after compression: " + post.injectionsQuarantined()
                    + " new injection line(s) quarantined (secrets were already masked before compression).");
        }
        double compressionMs = ms(t0);

        int compressedTokens = TokenEstimator.estimate(compressed);
        double savings = savings(rawTokens, compressedTokens);
        String strategy = mode == GatewayMode.FAST ? "naive" : "structure-aware";

        // 4. Validate. The contract is ALWAYS evaluated so it can be reported. Only where `enforce` is true does the
        //    result decide the outcome (validationMs). Where it is false the evaluation is a "shadow check": it records
        //    what would have happened but changes nothing, and its cost is reported separately (shadowCheckMs). A
        //    production Balanced mode would not run it; here it exists to produce the data in the benchmark.
        boolean enforce = switch (mode) {
            case FAST -> false;
            case STRICT -> true;
            case BALANCED -> savings > aggressiveSavingsPercent || LogLines.detect(raw) == LogLines.Format.UNKNOWN;
        };
        long t1 = System.nanoTime();
        PreservationValidator.Result r = validator.evaluate(raw, compressed);
        double checkMs = ms(t1);
        double validationMs = enforce ? checkMs : 0;
        double shadowMs = enforce ? 0 : checkMs;

        if (enforce && !r.passed()) {
            PreservationValidator.Result full = validator.evaluate(raw, raw);
            audit.add("Preservation contract FAILED (" + String.join("; ", r.failures()) + "). Falling back to raw output (FALLBACK_RAW).");
            return result(raw, raw, "FALLBACK_RAW", strategy, true, rawTokens, rawTokens, r, full, null,
                    injections, secrets, compressionMs, validationMs, shadowMs, audit);
        }

        // 5. Keep the original retrievable. FAST stores the output exactly as received (no redaction).
        String originalId = originalStore.store(username, toolName, raw);
        String shadow = r.passed()
                ? "shadow check: contract would have PASSED"
                : "shadow check: contract WOULD HAVE FAILED (" + String.join("; ", r.failures()) + ")";
        if (enforce) {
            audit.add("Preservation contract passed; compressed context accepted (" + rawTokens + " -> " + compressedTokens + " tokens).");
        } else if (mode == GatewayMode.FAST) {
            audit.add("Naive compression applied; contract NOT enforced in FAST (" + rawTokens + " -> " + compressedTokens
                    + " tokens). " + shadow + "; compressed context was sent regardless.");
        } else {
            audit.add("Reduction " + String.format("%.1f", savings) + "% is not aggressive and format is recognized: contract NOT enforced, "
                    + "compressor trusted. " + shadow + "; compressed context was sent regardless.");
        }
        audit.add("Original stored as " + originalId + " (recall requires owner or ROLE_ADMIN"
                + (mode == GatewayMode.FAST ? "; FAST mode bypasses this" : "") + ").");
        return result(compressed, raw, "COMPRESSED", strategy, enforce, rawTokens, compressedTokens, r, r, originalId,
                injections, secrets, compressionMs, validationMs, shadowMs, audit);
    }

    private StageResult result(String context, String reference, String decision, String strategy, boolean validated,
                               int rawTokens, int sentTokens, PreservationValidator.Result checkResult,
                               PreservationValidator.Result contextResult, String originalId,
                               int injections, int secrets, double compressionMs, double validationMs,
                               double shadowMs, List<String> audit) {
        CompressionReport report = new CompressionReport(
                decision, strategy, validated, rawTokens, sentTokens, round(savings(rawTokens, sentTokens)),
                checkResult.checks(), checkResult.failures(),
                contextResult.signaturesTotal(), contextResult.signaturesPreserved(),
                contextResult.needlesTotal(), contextResult.needlesPreserved(),
                originalId, injections, secrets, round(compressionMs), round(validationMs), round(shadowMs),
                preview(context), null, null, null);
        return new StageResult(context, reference, report, audit);
    }

    private static String preview(String text) {
        String[] lines = text.split("\\R");
        String joined = String.join("\n", Arrays.copyOfRange(lines, 0, Math.min(20, lines.length)));
        if (joined.length() > 2500) {
            joined = joined.substring(0, 2500);
        }
        return lines.length > 20 || joined.length() < text.length()
                ? joined + "\n... (" + lines.length + " lines total)" : joined;
    }

    private static double savings(int raw, int sent) {
        return raw == 0 ? 0 : 100.0 * (raw - sent) / raw;
    }

    private static double ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
