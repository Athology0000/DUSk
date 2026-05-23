package org.phantom.api.module

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ModuleRegistryTest {
    private fun stubModule(stateChanges: MutableList<String> = mutableListOf(), n: String = "phantom-test") = object : LoadedModule {
        override val name: String = n
        override fun onLoad(api: LoaderApi) { stateChanges.add("onLoad") }
        override fun onActivate() { stateChanges.add("onActivate") }
        override fun onDeactivate() { stateChanges.add("onDeactivate") }
    }

    @Test
    fun `register transitions NOT_LOADED to LOADED`() {
        val registry = ModuleRegistry()
        val mod = stubModule()
        registry.register(mod)
        assertEquals(ModuleState.LOADED, registry.stateOf(mod.name))
    }

    @Test
    fun `markActive transitions LOADED to ACTIVE`() {
        val registry = ModuleRegistry()
        val mod = stubModule()
        registry.register(mod)
        registry.markActive(mod.name)
        assertEquals(ModuleState.ACTIVE, registry.stateOf(mod.name))
    }

    @Test
    fun `markInactive transitions ACTIVE to INACTIVE`() {
        val registry = ModuleRegistry()
        val mod = stubModule()
        registry.register(mod)
        registry.markActive(mod.name)
        registry.markInactive(mod.name)
        assertEquals(ModuleState.INACTIVE, registry.stateOf(mod.name))
    }

    @Test
    fun `activeModules returns only ACTIVE entries`() {
        val registry = ModuleRegistry()
        registry.register(stubModule(n = "a"))
        registry.register(stubModule(n = "b"))
        registry.register(stubModule(n = "c"))
        registry.markActive("a")
        registry.markActive("c")
        assertEquals(setOf("a", "c"), registry.activeModules().map { it.name }.toSet())
    }

    @Test
    fun `markLoadFailed is terminal — subsequent transitions are ignored`() {
        val registry = ModuleRegistry()
        val mod = stubModule()
        registry.register(mod)
        registry.markLoadFailed(mod.name, "boom")
        registry.markActive(mod.name) // no-op
        assertEquals(ModuleState.LOAD_FAILED, registry.stateOf(mod.name))
    }

    @Test
    fun `unknown module returns NOT_LOADED`() {
        val registry = ModuleRegistry()
        assertEquals(ModuleState.NOT_LOADED, registry.stateOf("missing"))
    }

    @Test
    fun `failureReason is recorded for LOAD_FAILED`() {
        val registry = ModuleRegistry()
        val mod = stubModule()
        registry.register(mod)
        registry.markLoadFailed(mod.name, "decrypt failed")
        assertEquals("decrypt failed", registry.failureReasonOf(mod.name))
    }
}
