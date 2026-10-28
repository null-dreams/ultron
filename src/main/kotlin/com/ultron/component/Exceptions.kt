package com.ultron.component

import kotlin.reflect.KClass

open class ComponentException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

open class ComponentConfigurationException(message: String, cause: Throwable? = null) : ComponentException(message, cause)

class DuplicateComponentException(
    val componentId: ComponentId,
    message: String = "Component with ID '${componentId.value}' is already registered"
) : ComponentConfigurationException(message)

class MissingProviderException(
    val serviceId: String,
    val requiredBy: ComponentId,
    message: String = "Service '$serviceId' required by component '${requiredBy.value}' has no provider"
) : ComponentConfigurationException(message)

class DuplicateProviderException(
    val serviceId: String,
    val existingProvider: ComponentId,
    val conflictingProvider: ComponentId,
    message: String = "Service '$serviceId' provided by component '${conflictingProvider.value}' is already provided by component '${existingProvider.value}'"
) : ComponentConfigurationException(message)

class ConflictingServiceTypeException(
    val serviceId: String,
    val existingType: KClass<*>,
    val conflictingType: KClass<*>,
    val componentId: ComponentId,
    message: String = "Conflicting types for service ID '$serviceId': already declared with type '${existingType.qualifiedName ?: existingType.toString()}', but component '${componentId.value}' declares type '${conflictingType.qualifiedName ?: conflictingType.toString()}'"
) : ComponentConfigurationException(message)

class CircularDependencyException(
    val cycle: List<ComponentId>,
    message: String = "Dependency cycle detected: ${cycle.joinToString(" -> ") { it.value }}"
) : ComponentConfigurationException(message)

class SelfDependencyException(
    val componentId: ComponentId,
    val serviceId: String,
    message: String = "Component '${componentId.value}' cannot depend on service '$serviceId' which it provides itself"
) : ComponentConfigurationException(message)

class InvalidDescriptorException(
    val componentId: ComponentId,
    message: String
) : ComponentConfigurationException(message)

enum class LifecyclePhase {
    INITIALIZATION,
    STARTUP,
    SHUTDOWN
}

data class CleanupFailure(
    val componentId: ComponentId,
    val cause: Throwable
)

class ComponentLifecycleException(
    val componentId: ComponentId,
    val phase: LifecyclePhase,
    val primaryCause: Throwable,
    val cleanupFailures: List<CleanupFailure> = emptyList(),
    message: String = buildMessage(componentId, phase, primaryCause, cleanupFailures)
) : ComponentException(message, primaryCause) {

    init {
        for (cleanup in cleanupFailures) {
            addSuppressed(cleanup.cause)
        }
    }

    companion object {
        private fun buildMessage(
            componentId: ComponentId,
            phase: LifecyclePhase,
            primaryCause: Throwable,
            cleanupFailures: List<CleanupFailure>
        ): String {
            val base = "Lifecycle failure in phase $phase for component '${componentId.value}': ${primaryCause.message ?: primaryCause::class.simpleName}"
            return if (cleanupFailures.isEmpty()) {
                base
            } else {
                val cleanupDetails = cleanupFailures.joinToString("; ") {
                    "'${it.componentId.value}': ${it.cause.message ?: it.cause::class.simpleName}"
                }
                "$base (Cleanup failures: $cleanupDetails)"
            }
        }
    }
}

open class ServiceException(message: String, cause: Throwable? = null) : ComponentException(message, cause)

class ServicePublicationException(
    val componentId: ComponentId,
    val serviceId: String,
    val reason: String,
    message: String = "Service publication failed for component '${componentId.value}', service '$serviceId': $reason"
) : ServiceException(message)

class ServiceResolutionException(
    val componentId: ComponentId,
    val serviceId: String,
    val reason: String,
    message: String = "Service resolution failed for component '${componentId.value}', service '$serviceId': $reason"
) : ServiceException(message)
