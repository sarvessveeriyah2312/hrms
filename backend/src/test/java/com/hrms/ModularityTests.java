package com.hrms;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Fails the build when one module reaches into another module's internals. */
class ModularityTests {

    @Test
    void modulesRespectBoundaries() {
        ApplicationModules.of(HrmsApiApplication.class).verify();
    }
}
