package com.hrms.shared.audit;

import com.hrms.shared.tenancy.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Appends to audit_event: who changed what, with the new values. Never updated or deleted. */
@Component
public class AuditLog {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    AuditLog(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(UUID actorUserId, String action, String entityType, UUID entityId, Map<String, ?> after) {
        jdbc.sql("""
                insert into audit_event (tenant_id, actor_id, action, entity_type, entity_id, after)
                values (:tenant, :actor, :action, :type, :id, cast(:after as jsonb))""")
            .param("tenant", TenantContext.require())
            .param("actor", actorUserId)
            .param("action", action)
            .param("type", entityType)
            .param("id", entityId)
            .param("after", after == null ? null : json.writeValueAsString(after))
            .update();
    }
}
