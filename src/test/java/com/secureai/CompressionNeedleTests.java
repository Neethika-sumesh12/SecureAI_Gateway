package com.secureai;

import com.secureai.model.CompressionReport;
import com.secureai.model.GatewayMode;
import com.secureai.model.GatewayRequest;
import com.secureai.model.GatewayResponse;
import com.secureai.service.CompressionStageService;
import com.secureai.service.ContextCompressionService;
import com.secureai.service.GatewayOrchestratorService;
import com.secureai.service.LogWorkloads;
import com.secureai.service.OriginalStore;
import com.secureai.service.PreservationValidator;
import com.secureai.service.TokenEstimator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class CompressionNeedleTests {

    private static final String HEADER = "Logs for Service X:\n";

    @Autowired private CompressionStageService stage;
    @Autowired private ContextCompressionService compressor;
    @Autowired private PreservationValidator validator;
    @Autowired private OriginalStore store;
    @Autowired private GatewayOrchestratorService orchestrator;

    private static String raw(String workload) {
        return HEADER + LogWorkloads.get(workload);
    }

    private CompressionReport run(GatewayMode mode, String workload) {
        return stage.process(mode, "tester", "getServiceLogs", raw(workload)).report();
    }

    private GatewayResponse ask(GatewayMode mode, String workload, String prompt, String user, String role) {
        GatewayRequest req = new GatewayRequest(prompt, mode, user, role);
        req.setWorkload(workload);
        return orchestrator.handleRequest(req);
    }

    // ---------------------------------------------------------------- needles

    @Test
    void largeWorkloadHasFiveNeedlesAndRealRepetition() {
        PreservationValidator.Result r = validator.evaluate(raw("large"), raw("large"));
        assertEquals(5, r.needlesTotal(), "workload must contain exactly 5 rare WARN/ERROR signatures");
        assertTrue(TokenEstimator.estimate(raw("large")) > 20_000);
    }

    @Test
    void strictPreservesAllNeedlesAndPassesContract() {
        CompressionReport r = run(GatewayMode.STRICT, "large");
        assertEquals("COMPRESSED", r.decision());
        assertTrue(r.validated());
        assertEquals(r.needlesTotal(), r.needlesPreserved());
        assertEquals(r.signaturesTotal(), r.signaturesPreserved());
        assertTrue(r.checks().values().stream().allMatch(Boolean::booleanValue), r.checks().toString());
        assertTrue(r.savingsPercent() > 90, "expected large savings, got " + r.savingsPercent());
    }

    @Test
    void naiveCompressionSilentlyDropsNeedles() {
        CompressionReport r = run(GatewayMode.FAST, "large");
        assertEquals("COMPRESSED", r.decision());
        assertFalse(r.validated());
        assertTrue(r.needlesPreserved() < r.needlesTotal(),
                "naive path should lose needles, kept " + r.needlesPreserved() + "/" + r.needlesTotal());
        assertTrue(r.savingsPercent() > 95, "naive path reports big savings: " + r.savingsPercent());
        assertFalse(r.checks().values().stream().allMatch(Boolean::booleanValue));
    }

    @Test
    void validatorRejectsNaiveOutputOnItsOwn() {
        String raw = raw("large");
        PreservationValidator.Result r = validator.evaluate(raw, compressor.compressNaive(raw));
        assertFalse(r.passed());
        assertFalse(r.lostSignatures().isEmpty());
    }

    @Test
    void guardedCompressorKeepsEverythingOnLogAndJson() {
        for (String w : new String[]{"large", "json"}) {
            String raw = raw(w);
            PreservationValidator.Result r = validator.evaluate(raw, compressor.compressGuarded(raw));
            assertTrue(r.passed(), w + ": " + r.failures());
        }
    }

    // ------------------------------------------------------------ decisions

    @Test
    void balancedSkipsSmallInputs() {
        CompressionReport r = run(GatewayMode.BALANCED, "small");
        assertEquals("SKIPPED_SMALL", r.decision());
        assertEquals(r.rawTokens(), r.compressedTokens());
        assertEquals(0.0, r.savingsPercent());
    }

    @Test
    void balancedValidatesAggressiveReductionAndKeepsNeedles() {
        CompressionReport r = run(GatewayMode.BALANCED, "large");
        assertEquals("COMPRESSED", r.decision());
        assertTrue(r.validated(), "reduction > 80% must trigger validation");
        assertEquals(r.needlesTotal(), r.needlesPreserved());
    }

    @Test
    void balancedShadowCheckIsReportedButNotEnforcedOnModerateReduction() {
        var stageResult = stage.process(GatewayMode.BALANCED, "tester", "getServiceLogs", raw("json"));
        CompressionReport r = stageResult.report();
        assertEquals("COMPRESSED", r.decision());
        assertFalse(r.validated(), "66% savings is below the 80% trigger");
        assertEquals(0.0, r.validationTimeMs(), "no enforced validation cost");
        assertTrue(r.shadowCheckTimeMs() > 0, "contract was still evaluated, and its cost is reported separately");
        assertTrue(stageResult.audit().stream().anyMatch(a -> a.contains("contract NOT enforced") && a.contains("shadow check")),
                stageResult.audit().toString());
    }

    @Test
    void shadowCheckRecordsFailureThatFastModeDidNotActOn() {
        var stageResult = stage.process(GatewayMode.FAST, "tester", "getServiceLogs", raw("large"));
        assertFalse(stageResult.report().validated());
        assertTrue(stageResult.audit().stream().anyMatch(a -> a.contains("WOULD HAVE FAILED")),
                stageResult.audit().toString());
    }


    @Test
    void unrecognizedFormatFallsBackToRawUnderStrictAndBalanced() {
        for (GatewayMode m : new GatewayMode[]{GatewayMode.STRICT, GatewayMode.BALANCED}) {
            CompressionReport r = run(m, "syslog");
            assertEquals("FALLBACK_RAW", r.decision(), m.name());
            assertTrue(r.validated());
            assertFalse(r.checks().get("FORMAT_RECOGNIZED"));
            assertEquals(r.rawTokens(), r.compressedTokens());
            assertEquals(r.needlesTotal(), r.needlesPreserved(), "raw fallback keeps every needle");
        }
    }

    @Test
    void fastModeLosesSyslogNeedlesThatStrictKeeps() {
        CompressionReport fast = run(GatewayMode.FAST, "syslog");
        assertTrue(fast.needlesPreserved() < fast.needlesTotal());
    }

    // ------------------------------------------------------ end-to-end answers

    @Test
    void guardedAnswerMatchesGroundTruthNaiveDoesNot() {
        String q = "Summarize the Service X logs: how many distinct warning and error types are there?";
        GatewayResponse strict = ask(GatewayMode.STRICT, "large", q, "junior_support", "ROLE_SUPPORT");
        GatewayResponse fast = ask(GatewayMode.FAST, "large", q, "junior_support", "ROLE_SUPPORT");
        assertTrue(strict.getCompression().answerCorrect(), strict.getCompression().modelAnswer());
        assertFalse(fast.getCompression().answerCorrect(),
                "naive: " + fast.getCompression().modelAnswer() + " vs truth " + fast.getCompression().groundTruthAnswer());
    }

    /** Honest counter-example: the naive compressor keeps "(+N similar)" counts, so plain totals survive. */
    @Test
    void naiveCompressionStillGetsPlainWarningTotalRight() {
        String q = "Summarize the Service X logs: how many warnings are there?";
        GatewayResponse fast = ask(GatewayMode.FAST, "large", q, "junior_support", "ROLE_SUPPORT");
        assertTrue(fast.getCompression().answerCorrect(), fast.getCompression().modelAnswer());
    }

    @Test
    void adminAuthFailureQuestionIsAnsweredWrongUnderNaiveCompression() {
        String q = "Summarize the Service X logs: any authentication failures for user admin?";
        GatewayResponse strict = ask(GatewayMode.STRICT, "large", q, "junior_support", "ROLE_SUPPORT");
        GatewayResponse fast = ask(GatewayMode.FAST, "large", q, "junior_support", "ROLE_SUPPORT");
        assertTrue(strict.getCompression().modelAnswer().startsWith("Yes"));
        assertTrue(strict.getCompression().answerCorrect());
        assertEquals("No", fast.getCompression().modelAnswer().substring(0, 2),
                "naive path should report no admin failures: " + fast.getCompression().modelAnswer());
    }

    // --------------------------------------------------------------- security

    @Test
    void indirectInjectionInToolOutputIsQuarantinedAndFollowedOnlyWhenUnprotected() {
        String q = "Summarize the Service X logs";
        GatewayResponse fast = ask(GatewayMode.FAST, "poisoned", q, "junior_support", "ROLE_SUPPORT");
        assertTrue(fast.getFinalResponse().contains("UNPROTECTED MODEL"), "FAST obeys injected tool output");

        GatewayResponse strict = ask(GatewayMode.STRICT, "poisoned", q, "junior_support", "ROLE_SUPPORT");
        assertFalse(strict.getFinalResponse().contains("UNPROTECTED MODEL"));
        assertTrue(strict.getCompression().injectionsQuarantined() >= 1);
        assertFalse(strict.getToolResult().toLowerCase().contains("ignore previous instructions"));
    }

    @Test
    void secretsInToolOutputAreRedactedBeforeModelAndStore() {
        String poisoned = raw("poisoned");
        assertTrue(poisoned.contains("AKIA9Q8W7E6R5T4Y") && poisoned.contains("Zx9fKq2mL"));

        GatewayResponse strict = ask(GatewayMode.STRICT, "poisoned", "Summarize the Service X logs", "junior_support", "ROLE_SUPPORT");
        CompressionReport rep = strict.getCompression();
        assertTrue(rep.secretsRedacted() >= 2);
        assertFalse(strict.getToolResult().contains("AKIA9Q8W7E6R5T4Y"));
        assertFalse(strict.getToolResult().contains("Zx9fKq2mL"));

        OriginalStore.RecallResult rr = store.recall(rep.originalId(), "junior_support", "ROLE_SUPPORT", false);
        assertTrue(rr.allowed());
        assertFalse(rr.content().contains("AKIA9Q8W7E6R5T4Y"), "store holds redacted original");
        assertFalse(rr.content().contains("Zx9fKq2mL"));
    }

    @Test
    void recallEnforcesOwnerAndAdminAccess() {
        GatewayResponse strict = ask(GatewayMode.STRICT, "large", "Summarize the Service X logs", "alice", "ROLE_USER");
        String id = strict.getCompression().originalId();
        assertNotNull(id);

        GatewayResponse bob = ask(GatewayMode.STRICT, "large", "Show me the full original " + id, "bob", "ROLE_USER");
        assertTrue(bob.getToolResult().contains("403 Forbidden"), bob.getToolResult());

        GatewayResponse alice = ask(GatewayMode.STRICT, "large", "Show me the full original " + id, "alice", "ROLE_USER");
        assertTrue(alice.getToolResult().contains("RateLimitExceeded"));

        GatewayResponse admin = ask(GatewayMode.STRICT, "large", "Show me the full original " + id, "sec_admin", "ROLE_ADMIN");
        assertTrue(admin.getToolResult().contains("RateLimitExceeded"));
    }

    @Test
    void fastModeBypassesRecallAuthorization() {
        GatewayResponse strict = ask(GatewayMode.STRICT, "large", "Summarize the Service X logs", "alice", "ROLE_USER");
        String id = strict.getCompression().originalId();
        GatewayResponse fastBob = ask(GatewayMode.FAST, "large", "Show me the full original " + id, "bob", "ROLE_USER");
        assertTrue(fastBob.getToolResult().contains("RateLimitExceeded"), "FAST skips RBAC, same as fetchInvoice");
    }
}
