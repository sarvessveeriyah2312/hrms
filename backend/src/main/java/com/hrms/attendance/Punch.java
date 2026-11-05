package com.hrms.attendance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One clock-in or clock-out. Never updated after creation; corrections are separate requests. */
@Entity
@Table(name = "punch")
public class Punch {

    public enum Type { IN, OUT }

    public enum Result { ACCEPTED, FLAGGED, BLOCKED }

    public enum Source { MOBILE, KIOSK, WEB, OFFLINE }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(name = "server_time", nullable = false)
    private Instant serverTime;

    @Column(name = "client_time")
    private Instant clientTime;

    private BigDecimal lat;
    private BigDecimal lng;

    @Column(name = "accuracy_m")
    private Integer accuracyM;

    @Column(name = "site_id")
    private UUID siteId;

    @Column(name = "wifi_bssid")
    private String wifiBssid;

    @Column(name = "selfie_file_id")
    private UUID selfieFileId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "flags", columnDefinition = "text[]")
    private String[] flags;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Result result;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    private String remarks;

    @Version
    private long version;

    protected Punch() {
    }

    public Punch(UUID tenantId, UUID employeeId, Type type, Instant serverTime, Instant clientTime,
                 BigDecimal lat, BigDecimal lng, Integer accuracyM, UUID siteId, String wifiBssid,
                 String[] flags, Result result, Source source, String remarks) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.employeeId = employeeId;
        this.type = type;
        this.serverTime = serverTime;
        this.clientTime = clientTime;
        this.lat = lat;
        this.lng = lng;
        this.accuracyM = accuracyM;
        this.siteId = siteId;
        this.wifiBssid = wifiBssid;
        this.flags = flags;
        this.result = result;
        this.source = source;
        this.remarks = remarks;
    }

    public UUID getId() { return id; }
    public UUID getEmployeeId() { return employeeId; }
    public Type getType() { return type; }
    public Instant getServerTime() { return serverTime; }
    public String[] getFlags() { return flags; }
    public Result getResult() { return result; }
    public UUID getSiteId() { return siteId; }

    /** Set before saving, once the selfie has been checked (the person's own image, not used before). */
    void setSelfieFileId(UUID selfieFileId) { this.selfieFileId = selfieFileId; }
}
