package io.github.modulithlab;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModuleStructureTest {

    @Test
    void verifiesModuleBoundaries() {
        ApplicationModules.of(ModulithEventLabApplication.class).verify();
    }
}
