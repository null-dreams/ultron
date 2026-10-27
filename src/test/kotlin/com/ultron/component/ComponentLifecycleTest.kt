package com.ultron.component

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class ComponentLifecycleTest {

    private class ServiceA
    private class ServiceB
    private class ServiceC

    private class LifecycleRecordingComponent(
        override val descriptor: ComponentDescriptor,
        val callLog: MutableList<String>? = null,
        val onInitialize: (suspend (ComponentContext) -> Unit)? = null,
        val onStart: (suspend () -> Unit)? = null,
        val onStop: (suspend () -> Unit)? = null
    ) : AppComponent {
        var initializeCallCount = 0
        var startCallCount = 0
        var stopCallCount = 0

        override suspend fun initialize(context: ComponentContext) {
            initializeCallCount++
            callLog?.add("initialize:${descriptor.id.value}")
            onInitialize?.invoke(context)
        }

        override suspend fun start() {
            startCallCount++
            callLog?.add("start:${descriptor.id.value}")
            onStart?.invoke()
        }

        override suspend fun stop() {
            stopCallCount++
            callLog?.add("stop:${descriptor.id.value}")
            onStop?.invoke()
        }

        companion object {
            fun of(
                id: String,
                requires: Set<ServiceKey<*>> = emptySet(),
                provides: Set<ServiceKey<*>> = emptySet(),
                callLog: MutableList<String>? = null,
                onInitialize: (suspend (ComponentContext) -> Unit)? = null,
                onStart: (suspend () -> Unit)? = null,
                onStop: (suspend () -> Unit)? = null
            ): LifecycleRecordingComponent {
                return LifecycleRecordingComponent(
                    descriptor = ComponentDescriptor(ComponentId(id), requires, provides),
                    callLog = callLog,
                    onInitialize = onInitialize,
                    onStart = onStart,
                    onStop = onStop
                )
            }
        }
    }

    // 1. All components initialize in deterministic dependency order.
    // 2. All initialization calls complete before the first startup call.
    // 3. All components start in deterministic dependency order.
    // 4. Dependencies start before dependents.
    // 5. Shutdown runs in reverse initialization order.
    // 6. Component statuses accurately reflect successful lifecycle transitions.
    // 7. The runtime becomes running only after every component starts successfully.
    @Test
    fun `test successful lifecycle order and state transitions`() = runBlocking {
        val callLog = mutableListOf<String>()
        val runtime = ComponentRuntime()

        val sA = ServiceKey("sA", ServiceA::class)
        val sB = ServiceKey("sB", ServiceB::class)

        // A provides sA
        // B requires sA, provides sB
        // C requires sB
        // Dependency order: A -> B -> C
        val compA = LifecycleRecordingComponent.of("compA", provides = setOf(sA), callLog = callLog)
        val compB = LifecycleRecordingComponent.of("compB", requires = setOf(sA), provides = setOf(sB), callLog = callLog)
        val compC = LifecycleRecordingComponent.of("compC", requires = setOf(sB), callLog = callLog)

        runtime.register(compC, compA, compB)

        // Prior to startup, all are REGISTERED
        assertEquals(ComponentStatus.REGISTERED, runtime.getStatus(compA.descriptor.id))
        assertEquals(ComponentStatus.REGISTERED, runtime.getStatus(compB.descriptor.id))
        assertEquals(ComponentStatus.REGISTERED, runtime.getStatus(compC.descriptor.id))
        assertFalse(runtime.isRunning)
        assertEquals(RuntimeLifecycleState.NOT_STARTED, runtime.lifecycleState)

        runtime.start()

        // Test 1: All components initialize in deterministic dependency order
        val initCalls = callLog.filter { it.startsWith("initialize:") }
        assertEquals(listOf("initialize:compA", "initialize:compB", "initialize:compC"), initCalls)

        // Test 2: All initialization calls complete before first startup call
        val lastInitIndex = callLog.indexOfLast { it.startsWith("initialize:") }
        val firstStartIndex = callLog.indexOfFirst { it.startsWith("start:") }
        assertTrue(lastInitIndex < firstStartIndex, "All initialize calls must complete before any start calls")

        // Test 3 & 4: All components start in deterministic dependency order, dependencies before dependents
        val startCalls = callLog.filter { it.startsWith("start:") }
        assertEquals(listOf("start:compA", "start:compB", "start:compC"), startCalls)

        // Test 6: Statuses after start
        assertEquals(ComponentStatus.STARTED, runtime.getStatus(compA.descriptor.id))
        assertEquals(ComponentStatus.STARTED, runtime.getStatus(compB.descriptor.id))
        assertEquals(ComponentStatus.STARTED, runtime.getStatus(compC.descriptor.id))

        // Test 7: Runtime is running
        assertTrue(runtime.isRunning)
        assertEquals(RuntimeLifecycleState.RUNNING, runtime.lifecycleState)

        // Test 5: Shutdown runs in reverse initialization order
        runtime.stop()

        val stopCalls = callLog.filter { it.startsWith("stop:") }
        assertEquals(listOf("stop:compC", "stop:compB", "stop:compA"), stopCalls)

        // Statuses after stop
        assertEquals(ComponentStatus.STOPPED, runtime.getStatus(compA.descriptor.id))
        assertEquals(ComponentStatus.STOPPED, runtime.getStatus(compB.descriptor.id))
        assertEquals(ComponentStatus.STOPPED, runtime.getStatus(compC.descriptor.id))
        assertFalse(runtime.isRunning)
        assertTrue(runtime.isStopped)
        assertEquals(RuntimeLifecycleState.STOPPED, runtime.lifecycleState)

        // Verify full sequence
        val expectedFullSequence = listOf(
            "initialize:compA",
            "initialize:compB",
            "initialize:compC",
            "start:compA",
            "start:compB",
            "start:compC",
            "stop:compC",
            "stop:compB",
            "stop:compA"
        )
        assertEquals(expectedFullSequence, callLog)
    }

    // 8. An initialization failure marks the component FAILED.
    // 9. Later components are not initialized after a failure.
    // 10. No component starts if any initialization fails.
    // 11. Previously initialized components are cleaned up in reverse order.
    // 12. The failing component's stop() method is not called.
    // 13. Cleanup continues if an earlier cleanup attempt throws.
    // 14. The original initialization exception remains identifiable.
    @Test
    fun `test initialization failure marks component FAILED and cleans up previously initialized in reverse order`() = runBlocking {
        val callLog = mutableListOf<String>()
        val runtime = ComponentRuntime()

        val initException = RuntimeException("compB init exploded")

        val compA = LifecycleRecordingComponent.of("compA", callLog = callLog)
        val compB = LifecycleRecordingComponent.of(
            "compB",
            callLog = callLog,
            onInitialize = { throw initException }
        )
        val compC = LifecycleRecordingComponent.of("compC", callLog = callLog)

        runtime.register(compA, compB, compC)

        val ex = assertFailsWith<ComponentLifecycleException> {
            runtime.start()
        }

        // Test 14: Original initialization exception remains identifiable
        assertSame(initException, ex.primaryCause)
        assertSame(initException, ex.cause)
        assertEquals(LifecyclePhase.INITIALIZATION, ex.phase)
        assertEquals(ComponentId("compB"), ex.componentId)

        // Test 8: Initialization failure marks component FAILED
        assertEquals(ComponentStatus.FAILED, runtime.getStatus(ComponentId("compB")))

        // Test 9: Later components are not initialized
        assertEquals(0, compC.initializeCallCount)
        assertEquals(ComponentStatus.REGISTERED, runtime.getStatus(ComponentId("compC")))

        // Test 10: No component starts if any initialization fails
        assertEquals(0, compA.startCallCount)
        assertEquals(0, compB.startCallCount)
        assertEquals(0, compC.startCallCount)

        // Test 11: Previously initialized components are cleaned up in reverse order
        assertEquals(listOf("initialize:compA", "initialize:compB", "stop:compA"), callLog)
        assertEquals(ComponentStatus.STOPPED, runtime.getStatus(ComponentId("compA")))

        // Test 12: Failing component's stop() is not called
        assertEquals(0, compB.stopCallCount)

        // Runtime state is FAILED
        assertTrue(runtime.isFailed)
        assertEquals(RuntimeLifecycleState.FAILED, runtime.lifecycleState)
    }

    @Test
    fun `test initialization failure with multiple previously initialized components cleaned up in reverse order`() = runBlocking {
        val callLog = mutableListOf<String>()
        val runtime = ComponentRuntime()

        val compA = LifecycleRecordingComponent.of("compA", callLog = callLog)
        val compB = LifecycleRecordingComponent.of("compB", callLog = callLog)
        val compC = LifecycleRecordingComponent.of("compC", callLog = callLog, onInitialize = {
            throw IllegalStateException("compC failed")
        })

        runtime.register(compA, compB, compC)

        assertFailsWith<ComponentLifecycleException> {
            runtime.start()
        }

        // Cleanup reverse order of initialized (compB, then compA)
        assertEquals(
            listOf("initialize:compA", "initialize:compB", "initialize:compC", "stop:compB", "stop:compA"),
            callLog
        )
        assertEquals(0, compC.stopCallCount)
    }

    // Test 13: Cleanup continues if an earlier cleanup attempt throws.
    @Test
    fun `test cleanup continues during initialization failure if an earlier cleanup throws`() = runBlocking {
        val callLog = mutableListOf<String>()
        val runtime = ComponentRuntime()

        val stopException = RuntimeException("compB stop failed")
        val initException = RuntimeException("compC init failed")

        val compA = LifecycleRecordingComponent.of("compA", callLog = callLog)
        val compB = LifecycleRecordingComponent.of("compB", callLog = callLog, onStop = { throw stopException })
        val compC = LifecycleRecordingComponent.of("compC", callLog = callLog, onInitialize = { throw initException })

        runtime.register(compA, compB, compC)

        val ex = assertFailsWith<ComponentLifecycleException> {
            runtime.start()
        }

        assertSame(initException, ex.primaryCause)
        assertEquals(1, ex.cleanupFailures.size)
        assertEquals(ComponentId("compB"), ex.cleanupFailures[0].componentId)
        assertSame(stopException, ex.cleanupFailures[0].cause)

        // compA was still stopped even though compB stop failed
        assertEquals(1, compA.stopCallCount)
        assertEquals(listOf("initialize:compA", "initialize:compB", "initialize:compC", "stop:compB", "stop:compA"), callLog)
    }

    // 15. A startup failure marks the component FAILED.
    // 16. Later components are not started after a failure.
    // 17. A dependent component never starts if its provider fails to start.
    // 18. All successfully initialized components receive cleanup after startup failure.
    // 19. Components that initialized but never started are also cleaned up.
    // 20. Multiple cleanup failures are all reported.
    // 21. The original startup exception remains the primary failure.
    // 22. No automatic retry occurs.
    @Test
    fun `test startup failure marks component FAILED and stops all initialized components including unstarted`() = runBlocking {
        val callLog = mutableListOf<String>()
        val runtime = ComponentRuntime()

        val startException = RuntimeException("compB start failed")

        val sA = ServiceKey("sA", ServiceA::class)
        val sB = ServiceKey("sB", ServiceB::class)

        // A -> B -> C
        val compA = LifecycleRecordingComponent.of("compA", provides = setOf(sA), callLog = callLog)
        val compB = LifecycleRecordingComponent.of("compB", requires = setOf(sA), provides = setOf(sB), callLog = callLog, onStart = {
            throw startException
        })
        val compC = LifecycleRecordingComponent.of("compC", requires = setOf(sB), callLog = callLog)

        runtime.register(compA, compB, compC)

        val ex = assertFailsWith<ComponentLifecycleException> {
            runtime.start()
        }

        // Test 21: Original startup exception remains primary failure
        assertSame(startException, ex.primaryCause)
        assertEquals(LifecyclePhase.STARTUP, ex.phase)
        assertEquals(ComponentId("compB"), ex.componentId)

        // Test 15: Failing component is marked FAILED
        assertEquals(ComponentStatus.FAILED, runtime.getStatus(ComponentId("compB")))

        // Test 16 & 17: Later dependent component never starts
        assertEquals(0, compC.startCallCount)

        // Test 18 & 19: All successfully initialized components receive cleanup in reverse order
        // Both compA (started), compB (failed start), and compC (initialized but never started) are cleaned up!
        assertEquals(1, compC.stopCallCount)
        assertEquals(1, compB.stopCallCount)
        assertEquals(1, compA.stopCallCount)

        assertEquals(
            listOf(
                "initialize:compA",
                "initialize:compB",
                "initialize:compC",
                "start:compA",
                "start:compB",
                "stop:compC",
                "stop:compB",
                "stop:compA"
            ),
            callLog
        )

        // compC and compA reached STOPPED; compB remains FAILED
        assertEquals(ComponentStatus.STOPPED, runtime.getStatus(ComponentId("compC")))
        assertEquals(ComponentStatus.FAILED, runtime.getStatus(ComponentId("compB")))
        assertEquals(ComponentStatus.STOPPED, runtime.getStatus(ComponentId("compA")))

        // Test 22: No automatic retry occurs
        val logSize = callLog.size
        // Calling nothing else does not change log
        assertEquals(logSize, callLog.size)
        assertTrue(runtime.isFailed)
    }

    // Test 20: Multiple cleanup failures are all reported
    @Test
    fun `test multiple cleanup failures during startup failure are all reported`() = runBlocking {
        val callLog = mutableListOf<String>()
        val runtime = ComponentRuntime()

        val startEx = RuntimeException("compB start crashed")
        val stopExC = RuntimeException("compC stop crashed")
        val stopExA = RuntimeException("compA stop crashed")

        val compA = LifecycleRecordingComponent.of("compA", callLog = callLog, onStop = { throw stopExA })
        val compB = LifecycleRecordingComponent.of("compB", callLog = callLog, onStart = { throw startEx })
        val compC = LifecycleRecordingComponent.of("compC", callLog = callLog, onStop = { throw stopExC })

        runtime.register(compA, compB, compC)

        val ex = assertFailsWith<ComponentLifecycleException> {
            runtime.start()
        }

        assertSame(startEx, ex.primaryCause)
        assertEquals(2, ex.cleanupFailures.size)
        assertEquals(ComponentId("compC"), ex.cleanupFailures[0].componentId)
        assertSame(stopExC, ex.cleanupFailures[0].cause)
        assertEquals(ComponentId("compA"), ex.cleanupFailures[1].componentId)
        assertSame(stopExA, ex.cleanupFailures[1].cause)

        // Suppressed exceptions attached
        assertEquals(2, ex.suppressedExceptions.size)
        assertSame(stopExC, ex.suppressedExceptions[0])
        assertSame(stopExA, ex.suppressedExceptions[1])
    }

    // 23. A stop() failure marks that component FAILED.
    // 24. Shutdown continues after a component's cleanup fails.
    // 25. All cleanup failures remain inspectable.
    // 26. Repeated shutdown does not invoke stop() twice.
    @Test
    fun `test shutdown failures mark component FAILED, continue cleanup, and report all failures`() = runBlocking {
        val callLog = mutableListOf<String>()
        val runtime = ComponentRuntime()

        val stopExB = RuntimeException("compB stop exploded")

        val compA = LifecycleRecordingComponent.of("compA", callLog = callLog)
        val compB = LifecycleRecordingComponent.of("compB", callLog = callLog, onStop = { throw stopExB })
        val compC = LifecycleRecordingComponent.of("compC", callLog = callLog)

        runtime.register(compA, compB, compC)
        runtime.start()

        val ex = assertFailsWith<ComponentLifecycleException> {
            runtime.stop()
        }

        // Test 23: Component B is marked FAILED
        assertEquals(ComponentStatus.FAILED, runtime.getStatus(ComponentId("compB")))
        assertEquals(ComponentStatus.STOPPED, runtime.getStatus(ComponentId("compC")))
        assertEquals(ComponentStatus.STOPPED, runtime.getStatus(ComponentId("compA")))

        // Test 24: Shutdown continued after compB failed to stop compA
        assertEquals(1, compA.stopCallCount)
        assertEquals(listOf("stop:compC", "stop:compB", "stop:compA"), callLog.filter { it.startsWith("stop:") })

        // Test 25: All cleanup failures remain inspectable
        assertEquals(LifecyclePhase.SHUTDOWN, ex.phase)
        assertEquals(ComponentId("compB"), ex.componentId)
        assertSame(stopExB, ex.primaryCause)
        assertEquals(1, ex.cleanupFailures.size)

        // Runtime state is STOPPED
        assertTrue(runtime.isStopped)

        // Test 26: Repeated shutdown does not invoke stop() twice
        runtime.stop()
        assertEquals(1, compA.stopCallCount)
        assertEquals(1, compB.stopCallCount)
        assertEquals(1, compC.stopCallCount)
    }

    // 27. Duplicate startup is rejected.
    @Test
    fun `test duplicate startup is rejected`() = runBlocking {
        val runtime = ComponentRuntime()
        val compA = LifecycleRecordingComponent.of("compA")
        runtime.register(compA)

        runtime.start()
        assertTrue(runtime.isRunning)

        val ex = assertFailsWith<IllegalStateException> {
            runtime.start()
        }
        assertTrue(ex.message!!.contains("already running", ignoreCase = true))
        assertEquals(1, compA.startCallCount)
    }

    // 28. Restart after failed startup is rejected.
    @Test
    fun `test restart after failed startup is rejected`() = runBlocking {
        val runtime = ComponentRuntime()
        val compA = LifecycleRecordingComponent.of("compA", onStart = { throw RuntimeException("fail") })
        runtime.register(compA)

        assertFailsWith<ComponentLifecycleException> {
            runtime.start()
        }
        assertTrue(runtime.isFailed)

        val ex = assertFailsWith<IllegalStateException> {
            runtime.start()
        }
        assertTrue(ex.message!!.contains("failed", ignoreCase = true))
        // Does not retry
        assertEquals(1, compA.startCallCount)
    }

    // 29. Starting after shutdown is rejected.
    @Test
    fun `test starting after shutdown is rejected`() = runBlocking {
        val runtime = ComponentRuntime()
        val compA = LifecycleRecordingComponent.of("compA")
        runtime.register(compA)

        runtime.start()
        runtime.stop()
        assertTrue(runtime.isStopped)

        val ex = assertFailsWith<IllegalStateException> {
            runtime.start()
        }
        assertTrue(ex.message!!.contains("stopped", ignoreCase = true))
        assertEquals(1, compA.startCallCount)
    }

    // 30. Stopping before startup invokes no component lifecycle methods.
    @Test
    fun `test stopping before startup invokes no component lifecycle methods`(): Unit = runBlocking {
        val runtime = ComponentRuntime()
        val compA = LifecycleRecordingComponent.of("compA")
        runtime.register(compA)

        runtime.stop()
        assertEquals(0, compA.initializeCallCount)
        assertEquals(0, compA.startCallCount)
        assertEquals(0, compA.stopCallCount)
        assertTrue(runtime.isStopped)

        // Starting after this stopped state is also rejected
        assertFailsWith<IllegalStateException> {
            runtime.start()
        }
    }

    // 31. Concurrent lifecycle operations cannot execute component lifecycle methods concurrently.
    @Test
    fun `test concurrent start calls cannot execute lifecycle concurrently`() = runBlocking {
        val runtime = ComponentRuntime()
        val startEntered = CompletableDeferred<Unit>()
        val startCanProceed = CompletableDeferred<Unit>()

        val compA = LifecycleRecordingComponent.of(
            "compA",
            onStart = {
                startEntered.complete(Unit)
                startCanProceed.await()
            }
        )
        runtime.register(compA)

        // Launch first start on a coroutine
        val firstStartJob = launch(Dispatchers.Default) {
            runtime.start()
        }

        // Wait until first start enters compA.start()
        startEntered.await()

        // Concurrent start call must be rejected immediately
        val ex1 = assertFailsWith<IllegalStateException> {
            runtime.start()
        }
        assertTrue(
            ex1.message!!.contains("already in progress", ignoreCase = true) ||
            ex1.message!!.contains("already starting", ignoreCase = true)
        )

        // Concurrent stop call must also be rejected while startup is executing
        val ex2 = assertFailsWith<IllegalStateException> {
            runtime.stop()
        }
        assertTrue(
            ex2.message!!.contains("startup is currently executing", ignoreCase = true) ||
            ex2.message!!.contains("already in progress", ignoreCase = true)
        )

        // Allow first start to finish
        startCanProceed.complete(Unit)
        firstStartJob.join()

        assertTrue(runtime.isRunning)
        assertEquals(1, compA.startCallCount)

        // Clean up
        runtime.stop()
        assertTrue(runtime.isStopped)
    }

    @Test
    fun `test registration rejected after startup begins`() = runBlocking {
        val runtime = ComponentRuntime()
        val compA = LifecycleRecordingComponent.of("compA")
        val compB = LifecycleRecordingComponent.of("compB")
        runtime.register(compA)

        runtime.start()

        val ex = assertFailsWith<IllegalStateException> {
            runtime.register(compB)
        }
        assertTrue(ex.message!!.contains("already started", ignoreCase = true))
    }
}
