package com.ultron.component

import org.junit.jupiter.api.Test
import kotlin.test.*

class ComponentRuntimeTest {

    // Test helpers and fake components
    private class TestServiceA
    private class TestServiceB
    private class TestServiceC
    private class TestServiceD

    private class RecordingComponent(
        override val descriptor: ComponentDescriptor
    ) : AppComponent {
        var initializeCallCount = 0
        var startCallCount = 0
        var stopCallCount = 0

        override suspend fun initialize(context: ComponentContext) {
            initializeCallCount++
        }

        override suspend fun start() {
            startCallCount++
        }

        override suspend fun stop() {
            stopCallCount++
        }

        companion object {
            fun of(
                id: String,
                requires: Set<ServiceKey<*>> = emptySet(),
                provides: Set<ServiceKey<*>> = emptySet()
            ): RecordingComponent {
                return RecordingComponent(
                    ComponentDescriptor(
                        id = ComponentId(id),
                        requires = requires,
                        provides = provides
                    )
                )
            }
        }
    }

    // 1. Valid registration
    @Test
    fun `test valid registration`() {
        val runtime = ComponentRuntime()
        val compA = RecordingComponent.of(
            id = "compA",
            provides = setOf(ServiceKey("serviceA", TestServiceA::class))
        )
        val compB = RecordingComponent.of(
            id = "compB",
            requires = setOf(ServiceKey("serviceA", TestServiceA::class)),
            provides = setOf(ServiceKey("serviceB", TestServiceB::class))
        )

        runtime.register(compA)
        runtime.register(compB)

        assertEquals(2, runtime.size)
        assertEquals(compA, runtime.getComponent(ComponentId("compA")))
        assertEquals(compB, runtime.getComponent(ComponentId("compB")))
        assertEquals(compA.descriptor, runtime.getDescriptor(ComponentId("compA")))
        assertEquals(compB.descriptor, runtime.getDescriptor(ComponentId("compB")))

        runtime.validate()
        val order = runtime.resolveExecutionOrder()
        assertEquals(listOf(ComponentId("compA"), ComponentId("compB")), order)
    }

    // 2. Duplicate component IDs
    @Test
    fun `test duplicate component IDs rejected`() {
        val runtime = ComponentRuntime()
        val comp1 = RecordingComponent.of("sameId")
        val comp2 = RecordingComponent.of("sameId")

        runtime.register(comp1)
        val ex = assertFailsWith<DuplicateComponentException> {
            runtime.register(comp2)
        }
        assertEquals(ComponentId("sameId"), ex.componentId)
        assertTrue(ex.message!!.contains("sameId"))
    }

    // 3. Blank component IDs
    @Test
    fun `test blank component and service IDs rejected`() {
        val emptyIdEx = assertFailsWith<IllegalArgumentException> {
            ComponentId("")
        }
        assertTrue(emptyIdEx.message!!.contains("must not be blank", ignoreCase = true))

        val blankIdEx = assertFailsWith<IllegalArgumentException> {
            ComponentId("   \t\n")
        }
        assertTrue(blankIdEx.message!!.contains("must not be blank", ignoreCase = true))

        val emptyServiceKeyEx = assertFailsWith<IllegalArgumentException> {
            ServiceKey("", TestServiceA::class)
        }
        assertTrue(emptyServiceKeyEx.message!!.contains("must not be blank", ignoreCase = true))

        val blankServiceKeyEx = assertFailsWith<IllegalArgumentException> {
            ServiceKey("   ", TestServiceA::class)
        }
        assertTrue(blankServiceKeyEx.message!!.contains("must not be blank", ignoreCase = true))
    }

    // 4. Missing service providers
    @Test
    fun `test missing service provider rejected`() {
        val runtime = ComponentRuntime()
        val comp = RecordingComponent.of(
            id = "consumer",
            requires = setOf(ServiceKey("missingService", TestServiceA::class))
        )
        runtime.register(comp)

        val ex = assertFailsWith<MissingProviderException> {
            runtime.validate()
        }
        assertEquals("missingService", ex.serviceId)
        assertEquals(ComponentId("consumer"), ex.requiredBy)
        assertTrue(ex.message!!.contains("missingService"))
        assertTrue(ex.message!!.contains("consumer"))
    }

    // 5. Duplicate service providers
    @Test
    fun `test duplicate service providers rejected`() {
        val runtime = ComponentRuntime()
        val key = ServiceKey("sharedService", TestServiceA::class)
        val provider1 = RecordingComponent.of("provider1", provides = setOf(key))
        val provider2 = RecordingComponent.of("provider2", provides = setOf(key))

        runtime.register(provider1)
        val ex = assertFailsWith<DuplicateProviderException> {
            runtime.register(provider2)
        }
        assertEquals("sharedService", ex.serviceId)
        assertEquals(ComponentId("provider1"), ex.existingProvider)
        assertEquals(ComponentId("provider2"), ex.conflictingProvider)
        assertTrue(ex.message!!.contains("sharedService"))
        assertTrue(ex.message!!.contains("provider1"))
        assertTrue(ex.message!!.contains("provider2"))
    }

    // 6. Conflicting types for the same service ID
    @Test
    fun `test conflicting types for same service ID rejected`() {
        val runtime = ComponentRuntime()
        val key1 = ServiceKey("config", TestServiceA::class)
        val key2 = ServiceKey("config", TestServiceB::class)

        val comp1 = RecordingComponent.of("comp1", provides = setOf(key1))
        val comp2 = RecordingComponent.of("comp2", requires = setOf(key2))

        runtime.register(comp1)
        val ex = assertFailsWith<ConflictingServiceTypeException> {
            runtime.register(comp2)
        }
        assertEquals("config", ex.serviceId)
        assertEquals(TestServiceA::class, ex.existingType)
        assertEquals(TestServiceB::class, ex.conflictingType)
        assertEquals(ComponentId("comp2"), ex.componentId)
        assertTrue(ex.message!!.contains("config"))
        assertTrue(ex.message!!.contains("comp2"))
    }

    @Test
    fun `test conflicting types between two consumers rejected`() {
        val runtime = ComponentRuntime()
        val key1 = ServiceKey("data", TestServiceA::class)
        val key2 = ServiceKey("data", TestServiceB::class)

        val consumer1 = RecordingComponent.of("consumer1", requires = setOf(key1))
        val consumer2 = RecordingComponent.of("consumer2", requires = setOf(key2))

        runtime.register(consumer1)
        val ex = assertFailsWith<ConflictingServiceTypeException> {
            runtime.register(consumer2)
        }
        assertEquals("data", ex.serviceId)
        assertEquals(TestServiceA::class, ex.existingType)
        assertEquals(TestServiceB::class, ex.conflictingType)
        assertEquals(ComponentId("consumer2"), ex.componentId)
    }

    // 7. Duplicate service declarations
    @Test
    fun `test duplicate service declarations in requires rejected`() {
        // Set with different types but same service ID
        val key1 = ServiceKey("dupKey", TestServiceA::class)
        val key2 = ServiceKey("dupKey", TestServiceB::class)

        val ex = assertFailsWith<InvalidDescriptorException> {
            ComponentDescriptor(
                id = ComponentId("faultyComp"),
                requires = setOf(key1, key2)
            )
        }
        assertEquals(ComponentId("faultyComp"), ex.componentId)
        assertTrue(ex.message!!.contains("dupKey"))
        assertTrue(ex.message!!.contains("requires"))
    }

    @Test
    fun `test duplicate service declarations in provides rejected`() {
        val key1 = ServiceKey("dupKey", TestServiceA::class)
        val key2 = ServiceKey("dupKey", TestServiceB::class)

        val ex = assertFailsWith<InvalidDescriptorException> {
            ComponentDescriptor(
                id = ComponentId("faultyComp"),
                provides = setOf(key1, key2)
            )
        }
        assertEquals(ComponentId("faultyComp"), ex.componentId)
        assertTrue(ex.message!!.contains("dupKey"))
        assertTrue(ex.message!!.contains("provides"))
    }

    // 8. A component requiring its own service
    @Test
    fun `test component requiring its own service rejected`() {
        val key = ServiceKey("selfService", TestServiceA::class)

        val ex = assertFailsWith<SelfDependencyException> {
            ComponentDescriptor(
                id = ComponentId("selfComp"),
                requires = setOf(key),
                provides = setOf(key)
            )
        }
        assertEquals(ComponentId("selfComp"), ex.componentId)
        assertEquals("selfService", ex.serviceId)
        assertTrue(ex.message!!.contains("selfComp"))
        assertTrue(ex.message!!.contains("selfService"))
    }

    // 9. A two-component dependency cycle
    @Test
    fun `test two-component dependency cycle rejected`() {
        val runtime = ComponentRuntime()
        val s1 = ServiceKey("s1", TestServiceA::class)
        val s2 = ServiceKey("s2", TestServiceB::class)

        val compA = RecordingComponent.of("compA", requires = setOf(s2), provides = setOf(s1))
        val compB = RecordingComponent.of("compB", requires = setOf(s1), provides = setOf(s2))

        runtime.register(compA, compB)

        val ex = assertFailsWith<CircularDependencyException> {
            runtime.validate()
        }
        assertTrue(ex.cycle.contains(ComponentId("compA")))
        assertTrue(ex.cycle.contains(ComponentId("compB")))
        assertTrue(ex.message!!.contains("compA"))
        assertTrue(ex.message!!.contains("compB"))
    }

    // 10. A longer dependency cycle
    @Test
    fun `test longer dependency cycle rejected`() {
        val runtime = ComponentRuntime()
        val s1 = ServiceKey("s1", TestServiceA::class)
        val s2 = ServiceKey("s2", TestServiceB::class)
        val s3 = ServiceKey("s3", TestServiceC::class)
        val s4 = ServiceKey("s4", TestServiceD::class)

        // A -> B -> C -> D -> A
        val compA = RecordingComponent.of("nodeA", requires = setOf(s4), provides = setOf(s1))
        val compB = RecordingComponent.of("nodeB", requires = setOf(s1), provides = setOf(s2))
        val compC = RecordingComponent.of("nodeC", requires = setOf(s2), provides = setOf(s3))
        val compD = RecordingComponent.of("nodeD", requires = setOf(s3), provides = setOf(s4))

        runtime.register(compA, compB, compC, compD)

        val ex = assertFailsWith<CircularDependencyException> {
            runtime.resolveExecutionOrder()
        }
        assertTrue(ex.cycle.contains(ComponentId("nodeA")))
        assertTrue(ex.cycle.contains(ComponentId("nodeB")))
        assertTrue(ex.cycle.contains(ComponentId("nodeC")))
        assertTrue(ex.cycle.contains(ComponentId("nodeD")))
        assertTrue(ex.message!!.contains("Dependency cycle detected"))
    }

    // 11. Correct ordering for a linear dependency chain
    @Test
    fun `test correct ordering for linear dependency chain`() {
        val runtime = ComponentRuntime()
        val s1 = ServiceKey("s1", TestServiceA::class)
        val s2 = ServiceKey("s2", TestServiceB::class)
        val s3 = ServiceKey("s3", TestServiceC::class)

        // Linear: A -> B -> C -> D
        val compD = RecordingComponent.of("nodeD", requires = setOf(s3))
        val compB = RecordingComponent.of("nodeB", requires = setOf(s1), provides = setOf(s2))
        val compA = RecordingComponent.of("nodeA", provides = setOf(s1))
        val compC = RecordingComponent.of("nodeC", requires = setOf(s2), provides = setOf(s3))

        // Register in arbitrary order
        runtime.register(compD, compB, compA, compC)

        val order = runtime.resolveExecutionOrder()
        assertEquals(
            listOf(
                ComponentId("nodeA"),
                ComponentId("nodeB"),
                ComponentId("nodeC"),
                ComponentId("nodeD")
            ),
            order
        )
    }

    // 12. Deterministic ordering of independent components
    @Test
    fun `test deterministic ordering of independent components`() {
        val runtime = ComponentRuntime()
        val zulu = RecordingComponent.of("zulu")
        val bravo = RecordingComponent.of("bravo")
        val alpha = RecordingComponent.of("alpha")
        val charlie = RecordingComponent.of("charlie")

        runtime.register(zulu, bravo, alpha, charlie)

        val order = runtime.resolveExecutionOrder()
        assertEquals(
            listOf(
                ComponentId("alpha"),
                ComponentId("bravo"),
                ComponentId("charlie"),
                ComponentId("zulu")
            ),
            order
        )
    }

    @Test
    fun `test case-sensitive ordering of independent components`() {
        val runtime = ComponentRuntime()
        val bravoUpper = RecordingComponent.of("Bravo")
        val alphaLower = RecordingComponent.of("alpha")
        val zuluUpper = RecordingComponent.of("Zulu")

        runtime.register(alphaLower, zuluUpper, bravoUpper)

        // In ASCII: 'B' (66) < 'Z' (90) < 'a' (97)
        val order = runtime.resolveExecutionOrder()
        assertEquals(
            listOf(
                ComponentId("Bravo"),
                ComponentId("Zulu"),
                ComponentId("alpha")
            ),
            order
        )
    }

    // 13. Deterministic ordering for a mixed dependency graph
    @Test
    fun `test deterministic ordering for mixed dependency graph from spec`() {
        // Spec example:
        // - alpha provides a service required by charlie.
        // - bravo has no dependencies.
        // - charlie requires the service provided by alpha.
        // The resulting order must be: alpha -> bravo -> charlie

        val runtime = ComponentRuntime()
        val sAlpha = ServiceKey("serviceFromAlpha", TestServiceA::class)

        val alpha = RecordingComponent.of("alpha", provides = setOf(sAlpha))
        val bravo = RecordingComponent.of("bravo")
        val charlie = RecordingComponent.of("charlie", requires = setOf(sAlpha))

        runtime.register(bravo, charlie, alpha)

        val order = runtime.resolveExecutionOrder()
        assertEquals(
            listOf(
                ComponentId("alpha"),
                ComponentId("bravo"),
                ComponentId("charlie")
            ),
            order
        )
    }

    // 14. Identical computed order when registration order changes
    @Test
    fun `test identical computed order when registration order changes`() {
        val sAlpha = ServiceKey("serviceFromAlpha", TestServiceA::class)

        val permutations = listOf(
            listOf("alpha", "bravo", "charlie"),
            listOf("alpha", "charlie", "bravo"),
            listOf("bravo", "alpha", "charlie"),
            listOf("bravo", "charlie", "alpha"),
            listOf("charlie", "alpha", "bravo"),
            listOf("charlie", "bravo", "alpha")
        )

        val expectedOrder = listOf(
            ComponentId("alpha"),
            ComponentId("bravo"),
            ComponentId("charlie")
        )

        for (perm in permutations) {
            val runtime = ComponentRuntime()
            for (id in perm) {
                when (id) {
                    "alpha" -> runtime.register(RecordingComponent.of("alpha", provides = setOf(sAlpha)))
                    "bravo" -> runtime.register(RecordingComponent.of("bravo"))
                    "charlie" -> runtime.register(RecordingComponent.of("charlie", requires = setOf(sAlpha)))
                }
            }
            val computedOrder = runtime.resolveExecutionOrder()
            assertEquals(expectedOrder, computedOrder, "Failed for registration permutation: $perm")
        }
    }

    // 15. Confirmation that validation never invokes component lifecycle methods
    @Test
    fun `test confirmation that validation never invokes component lifecycle methods`() {
        val runtime = ComponentRuntime()
        val s1 = ServiceKey("s1", TestServiceA::class)
        val s2 = ServiceKey("s2", TestServiceB::class)

        val comp1 = RecordingComponent.of("c1", provides = setOf(s1))
        val comp2 = RecordingComponent.of("c2", requires = setOf(s1), provides = setOf(s2))
        val comp3 = RecordingComponent.of("c3", requires = setOf(s2))

        val allComponents = listOf(comp1, comp2, comp3)

        // Register components
        runtime.register(allComponents)

        // Validate multiple times
        runtime.validate()
        runtime.validate()

        // Resolve order
        val order = runtime.resolveExecutionOrder()
        val componentOrder = runtime.resolveComponentOrder()

        assertEquals(3, order.size)
        assertEquals(3, componentOrder.size)

        // Verify lifecycle call counts
        for (comp in allComponents) {
            assertEquals(0, comp.initializeCallCount, "initialize() must not be called on ${comp.descriptor.id}")
            assertEquals(0, comp.startCallCount, "start() must not be called on ${comp.descriptor.id}")
            assertEquals(0, comp.stopCallCount, "stop() must not be called on ${comp.descriptor.id}")
        }
    }

    // Additional tests for edge cases

    @Test
    fun `test empty runtime validation and resolution`() {
        val runtime = ComponentRuntime()
        runtime.validate()
        val order = runtime.resolveExecutionOrder()
        assertTrue(order.isEmpty())
        assertTrue(runtime.resolveComponentOrder().isEmpty())
    }

    @Test
    fun `test diamond dependency graph deterministic ordering`() {
        // A provides sA
        // B requires sA, provides sB
        // C requires sA, provides sC
        // D requires sB and sC
        // Eligible after A: B and C. Between B and C, B < C.
        // Order: A, B, C, D
        val sA = ServiceKey("sA", TestServiceA::class)
        val sB = ServiceKey("sB", TestServiceB::class)
        val sC = ServiceKey("sC", TestServiceC::class)

        val compA = RecordingComponent.of("A", provides = setOf(sA))
        val compB = RecordingComponent.of("B", requires = setOf(sA), provides = setOf(sB))
        val compC = RecordingComponent.of("C", requires = setOf(sA), provides = setOf(sC))
        val compD = RecordingComponent.of("D", requires = setOf(sB, sC))

        val runtime = ComponentRuntime(compD, compC, compB, compA)
        val order = runtime.resolveExecutionOrder()

        assertEquals(
            listOf(ComponentId("A"), ComponentId("B"), ComponentId("C"), ComponentId("D")),
            order
        )
    }

    @Test
    fun `test multiple services provided by same provider`() {
        val s1 = ServiceKey("s1", TestServiceA::class)
        val s2 = ServiceKey("s2", TestServiceB::class)

        val provider = RecordingComponent.of("provider", provides = setOf(s1, s2))
        val consumer = RecordingComponent.of("consumer", requires = setOf(s1, s2))

        val runtime = ComponentRuntime(consumer, provider)
        val order = runtime.resolveExecutionOrder()

        assertEquals(listOf(ComponentId("provider"), ComponentId("consumer")), order)
    }
}

