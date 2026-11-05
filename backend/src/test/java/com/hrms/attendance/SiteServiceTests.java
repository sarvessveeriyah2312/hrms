package com.hrms.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SiteServiceTests {

    @Test
    void readsAccessPointAddressesInTheUsualFormats() {
        assertThat(SiteService.normaliseBssid("3C-84-6A-12-9F-01")).isEqualTo("3c:84:6a:12:9f:01");
        assertThat(SiteService.normaliseBssid(" 3c:84:6a:12:9f:01 ")).isEqualTo("3c:84:6a:12:9f:01");
        assertThat(SiteService.normaliseBssid("3c846a129f01")).isEqualTo("3c:84:6a:12:9f:01");
        assertThat(SiteService.normaliseBssid("3c84.6a12.9f01")).isEqualTo("3c:84:6a:12:9f:01");
    }

    @Test
    void rejectsThingsThatArentAccessPoints() {
        assertThat(SiteService.normaliseBssid("DEMO-OFFICE")).isNull();
        assertThat(SiteService.normaliseBssid("3c:84:6a:12:9f")).isNull();
        assertThat(SiteService.normaliseBssid("zz:84:6a:12:9f:01")).isNull();
        assertThat(SiteService.normaliseBssid(null)).isNull();
    }
}
