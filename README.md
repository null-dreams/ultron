# Ultron
IoT Management System

## Component Runtime: Public Contracts & Validation Layer

The component runtime provides the core foundation for registering components, declaring service dependencies, validating the component dependency graph, and computing a deterministic execution order.

### Public Contracts

- **`ComponentId`**: Value class wrapping a non-blank, case-sensitive component identifier.
- **`ComponentStatus`**: Enum representing lifecycle phases (`REGISTERED`, `INITIALIZING`, `INITIALIZED`, `STARTING`, `STARTED`, `STOPPING`, `STOPPED`, `FAILED`).
- **`ServiceKey<T>`**: Identifies a service by a non-blank, case-sensitive ID and a strongly-typed `KClass<T>`.
- **`ComponentDescriptor`**: Immutable metadata declaring a component's identity (`ComponentId`), required services (`requires: Set<ServiceKey<*>>`), and provided services (`provides: Set<ServiceKey<*>>`).
- **`AppComponent`**: Contract for modular system components declaring a `descriptor` and lifecycle hooks (`initialize`, `start`, `stop`).
- **`ComponentContext`**: Runtime interface for components to publish and require services.
- **`ComponentRuntime`**: Configuration, validation, and lifecycle container for component registration and deterministic lifecycle management.

### Validation Rules

1. **Non-blank Identifiers**: Component IDs and service key IDs must not be blank. IDs are strictly case-sensitive.
2. **Descriptor Integrity**:
   - No duplicate service IDs within `requires`.
   - No duplicate service IDs within `provides`.
   - No service ID may appear in both `requires` and `provides` of the same component (a component cannot depend on a service it provides itself).
3. **Type Consistency**: A service ID must have a consistent declared type across the entire runtime configuration. Incompatible type declarations reusing the same service ID are rejected.
4. **Single-Provider Guarantee**:
   - Every service can have at most one provider across registered components (duplicate providers are rejected).
   - Every required service must have exactly one provider upon validation.
5. **Acyclic Dependency Graph**: The dependency graph ($A \to B$ where $B$ requires a service provided by $A$) must be acyclic. Two-component and longer circular dependencies are detected and rejected with diagnostic paths.

### Deterministic Execution Order

The execution order is resolved using a deterministic topological sort:
- For any dependency $A \to B$ ($B$ requires a service from $A$), $A$ precedes $B$.
- When multiple components are eligible for selection (in-degree = 0), ties are broken deterministically by choosing the lexicographically smallest `ComponentId` using case-sensitive ordering.
- The computed execution order is invariant to component registration order.

Component lifecycle methods (`initialize`, `start`, `stop`) are never invoked during graph validation or ordering.

---

## Lifecycle Orchestration

`ComponentRuntime` orchestrates component lifecycles sequentially through explicit, non-interleaved phases.

### Two-Phase Startup

Startup divides into two distinct phases using the deterministic dependency order:

1. **Phase A: Initialization**
   - For every component in dependency order, its status is set to `INITIALIZING`, and `initialize(context)` is invoked.
   - Upon success, status transitions to `INITIALIZED`.
   - No component starts until all components have initialized successfully.
   - If a component fails initialization, it is marked `FAILED`, later components are not initialized, no components are started, and previously initialized components are cleaned up in reverse initialization order. The failing component's `stop()` method is not invoked.
2. **Phase B: Startup**
   - Executed only after all components initialize.
   - For each component in dependency order, the runtime verifies that each declared dependency has reached status `STARTED`.
   - Its status transitions to `STARTING`, and `start()` is invoked.
   - Upon success, status transitions to `STARTED`.
   - If `start()` fails, that component is marked `FAILED`, later components are not started, and cleanup is executed for all successfully initialized components in reverse initialization order (including components that initialized but never started).

### Reverse-Order Shutdown

For a running runtime, shutdown via `stop()` iterates through all successfully initialized components in reverse initialization order ($C \to B \to A$ for dependency order $A \to B \to C$):
1. Status is set to `STOPPING`.
2. `stop()` is invoked.
3. Status transitions to `STOPPED` upon success, or `FAILED` if an exception is thrown.
4. If a component fails during shutdown, its error is recorded, and shutdown continues for all remaining components. All failures are reported upon completion.
5. Repeated calls to `stop()` after shutdown are no-ops and do not invoke `stop()` again.

### Startup Failure Recovery & Cleanup

- **Initialization Failure**: Only previously initialized components are stopped in reverse order. The failing component's `stop()` method is not called.
- **Startup Failure**: All successfully initialized components (both started components and initialized unstarted components) receive cleanup in reverse initialization order.
- **Failure Preservation**: The primary exception (from `initialize` or `start`) is preserved and thrown as a `ComponentLifecycleException`. Any cleanup failures are captured in `cleanupFailures` and attached as suppressed exceptions without obscuring the primary cause.

### Invalid Lifecycle Operations

| Operation | Behavior |
|---|---|
| Start on fresh runtime | Executes initialization and startup sequentially. State becomes `RUNNING`. |
| Start while running | Rejected with `IllegalStateException`; lifecycle methods are not re-executed. |
| Start after failed startup | Rejected with `IllegalStateException`; automatic retries are unsupported. |
| Start after shutdown | Rejected with `IllegalStateException`; restarting is unsupported. |
| Stop before startup | No component lifecycle methods are invoked; runtime transitions to `STOPPED`. |
| Stop while running | Shuts down initialized components in reverse order; runtime transitions to `STOPPED`. |
| Stop after shutdown | No-op; component lifecycle methods are not called again. |
| Stop while starting | Rejected with `IllegalStateException`. |
| Concurrent start/stop calls | Protected via non-blocking coroutine `Mutex`; concurrent lifecycle calls are immediately rejected. |
| Register after startup begins | Rejected with `IllegalStateException`. |

---

## Service Publication, Resolution & Isolation

The runtime provides strict service publication, resolution, and lifecycle visibility enforcement via `ComponentContext`.

### Service Publication

Components publish services during Phase A initialization using `ComponentContext.publishService(key, service)`:
- **Phase Restriction**: Publication is only permitted while the component's `initialize()` method is active. Calling `publishService` before initialization, after initialization completes, or during `start()` / `stop()` fails immediately with `ServicePublicationException`.
- **Declared Services Only**: The published `ServiceKey` must be declared in the component's `provides` set. Attempting to publish an undeclared service throws `ServicePublicationException`.
- **Runtime Type Safety**: The provided service instance is verified against the key's type (`key.type.isInstance(service)`). Supplying an instance incompatible with `key.type` throws `ServicePublicationException`.
- **Single Publication**: A component cannot publish the same service more than once. Re-publishing throws `ServicePublicationException`.
- **Mandatory Publication Enforcement**: When `initialize()` completes, the runtime verifies that the component published every service declared in its `provides` set. If any declared service is missing, the component's initialization fails with `ServicePublicationException`, triggering startup rollback.

### Service Resolution

Components resolve their dependencies using `ComponentContext.requireService(key)`:
- **Declared Dependencies Only**: The requested `ServiceKey` must be declared in the consumer's `requires` set. Requesting an undeclared service throws `ServiceResolutionException`.
- **Type Compatibility**: The requested service key's type is validated against the registered service type.
- **Provider Lifecycle Visibility**: A service is only resolvable when its provider is in an operational lifecycle state (`INITIALIZED`, `STARTING`, or `STARTED`). If the provider has not yet initialized, has failed, or is shutting down, resolution throws `ServiceResolutionException`.

### Service Visibility Across Lifecycle States

The following table details service availability based on the provider component's `ComponentStatus`:

| Provider ComponentStatus | Service Resolvable via `requireService`? | Description |
|---|---|---|
| `REGISTERED` | No | Component is registered but not yet initialized; services are not published. |
| `INITIALIZING` | No | Services published during this phase are staged and uncommitted until initialization succeeds. |
| `INITIALIZED` | Yes | Component has completed initialization and committed all declared services. |
| `STARTING` | Yes | Component is executing its startup sequence. |
| `STARTED` | Yes | Component is fully running. |
| `STOPPING` | No | Component has entered shutdown; services are invalidated to prevent late access. |
| `STOPPED` | No | Component is stopped; all provided services are revoked. |
| `FAILED` | No | Component encountered a failure; all provided services are invalidated and cleaned up. |

### Initialization Transaction & Failure Rollback

1. **Staged Publication**: Services published during `initialize(context)` are staged in a private transaction buffer. They become visible to consumers only after the provider's `initialize()` returns successfully and all declared services have been published.
2. **Rollback on Initialization Failure**: If a component throws an exception or fails mandatory publication verification, its staged services are discarded. Previously initialized components are shut down in reverse order, and their committed services are invalidated. The failing component's `stop()` hook is not called.
3. **Invalidation on Shutdown**: When `stop()` is invoked on the runtime, each component's committed services are removed as that component enters the `STOPPING` state.

### Interface-Based Dependencies & Provider Substitution

Components depend strictly on abstract service interfaces rather than concrete implementations:
- Decouples component business logic from specific service implementations.
- Allows seamless provider substitution (e.g., swapping a `FormalGreetingService` with a `CasualGreetingService`, or a mock service in test environments) without modifying consumer code or descriptors.
- Enforces clear architectural boundaries where components only share API contracts.

### Minimal Example

```kotlin
import com.ultron.component.*
import kotlinx.coroutines.runBlocking

// 1. Define the service interface and typed key
interface GreetingService {
    fun greet(name: String): String
}

val greetingServiceKey = ServiceKey("greetingService", GreetingService::class)

// 2. Implement the provider component
class GreetingProviderComponent : AppComponent {
    override val descriptor = ComponentDescriptor(
        id = ComponentId("greetingProvider"),
        provides = setOf(greetingServiceKey)
    )

    override suspend fun initialize(context: ComponentContext) {
        val service = object : GreetingService {
            override fun greet(name: String): String = "Hello, $name!"
        }
        context.publishService(greetingServiceKey, service)
    }

    override suspend fun start() {}
    override suspend fun stop() {}
}

// 3. Implement the consumer component
class GreetingConsumerComponent : AppComponent {
    override val descriptor = ComponentDescriptor(
        id = ComponentId("greetingConsumer"),
        requires = setOf(greetingServiceKey)
    )

    private var greetingService: GreetingService? = null

    override suspend fun initialize(context: ComponentContext) {
        greetingService = context.requireService(greetingServiceKey)
    }

    override suspend fun start() {
        println(greetingService?.greet("World"))
    }

    override suspend fun stop() {}
}

// 4. Assemble and run
fun main() = runBlocking {
    val runtime = ComponentRuntime(
        GreetingProviderComponent(),
        GreetingConsumerComponent()
    )
    runtime.start()
    runtime.stop()
}
```

### Unsupported Features

By design, the component runtime intentionally does not support:
- **Dynamic Service Replacement**: Service bindings are immutable once committed for the runtime lifecycle.
- **Multiple Providers**: Every service must have exactly one provider; multiple providers for the same service key are rejected during configuration validation.
- **Undeclared Service Lookups**: Service locator patterns and undeclared lookups are blocked; dependencies must be statically declared.
- **Runtime Service Unpublishing**: Components cannot unpublish or retract services while running.
- **In-Memory Reference Revocation**: While the runtime invalidates services and prevents future `requireService` calls after shutdown, it cannot forcefully nullify references already retained in local component fields. Components are responsible for clearing local state during `stop()`.
