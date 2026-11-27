package com.secureai.model;

public class GatewayRequest {
    private String prompt;
    private GatewayMode mode = GatewayMode.BALANCED;
    private String username = "junior_support";
    private String role = "ROLE_SUPPORT"; // Roles: ROLE_USER, ROLE_SUPPORT, ROLE_ADMIN
    private boolean simulatedApproval = false;
    private String workload = "small"; // log workload served by getServiceLogs: small | large | json | syslog | poisoned

    public GatewayRequest() {}

    public GatewayRequest(String prompt, GatewayMode mode, String username, String role) {
        this.prompt = prompt;
        this.mode = mode;
        this.username = username;
        this.role = role;
    }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public GatewayMode getMode() { return mode; }
    public void setMode(GatewayMode mode) { this.mode = mode; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getWorkload() { return workload; }
    public void setWorkload(String workload) { this.workload = workload; }

    public boolean isSimulatedApproval() { return simulatedApproval; }
    public void setSimulatedApproval(boolean simulatedApproval) { this.simulatedApproval = simulatedApproval; }
}
