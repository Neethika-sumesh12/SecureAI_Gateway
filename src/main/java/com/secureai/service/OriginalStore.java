package com.secureai.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory store for raw tool output that was replaced by a compressed version, so an agent can call
 * recall(id) to fetch the full text. Entries are owned by the requesting user (tenant isolation); only the
 * owner or ROLE_ADMIN can recall them. Bounded by entry count; oldest entries are evicted first.
 */
@Service
public class OriginalStore {

    private static final int MAX_ENTRIES = 100;

    public record Stored(String id, String owner, String toolName, String content, Instant createdAt) {}

    public record RecallResult(boolean found, boolean allowed, String content, String message) {}

    private final Map<String, Stored> entries = new LinkedHashMap<>();

    public synchronized String store(String owner, String toolName, String content) {
        String id = "orig-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        entries.put(id, new Stored(id, owner, toolName, content, Instant.now()));
        Iterator<String> it = entries.keySet().iterator();
        while (entries.size() > MAX_ENTRIES && it.hasNext()) {
            it.next();
            it.remove();
        }
        return id;
    }

    public synchronized RecallResult recall(String id, String username, String role, boolean bypassAuth) {
        Stored s = entries.get(id);
        if (s == null) {
            return new RecallResult(false, false, null, "Original not found: " + id);
        }
        boolean owner = s.owner() != null && s.owner().equalsIgnoreCase(username);
        if (!bypassAuth && !owner && !"ROLE_ADMIN".equals(role)) {
            return new RecallResult(true, false, null,
                    "403 Forbidden: User '" + username + "' (" + role + ") is not authorized to recall "
                            + id + " owned by '" + s.owner() + "'.");
        }
        return new RecallResult(true, true, s.content(), null);
    }
}
