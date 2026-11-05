package com.hrms.attendance;

import com.hrms.shared.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Local development only: a head-office site for the main demo company (geofence + office Wi-Fi), with everyone
 * assigned, when it has no sites yet. The coordinates and access point are samples.
 */
@Component
@Order(50)
@ConditionalOnProperty(name = "hrms.seed.enabled", havingValue = "true")
class SiteDemoData implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SiteDemoData.class);
    private final JdbcClient jdbc;

    SiteDemoData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        UUID tenant = jdbc.sql("select id from tenant where code = 'DEMO'").query(UUID.class).optional().orElse(null);
        if (tenant == null) return;
        TenantContext.set(tenant);
        try {
            if (jdbc.sql("select exists (select 1 from site)").query(Boolean.class).single()) return;
            UUID entity = jdbc.sql("select id from legal_entity order by created_at limit 1").query(UUID.class).optional().orElse(null);
            if (entity == null) return;
            UUID site = UUID.randomUUID();
            jdbc.sql("""
                    insert into site (id, tenant_id, entity_id, name, address, lat, lng, radius_m, methods, wifi_mode)
                    values (:id, :tenant, :entity, 'Head office', 'Bangsar South, 59200 Kuala Lumpur', :lat, :lng, 150,
                            '{GEOFENCE,WIFI}', 'GPS_OR_WIFI')""")
                .param("id", site).param("tenant", tenant).param("entity", entity)
                .param("lat", new BigDecimal("3.110250")).param("lng", new BigDecimal("101.665650")).update();
            jdbc.sql("""
                    insert into site_wifi (tenant_id, site_id, bssid, ssid, label)
                    values (:tenant, :site, '3c:84:6a:12:9f:01', 'DEMO-OFFICE', 'Level 12 router')""")
                .param("tenant", tenant).param("site", site).update();
            List<UUID> people = jdbc.sql("select id from employee where status = 'ACTIVE'").query(UUID.class).list();
            for (UUID e : people) {
                jdbc.sql("insert into employee_site (tenant_id, employee_id, site_id) values (:tenant, :emp, :site)")
                    .param("tenant", tenant).param("emp", e).param("site", site).update();
            }
            log.info("Added a demo site with {} people", people.size());
        } finally {
            TenantContext.clear();
        }
    }
}
