package com.ultron.component

import kotlinx.coroutines.sync.Mutex
import java.util.PriorityQueue

enum class RuntimeLifecycleState {
    NOT_STARTED,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED
}

class ComponentRuntime() {

    constructor(components: Iterable<AppComponent>) : this() {
        register(components)
    }

    constructor(vararg components: AppComponent) : this(components.asIterable())

    private val componentsById = LinkedHashMap<ComponentId, AppComponent>()
    private val serviceProviders = HashMap<String, ComponentId>()
    private val serviceTypes = HashMap<String, ServiceKey<*>>()

    private val componentStatuses = LinkedHashMap<ComponentId, ComponentStatus>()
    private val initializedComponents = mutableListOf<AppComponent>()
    private val lifecycleMutex = Mutex()

    @Volatile
    private var state = RuntimeLifecycleState.NOT_STARTED

    val size: Int get() = componentsById.size

    val components: List<AppComponent> get() = componentsById.values.toList()

    val registeredComponents: Map<ComponentId, AppComponent> get() = componentsById.toMap()

    val statuses: Map<ComponentId, ComponentStatus> get() = componentStatuses.toMap()

    val componentStatusesMap: Map<ComponentId, ComponentStatus> get() = componentStatuses.toMap()

    val isRunning: Boolean get() = state == RuntimeLifecycleState.RUNNING

    val isStopped: Boolean get() = state == RuntimeLifecycleState.STOPPED

    val isFailed: Boolean get() = state == RuntimeLifecycleState.FAILED

    val lifecycleState: RuntimeLifecycleState get() = state

    fun getStatus(id: ComponentId): ComponentStatus? = componentStatuses[id]

    fun getComponent(id: ComponentId): AppComponent? = componentsById[id]

    fun getDescriptor(id: ComponentId): ComponentDescriptor? = componentsById[id]?.descriptor

    fun register(component: AppComponent): ComponentRuntime {
        if (state != RuntimeLifecycleState.NOT_STARTED) {
            throw IllegalStateException(
                "Cannot register component '${component.descriptor.id.value}': runtime has already started (state: $state)"
            )
        }

        val descriptor = component.descriptor
        val componentId = descriptor.id

        // 1. Reject duplicate component IDs
        if (componentsById.containsKey(componentId)) {
            throw DuplicateComponentException(componentId)
        }

        // 2. Validate descriptor integrity
        validateDescriptor(descriptor)

        // 3. Reject duplicate service providers
        for (prov in descriptor.provides) {
            val existingProvider = serviceProviders[prov.id]
            if (existingProvider != null) {
                throw DuplicateProviderException(
                    serviceId = prov.id,
                    existingProvider = existingProvider,
                    conflictingProvider = componentId
                )
            }
        }

        // 4. Reject incompatible service-key types for the same service ID
        for (prov in descriptor.provides) {
            checkServiceTypeCompatibility(prov, componentId)
        }
        for (req in descriptor.requires) {
            checkServiceTypeCompatibility(req, componentId)
        }

        // 5. Store registration
        componentsById[componentId] = component
        componentStatuses[componentId] = ComponentStatus.REGISTERED

        for (prov in descriptor.provides) {
            serviceProviders[prov.id] = componentId
            serviceTypes[prov.id] = prov
        }

        for (req in descriptor.requires) {
            if (!serviceTypes.containsKey(req.id)) {
                serviceTypes[req.id] = req
            }
        }

        return this
    }

    fun register(vararg components: AppComponent): ComponentRuntime {
        for (comp in components) {
            register(comp)
        }
        return this
    }

    fun register(components: Iterable<AppComponent>): ComponentRuntime {
        for (comp in components) {
            register(comp)
        }
        return this
    }

    private fun checkServiceTypeCompatibility(key: ServiceKey<*>, componentId: ComponentId) {
        val existing = serviceTypes[key.id]
        if (existing != null && existing.type != key.type) {
            throw ConflictingServiceTypeException(
                serviceId = key.id,
                existingType = existing.type,
                conflictingType = key.type,
                componentId = componentId
            )
        }
    }

    private fun validateDescriptor(descriptor: ComponentDescriptor) {
        val reqIds = mutableSetOf<String>()
        for (req in descriptor.requires) {
            require(req.id.isNotBlank()) { "Service key ID must not be blank" }
            if (!reqIds.add(req.id)) {
                throw InvalidDescriptorException(
                    descriptor.id,
                    "Component '${descriptor.id.value}' declares duplicate service ID '${req.id}' in requires"
                )
            }
        }

        val provIds = mutableSetOf<String>()
        for (prov in descriptor.provides) {
            require(prov.id.isNotBlank()) { "Service key ID must not be blank" }
            if (!provIds.add(prov.id)) {
                throw InvalidDescriptorException(
                    descriptor.id,
                    "Component '${descriptor.id.value}' declares duplicate service ID '${prov.id}' in provides"
                )
            }
        }

        for (provId in provIds) {
            if (reqIds.contains(provId)) {
                throw SelfDependencyException(descriptor.id, provId)
            }
        }
    }

    fun validate(): ComponentRuntime {
        // 1. Validate that all required services have a provider and not self
        for (component in componentsById.values) {
            val consumerId = component.descriptor.id
            for (req in component.descriptor.requires) {
                val providerId = serviceProviders[req.id]
                    ?: throw MissingProviderException(
                        serviceId = req.id,
                        requiredBy = consumerId
                    )

                if (providerId == consumerId) {
                    throw SelfDependencyException(consumerId, req.id)
                }
            }
        }

        // 2. Validate acyclic graph and compute order
        computeTopologicalOrder()

        return this
    }

    fun resolveExecutionOrder(): List<ComponentId> {
        validate()
        return computeTopologicalOrder()
    }

    fun resolveComponentOrder(): List<AppComponent> {
        val order = resolveExecutionOrder()
        return order.map { componentsById[it]!! }
    }

    private fun computeTopologicalOrder(): List<ComponentId> {
        val inDegree = mutableMapOf<ComponentId, Int>()
        val adjacency = mutableMapOf<ComponentId, MutableSet<ComponentId>>()

        for (id in componentsById.keys) {
            inDegree[id] = 0
            adjacency[id] = mutableSetOf()
        }

        for (component in componentsById.values) {
            val consumerId = component.descriptor.id
            for (req in component.descriptor.requires) {
                val providerId = serviceProviders[req.id] ?: continue
                if (providerId != consumerId) {
                    if (adjacency[providerId]!!.add(consumerId)) {
                        inDegree[consumerId] = inDegree[consumerId]!! + 1
                    }
                }
            }
        }

        // Check for cycle before topological sort to provide clear cycle diagnostics
        val cycle = findCycle(componentsById.keys, adjacency)
        if (cycle != null) {
            throw CircularDependencyException(cycle)
        }

        // Kahn's algorithm with min-heap for deterministic tie-breaking
        val pq = PriorityQueue<ComponentId>()
        for ((id, deg) in inDegree) {
            if (deg == 0) {
                pq.add(id)
            }
        }

        val result = mutableListOf<ComponentId>()
        while (pq.isNotEmpty()) {
            val current = pq.poll()
            result.add(current)

            for (neighbor in adjacency[current] ?: emptySet()) {
                val newDeg = inDegree[neighbor]!! - 1
                inDegree[neighbor] = newDeg
                if (newDeg == 0) {
                    pq.add(neighbor)
                }
            }
        }

        if (result.size < componentsById.size) {
            val remainingCycle = findCycle(componentsById.keys.filter { it !in result }, adjacency)
                ?: componentsById.keys.filter { it !in result }
            throw CircularDependencyException(remainingCycle)
        }

        return result
    }

    private fun findCycle(
        nodes: Collection<ComponentId>,
        adjacency: Map<ComponentId, Set<ComponentId>>
    ): List<ComponentId>? {
        val state = mutableMapOf<ComponentId, Int>() // 0: unvisited, 1: visiting, 2: visited
        val path = mutableListOf<ComponentId>()

        fun dfs(current: ComponentId): List<ComponentId>? {
            state[current] = 1
            path.add(current)

            val neighbors = adjacency[current]?.sorted() ?: emptyList()
            for (neighbor in neighbors) {
                val neighborState = state[neighbor] ?: 0
                if (neighborState == 1) {
                    val cycleStartIndex = path.indexOf(neighbor)
                    val cycle = path.subList(cycleStartIndex, path.size).toMutableList()
                    cycle.add(neighbor)
                    return cycle
                } else if (neighborState == 0) {
                    val cycle = dfs(neighbor)
                    if (cycle != null) return cycle
                }
            }

            path.removeAt(path.size - 1)
            state[current] = 2
            return null
        }

        for (node in nodes.sorted()) {
            if ((state[node] ?: 0) == 0) {
                val cycle = dfs(node)
                if (cycle != null) return cycle
            }
        }
        return null
    }

    suspend fun start() {
        if (!lifecycleMutex.tryLock()) {
            if (state == RuntimeLifecycleState.STARTING) {
                throw IllegalStateException("Cannot start runtime: startup is already in progress")
            }
            if (state == RuntimeLifecycleState.STOPPING) {
                throw IllegalStateException("Cannot start runtime: shutdown is currently in progress")
            }
            throw IllegalStateException("Lifecycle operation already in progress")
        }
        try {
            when (state) {
                RuntimeLifecycleState.RUNNING -> throw IllegalStateException("Runtime is already running")
                RuntimeLifecycleState.STARTING -> throw IllegalStateException("Runtime is already starting")
                RuntimeLifecycleState.STOPPING -> throw IllegalStateException("Cannot start runtime: runtime is currently stopping")
                RuntimeLifecycleState.STOPPED -> throw IllegalStateException("Cannot start runtime: restarting a stopped runtime is not supported")
                RuntimeLifecycleState.FAILED -> throw IllegalStateException("Cannot start runtime: retrying a failed runtime is not supported")
                RuntimeLifecycleState.NOT_STARTED -> {
                    state = RuntimeLifecycleState.STARTING
                }
            }

            val executionOrder = resolveExecutionOrder()
            val componentsInOrder = executionOrder.map { componentsById[it]!! }

            // Phase A: Initialize
            for (component in componentsInOrder) {
                val componentId = component.descriptor.id
                componentStatuses[componentId] = ComponentStatus.INITIALIZING

                val context = DefaultComponentContext(componentId)
                try {
                    component.initialize(context)
                    componentStatuses[componentId] = ComponentStatus.INITIALIZED
                    initializedComponents.add(component)
                } catch (t: Throwable) {
                    componentStatuses[componentId] = ComponentStatus.FAILED
                    state = RuntimeLifecycleState.FAILED

                    val cleanupFailures = cleanup(initializedComponents.asReversed(), failingComponentId = componentId)
                    throw ComponentLifecycleException(
                        componentId = componentId,
                        phase = LifecyclePhase.INITIALIZATION,
                        primaryCause = t,
                        cleanupFailures = cleanupFailures
                    )
                }
            }

            // Phase B: Start
            for (component in componentsInOrder) {
                val componentId = component.descriptor.id

                // 1. Verify that each declared dependency has status STARTED
                for (req in component.descriptor.requires) {
                    val providerId = serviceProviders[req.id]!!
                    val providerStatus = componentStatuses[providerId]
                    if (providerStatus != ComponentStatus.STARTED) {
                        val depFailure = IllegalStateException(
                            "Declared dependency '${providerId.value}' for component '${componentId.value}' is in status $providerStatus, expected STARTED"
                        )
                        componentStatuses[componentId] = ComponentStatus.FAILED
                        state = RuntimeLifecycleState.FAILED
                        val cleanupFailures = cleanup(initializedComponents.asReversed(), failingComponentId = componentId)
                        throw ComponentLifecycleException(
                            componentId = componentId,
                            phase = LifecyclePhase.STARTUP,
                            primaryCause = depFailure,
                            cleanupFailures = cleanupFailures
                        )
                    }
                }

                componentStatuses[componentId] = ComponentStatus.STARTING
                try {
                    component.start()
                    componentStatuses[componentId] = ComponentStatus.STARTED
                } catch (t: Throwable) {
                    componentStatuses[componentId] = ComponentStatus.FAILED
                    state = RuntimeLifecycleState.FAILED

                    val cleanupFailures = cleanup(initializedComponents.asReversed(), failingComponentId = componentId)
                    throw ComponentLifecycleException(
                        componentId = componentId,
                        phase = LifecyclePhase.STARTUP,
                        primaryCause = t,
                        cleanupFailures = cleanupFailures
                    )
                }
            }

            state = RuntimeLifecycleState.RUNNING
        } finally {
            lifecycleMutex.unlock()
        }
    }

    suspend fun stop() {
        if (!lifecycleMutex.tryLock()) {
            if (state == RuntimeLifecycleState.STARTING) {
                throw IllegalStateException("Cannot stop runtime: startup is currently executing")
            }
            if (state == RuntimeLifecycleState.STOPPING) {
                throw IllegalStateException("Cannot stop runtime: shutdown is already in progress")
            }
            throw IllegalStateException("Lifecycle operation already in progress")
        }
        try {
            when (state) {
                RuntimeLifecycleState.STARTING -> {
                    throw IllegalStateException("Cannot stop runtime: startup is currently executing")
                }
                RuntimeLifecycleState.STOPPING -> {
                    throw IllegalStateException("Cannot stop runtime: shutdown is already in progress")
                }
                RuntimeLifecycleState.NOT_STARTED -> {
                    state = RuntimeLifecycleState.STOPPED
                    return
                }
                RuntimeLifecycleState.STOPPED -> {
                    return
                }
                RuntimeLifecycleState.FAILED -> {
                    return
                }
                RuntimeLifecycleState.RUNNING -> {
                    state = RuntimeLifecycleState.STOPPING
                }
            }

            val cleanupFailures = cleanup(initializedComponents.asReversed())
            state = RuntimeLifecycleState.STOPPED

            if (cleanupFailures.isNotEmpty()) {
                val first = cleanupFailures.first()
                throw ComponentLifecycleException(
                    componentId = first.componentId,
                    phase = LifecyclePhase.SHUTDOWN,
                    primaryCause = first.cause,
                    cleanupFailures = cleanupFailures
                )
            }
        } finally {
            lifecycleMutex.unlock()
        }
    }

    private suspend fun cleanup(
        componentsToClean: List<AppComponent>,
        failingComponentId: ComponentId? = null
    ): List<CleanupFailure> {
        val failures = mutableListOf<CleanupFailure>()
        for (comp in componentsToClean) {
            val id = comp.descriptor.id
            val wasFailed = (id == failingComponentId)
            if (!wasFailed) {
                componentStatuses[id] = ComponentStatus.STOPPING
            }
            try {
                comp.stop()
                if (!wasFailed) {
                    componentStatuses[id] = ComponentStatus.STOPPED
                }
            } catch (t: Throwable) {
                componentStatuses[id] = ComponentStatus.FAILED
                failures.add(CleanupFailure(id, t))
            }
        }
        return failures
    }

    private class DefaultComponentContext(
        val componentId: ComponentId
    ) : ComponentContext {
        override fun <T : Any> requireService(key: ServiceKey<T>): T {
            throw UnsupportedOperationException("Service resolution is not implemented in this task")
        }

        override fun <T : Any> publishService(key: ServiceKey<T>, service: T) {
            // Service publication enforcement is implemented in Task 1.0.3
        }
    }

    companion object {
        fun builder(): ComponentRuntime = ComponentRuntime()
    }
}

