package com.secureai.service;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class MockEnterpriseToolsService {

    // Mock Invoices (Tenant-isolated)
    private static final Map<String, String> INVOICES = Map.of(
            "9041", "Invoice #9041 - Owner: Bob | Amount: $340.00 | Status: PAID",
            "9042", "Invoice #9042 - Owner: Alice | Amount: $4,500.00 | Status: PENDING_CONFIDENTIAL"
    );

    // Mock Server State
    private static final Map<String, String> SERVERS = new HashMap<>(Map.of(
            "app-srv-01", "Online - Uptime: 45 days, Load: 12%",
            "db-prod-cluster", "Online - Uptime: 180 days, Master-Slave active"
    ));

    public record ToolExecutionResult(boolean success, String toolName, String output, String accessViolation) {}

    // Tool 1: Fetch Invoice (Enforces RBAC / Tenant isolation)
    public ToolExecutionResult fetchInvoice(String invoiceId, String requestingUser, String requestingRole, boolean bypassAuth) {
        String data = INVOICES.get(invoiceId);
        if (data == null) {
            return new ToolExecutionResult(false, "fetchInvoice", "Invoice ID not found: " + invoiceId, null);
        }

        // Fast mode bypasses auth check; Secure & Balanced enforce it
        if (!bypassAuth) {
            if ("9042".equals(invoiceId) && !"alice".equalsIgnoreCase(requestingUser) && !"ROLE_ADMIN".equals(requestingRole)) {
                return new ToolExecutionResult(
                        false,
                        "fetchInvoice",
                        null,
                        "403 Forbidden: User '" + requestingUser + "' (" + requestingRole + ") is not authorized to view invoice #" + invoiceId + " belonging to Alice."
                );
            }
        }

        return new ToolExecutionResult(true, "fetchInvoice", data, null);
    }

    // Tool 2: Restart Server (Enforces High-Impact Approval Check)
    public ToolExecutionResult restartServer(String serverId, String requestingRole, boolean approvalGranted, boolean bypassAuth) {
        if (!bypassAuth) {
            if (!approvalGranted && !"ROLE_ADMIN".equals(requestingRole)) {
                return new ToolExecutionResult(
                        false,
                        "restartServer",
                        null,
                        "Human-in-the-Loop Approval Required: Action 'restartServer(" + serverId + ")' is restricted. Requesting user requires Admin approval."
                );
            }
        }

        SERVERS.put(serverId, "Restarted just now by request at " + new Date());
        return new ToolExecutionResult(true, "restartServer", "Server '" + serverId + "' reboot initiated successfully.", null);
    }

    // Tool 3: Get Service Logs (Routine / Benign Tool). The workload picks one of the deterministic synthetic logs.
    public ToolExecutionResult getServiceLogs(String serviceName, String workload) {
        String logs = LogWorkloads.get(LogWorkloads.NAMES.contains(workload) ? workload : "small");
        return new ToolExecutionResult(true, "getServiceLogs", "Logs for " + serviceName + ":\n" + logs, null);
    }
}
