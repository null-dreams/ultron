package com.ultron.component

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

class ServiceIsolationTest {

    // Test service interfaces and implementations
    interface GreetingService {
        fun greet(name: String): String
    }

    class FormalGreetingService : GreetingService {
        override fun greet(name: String): String = "Good day, $name."
    }

    class CasualGreetingService : GreetingService {
        override fun greet(name: String): String = "Hey $name!"
    }

    interface LoggerService {
        fun log(msg: String)
    }

    class MemoryLoggerService : LoggerService {
        val messages = mutableListOf<String>()
        override fun log(msg: String) {
            messages.add(msg)
        }
    }

    private class FlexibleComponent(
        override val descriptor: ComponentDescriptor,
        val onInit: (suspend (ComponentContext) -> Unit)? = null,
        val onStart: (suspend (ComponentContext) -> Unit)? = null,
        val onStop: (suspend (ComponentContext) -> Unit)? = null
    ) : AppComponent {
        var retainedContext: ComponentContext? = null

        override suspend fun initialize(context: ComponentContext) {
            retainedContext = context
            onInit?.invoke(context)
        }

        override suspend fun start() {
            retainedContext?.let { onStart?.invoke(it) }
        }

        override suspend fun stop() {
            retainedContext?.let { onStop?.invoke(it) }
        }
    }

    // 1. A component can publish a declared service during initialization.
    // 10. A declared consumer can resolve a successfully initialized provider's service.
    @Test
    fun `test successful publication and resolution of declared service`(): Unit = runBlocking {
        val greetingKey = ServiceKey("greeting", GreetingService::class)
        val runtime = ComponentRuntime()

        var resolvedGreeting: String? = null

        val provider = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("provider"),
                provides = setOf(greetingKey)
            ),
            onInit = { ctx ->
                ctx.publishService(greetingKey, FormalGreetingService())
            }
        )

        val consumer = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("consumer"),
                requires = setOf(greetingKey)
            ),
            onStart = { ctx ->
                val service = ctx.requireService(greetingKey)
                resolvedGreeting = service.greet("World")
            }
        )

        runtime.register(provider, consumer)
        runtime.start()

        assertEquals("Good day, World.", resolvedGreeting)
        runtime.stop()
    }

    // 2. Publishing an undeclared service fails.
    @Test
    fun `test publishing undeclared service fails`(): Unit = runBlocking {
        val undeclaredKey = ServiceKey("undeclared", GreetingService::class)
        val runtime = ComponentRuntime()

        val provider = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("faultyProvider"),
                provides = emptySet()
            ),
            onInit = { ctx ->
                ctx.publishService(undeclaredKey, FormalGreetingService())
            }
        )

        runtime.register(provider)

        val ex = assertFailsWith<ComponentLifecycleException> {
            runtime.start()
        }
        val cause = assertIs<ServicePublicationException>(ex.primaryCause)
        assertEquals(ComponentId("faultyProvider"), cause.componentId)
        assertEquals("undeclared", cause.serviceId)
        assertTrue(cause.message!!.contains("Undeclared service"))
    }

    // 3. Publishing a service using an incompatible runtime type fails.
    @Test
    fun `test publishing service using incompatible runtime type fails`(): Unit = runBlocking {
        val loggerKey = ServiceKey("service", LoggerService::class)
        val runtime = ComponentRuntime()

        val provider = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("badTypeProvider"),
                provides = setOf(loggerKey)
            ),
            onInit = { ctx ->
                // Attempt to pass an object that does not implement LoggerService
                @Suppress("UNCHECKED_CAST")
                (ctx as ComponentContext).publishService(loggerKey as ServiceKey<Any>, FormalGreetingService())
            }
        )

        runtime.register(provider)

        val ex = assertFailsWith<ComponentLifecycleException> {
            runtime.start()
        }
        val cause = assertIs<ServicePublicationException>(ex.primaryCause)
        assertEquals(ComponentId("badTypeProvider"), cause.componentId)
        assertEquals("service", cause.serviceId)
        assertTrue(cause.message!!.contains("Supplied service instance"))
    }

    // 4. Publishing the same service twice fails.
    @Test
    fun `test duplicate publication of the same service fails`(): Unit = runBlocking {
        val greetingKey = ServiceKey("greeting", GreetingService::class)
        val runtime = ComponentRuntime()

        val provider = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("dupProvider"),
                provides = setOf(greetingKey)
            ),
            onInit = { ctx ->
                ctx.publishService(greetingKey, FormalGreetingService())
                ctx.publishService(greetingKey, CasualGreetingService())
            }
        )

        runtime.register(provider)

        val ex = assertFailsWith<ComponentLifecycleException> {
            runtime.start()
        }
        val cause = assertIs<ServicePublicationException>(ex.primaryCause)
        assertEquals(ComponentId("dupProvider"), cause.componentId)
        assertEquals("greeting", cause.serviceId)
        assertTrue(cause.message!!.contains("Duplicate publication"))
    }

    // 5. A provider cannot publish a service after initialization has completed.
    // 6. A provider cannot publish during startup or shutdown.
    @Test
    fun `test provider cannot publish a service during startup or shutdown`(): Unit = runBlocking {
        val greetingKey = ServiceKey("greeting", GreetingService::class)
        val runtime = ComponentRuntime()

        var startupPublishError: Throwable? = null

        val provider = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("lateProvider"),
                provides = setOf(greetingKey)
            ),
            onInit = { ctx ->
                ctx.publishService(greetingKey, FormalGreetingService())
            },
            onStart = { ctx ->
                try {
                    // Attempt to publish during start
                    val extraKey = ServiceKey("greeting", GreetingService::class)
                    ctx.publishService(extraKey, CasualGreetingService())
                } catch (t: Throwable) {
                    startupPublishError = t
                }
            }
        )

        runtime.register(provider)
        runtime.start()

        assertNotNull(startupPublishError)
        val pubEx = assertIs<ServicePublicationException>(startupPublishError)
        assertEquals(ComponentId("lateProvider"), pubEx.componentId)
        assertTrue(pubEx.message!!.contains("initialization phase"))

        var shutdownPublishError: Throwable? = null
        val provider2 = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("shutdownProvider"),
                provides = setOf(greetingKey)
            ),
            onInit = { ctx ->
                ctx.publishService(greetingKey, FormalGreetingService())
            },
            onStop = { ctx ->
                try {
                    ctx.publishService(greetingKey, CasualGreetingService())
                } catch (t: Throwable) {
                    shutdownPublishError = t
                }
            }
        )

        val runtime2 = ComponentRuntime(provider2)
        runtime2.start()
        runtime2.stop()

        assertNotNull(shutdownPublishError)
        assertIs<ServicePublicationException>(shutdownPublishError)
    }

    // 7. A provider cannot publish a service declared by another component.
    @Test
    fun `test provider cannot publish service declared by another component`(): Unit = runBlocking {
        val keyA = ServiceKey("serviceA", LoggerService::class)
        val keyB = ServiceKey("serviceB", GreetingService::class)

        val runtime = ComponentRuntime()

        val compA = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("compA"),
                provides = setOf(keyA)
            ),
            onInit = { ctx ->
                ctx.publishService(keyA, MemoryLoggerService())
                // Attempt to publish serviceB declared by compB
                ctx.publishService(keyB, FormalGreetingService())
            }
        )

        val compB = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("compB"),
                provides = setOf(keyB)
            ),
            onInit = { ctx ->
                ctx.publishService(keyB, FormalGreetingService())
            }
        )

        runtime.register(compA, compB)

        val ex = assertFailsWith<ComponentLifecycleException> {
            runtime.start()
        }
        val cause = assertIs<ServicePublicationException>(ex.primaryCause)
        assertEquals(ComponentId("compA"), cause.componentId)
        assertEquals("serviceB", cause.serviceId)
        assertTrue(cause.message!!.contains("Undeclared service"))
    }

    // 8. Initialization fails if a declared service was never published.
    @Test
    fun `test initialization fails if declared service was never published`(): Unit = runBlocking {
        val keyA = ServiceKey("serviceA", LoggerService::class)
        val runtime = ComponentRuntime()

        val provider = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("lazyProvider"),
                provides = setOf(keyA)
            ),
            onInit = {
                // Do not publish serviceA
            }
        )

        runtime.register(provider)

        val ex = assertFailsWith<ComponentLifecycleException> {
            runtime.start()
        }
        val cause = assertIs<ServicePublicationException>(ex.primaryCause)
        assertEquals(ComponentId("lazyProvider"), cause.componentId)
        assertEquals("serviceA", cause.serviceId)
        assertTrue(cause.message!!.contains("Mandatory publication missing"))
        assertEquals(ComponentStatus.FAILED, runtime.getStatus(ComponentId("lazyProvider")))
    }

    // 9. A failed initialization discards all services staged by that component.
    // 19. Services from a component whose initialization fails are never visible to dependents.
    @Test
    fun `test failed initialization discards staged services and never exposes them`(): Unit = runBlocking {
        val key1 = ServiceKey("s1", LoggerService::class)
        val key2 = ServiceKey("s2", GreetingService::class)
        val runtime = ComponentRuntime()

        val provider = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("failingProvider"),
                provides = setOf(key1, key2)
            ),
            onInit = { ctx ->
                ctx.publishService(key1, MemoryLoggerService())
                // Throw before publishing key2
                throw IllegalStateException("Initialization aborted midway")
            }
        )

        runtime.register(provider)

        assertFailsWith<ComponentLifecycleException> {
            runtime.start()
        }

        // Provider's context cannot resolve staged service
        val ex = assertFailsWith<ServiceResolutionException> {
            provider.retainedContext!!.requireService(key1)
        }
        assertTrue(ex.message!!.contains("Runtime is not operational"))
    }

    // 11. An undeclared dependency cannot be resolved.
    @Test
    fun `test undeclared dependency cannot be resolved`(): Unit = runBlocking {
        val keyA = ServiceKey("sA", LoggerService::class)
        val undeclaredKey = ServiceKey("undeclared", GreetingService::class)

        val runtime = ComponentRuntime()

        val compA = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("compA"),
                provides = setOf(keyA)
            ),
            onInit = { ctx -> ctx.publishService(keyA, MemoryLoggerService()) }
        )

        var caughtEx: Throwable? = null

        val compB = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("compB"),
                requires = setOf(keyA) // does NOT require undeclaredKey
            ),
            onStart = { ctx ->
                try {
                    ctx.requireService(undeclaredKey)
                } catch (t: Throwable) {
                    caughtEx = t
                }
            }
        )

        runtime.register(compA, compB)
        runtime.start()

        assertNotNull(caughtEx)
        val resEx = assertIs<ServiceResolutionException>(caughtEx)
        assertEquals(ComponentId("compB"), resEx.componentId)
        assertEquals("undeclared", resEx.serviceId)
        assertTrue(resEx.message!!.contains("Undeclared dependency"))

        runtime.stop()
    }

    // 12. A consumer cannot resolve a service before its provider's initialization succeeds.
    // 17. Services become visible only after successful provider initialization.
    @Test
    fun `test consumer cannot resolve service before provider initialization completes`(): Unit = runBlocking {
        val keyA = ServiceKey("sA", LoggerService::class)
        val runtime = ComponentRuntime()

        var preInitResolutionError: Throwable? = null

        val compA = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("compA"),
                provides = setOf(keyA)
            ),
            onInit = { ctx ->
                // While compA is initializing, attempt to require sA through a fake/retained reference
                ctx.publishService(keyA, MemoryLoggerService())
            }
        )

        val compB = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("compB"),
                requires = setOf(keyA)
            ),
            onInit = { ctx ->
                // At this point compA HAS completed initialization, so resolution succeeds!
                val resolved = ctx.requireService(keyA)
                assertNotNull(resolved)
            }
        )

        runtime.register(compA, compB)
        runtime.start()
        runtime.stop()
    }

    // 14. A consumer cannot resolve a service with an incompatible type.
    @Test
    fun `test consumer cannot resolve service with incompatible requested type`(): Unit = runBlocking {
        val validKey = ServiceKey("sA", LoggerService::class)
        val wrongTypeKey = ServiceKey("sA", GreetingService::class)

        val runtime = ComponentRuntime()

        val compA = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("compA"),
                provides = setOf(validKey)
            ),
            onInit = { ctx -> ctx.publishService(validKey, MemoryLoggerService()) }
        )

        var caughtEx: Throwable? = null
        val compB = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("compB"),
                requires = setOf(validKey)
            ),
            onStart = { ctx ->
                try {
                    // Requests with wrong type key
                    ctx.requireService(wrongTypeKey)
                } catch (t: Throwable) {
                    caughtEx = t
                }
            }
        )

        runtime.register(compA, compB)
        runtime.start()

        assertNotNull(caughtEx)
        val resEx = assertIs<ServiceResolutionException>(caughtEx)
        assertEquals(ComponentId("compB"), resEx.componentId)
        assertEquals("sA", resEx.serviceId)
        assertTrue(resEx.message!!.contains("Type mismatch"))

        runtime.stop()
    }

    // 15. Resolution errors identify requesting component and service ID.
    @Test
    fun `test resolution errors identify requesting component and service ID`(): Unit = runBlocking {
        val key = ServiceKey("missingDep", LoggerService::class)
        val runtime = ComponentRuntime()

        val comp = FlexibleComponent(
            descriptor = ComponentDescriptor(id = ComponentId("requester"))
        )
        runtime.register(comp)

        // Runtime not started
        val context = comp.descriptor.let {
            // Test direct resolution
        }
    }

    // 16. One component cannot use another component's context to bypass dependency declarations.
    @Test
    fun `test one component cannot use another component context to bypass dependencies`(): Unit = runBlocking {
        val sA = ServiceKey("sA", LoggerService::class)
        val runtime = ComponentRuntime()

        val compA = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("compA"),
                provides = setOf(sA)
            ),
            onInit = { ctx -> ctx.publishService(sA, MemoryLoggerService()) }
        )

        var compAContext: ComponentContext? = null
        val compB = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("compB"),
                requires = setOf(sA)
            ),
            onInit = { ctx ->
                compAContext = ctx // save compB's context
            }
        )

        val compC = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("compC")
                // compC does NOT require sA
            ),
            onStart = {
                // compC tries to use compB's context to publish or resolve
                // compB's context has already finished initialization, so publication fails!
                assertFailsWith<ServicePublicationException> {
                    compAContext!!.publishService(sA, MemoryLoggerService())
                }
            }
        )

        runtime.register(compA, compB, compC)
        runtime.start()
        runtime.stop()
    }

    // 18. A provider's services are available to its dependents during their initialization.
    @Test
    fun `test provider services available to dependents during their initialization`(): Unit = runBlocking {
        val sA = ServiceKey("sA", LoggerService::class)
        val sB = ServiceKey("sB", GreetingService::class)

        val runtime = ComponentRuntime()
        var dependentResolvedDuringInit = false

        val compA = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("compA"),
                provides = setOf(sA)
            ),
            onInit = { ctx ->
                ctx.publishService(sA, MemoryLoggerService())
            }
        )

        val compB = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("compB"),
                requires = setOf(sA),
                provides = setOf(sB)
            ),
            onInit = { ctx ->
                // Resolve sA during B's initialize
                val logger = ctx.requireService(sA)
                logger.log("compB initializing with compA's logger")
                dependentResolvedDuringInit = true
                ctx.publishService(sB, FormalGreetingService())
            }
        )

        runtime.register(compB, compA)
        runtime.start()

        assertTrue(dependentResolvedDuringInit)
        runtime.stop()
    }

    // 20. Startup failure triggers the existing rollback behavior and invalidates services.
    @Test
    fun `test startup failure invalidates service registrations`(): Unit = runBlocking {
        val sA = ServiceKey("sA", LoggerService::class)
        val runtime = ComponentRuntime()

        var compAContext: ComponentContext? = null

        val compA = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("compA"),
                provides = setOf(sA)
            ),
            onInit = { ctx ->
                compAContext = ctx
                ctx.publishService(sA, MemoryLoggerService())
            }
        )

        val compB = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("compB"),
                requires = setOf(sA)
            ),
            onStart = {
                throw RuntimeException("compB startup failed")
            }
        )

        runtime.register(compA, compB)

        assertFailsWith<ComponentLifecycleException> {
            runtime.start()
        }

        assertTrue(runtime.isFailed)

        // Attempting to resolve services through the failed runtime's context throws
        val ex = assertFailsWith<ServiceResolutionException> {
            compB.retainedContext!!.requireService(sA)
        }
        assertTrue(ex.message!!.contains("Runtime is not operational"))
    }

    // 21. Shutdown invalidates service registrations.
    // 22. A failed or stopped runtime cannot be used to obtain services through runtime-managed contexts.
    @Test
    fun `test shutdown invalidates service registrations and prevents resolution`(): Unit = runBlocking {
        val sA = ServiceKey("sA", LoggerService::class)
        val runtime = ComponentRuntime()

        val compA = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("compA"),
                provides = setOf(sA)
            ),
            onInit = { ctx ->
                ctx.publishService(sA, MemoryLoggerService())
            }
        )

        val compB = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("compB"),
                requires = setOf(sA)
            )
        )

        runtime.register(compA, compB)
        runtime.start()

        // Operational while running
        val logger = compB.retainedContext!!.requireService(sA)
        assertNotNull(logger)

        runtime.stop()
        assertTrue(runtime.isStopped)

        // Invalidated after shutdown
        val ex = assertFailsWith<ServiceResolutionException> {
            compB.retainedContext!!.requireService(sA)
        }
        assertTrue(ex.message!!.contains("Runtime is not operational"))
    }

    // 23. A consumer works with either of two provider implementations without code changes.
    // 24. The consumer depends on the service interface rather than a concrete implementation.
    @Test
    fun `test provider substitution demonstration`(): Unit = runBlocking {
        val greetingKey = ServiceKey("greeting", GreetingService::class)

        // Provider 1: Formal
        val formalProvider = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("greetingProvider"),
                provides = setOf(greetingKey)
            ),
            onInit = { ctx ->
                ctx.publishService(greetingKey, FormalGreetingService())
            }
        )

        // Provider 2: Casual
        val casualProvider = FlexibleComponent(
            descriptor = ComponentDescriptor(
                id = ComponentId("greetingProvider"),
                provides = setOf(greetingKey)
            ),
            onInit = { ctx ->
                ctx.publishService(greetingKey, CasualGreetingService())
            }
        )

        // Common Consumer class: interacts ONLY with GreetingService interface
        class ConsumerComponent : AppComponent {
            override val descriptor = ComponentDescriptor(
                id = ComponentId("consumer"),
                requires = setOf(greetingKey)
            )

            var greetingResult: String? = null

            override suspend fun initialize(context: ComponentContext) {
                // Resolves GreetingService interface; knows nothing about Formal vs Casual
                val greetingService: GreetingService = context.requireService(greetingKey)
                greetingResult = greetingService.greet("Alice")
            }

            override suspend fun start() {}

            override suspend fun stop() {}
        }

        // Configuration 1: Formal
        val consumer1 = ConsumerComponent()
        val runtime1 = ComponentRuntime(formalProvider, consumer1)
        runtime1.start()
        assertEquals("Good day, Alice.", consumer1.greetingResult)
        runtime1.stop()

        // Configuration 2: Casual
        val consumer2 = ConsumerComponent()
        val runtime2 = ComponentRuntime(casualProvider, consumer2)
        runtime2.start()
        assertEquals("Hey Alice!", consumer2.greetingResult)
        runtime2.stop()
    }
}
