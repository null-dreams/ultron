package com.ultron.component

data class ComponentDescriptor(
    val id: ComponentId,
    val requires: Set<ServiceKey<*>> = emptySet(),
    val provides: Set<ServiceKey<*>> = emptySet()
) {
    init {
        // 1. Validate no blank component or service IDs
        // (ComponentId already validates in its init)

        // 2. Validate no duplicate service IDs in requires
        val reqIds = mutableSetOf<String>()
        for (req in requires) {
            require(req.id.isNotBlank()) {
                "Service key ID must not be blank"
            }
            if (!reqIds.add(req.id)) {
                throw InvalidDescriptorException(
                    id,
                    "Component '${id.value}' declares duplicate service ID '${req.id}' in requires"
                )
            }
        }

        // 3. Validate no duplicate service IDs in provides
        val provIds = mutableSetOf<String>()
        for (prov in provides) {
            require(prov.id.isNotBlank()) {
                "Service key ID must not be blank"
            }
            if (!provIds.add(prov.id)) {
                throw InvalidDescriptorException(
                    id,
                    "Component '${id.value}' declares duplicate service ID '${prov.id}' in provides"
                )
            }
        }

        // 4. Validate no service ID may appear in both requires and provides for the same component
        for (provId in provIds) {
            if (reqIds.contains(provId)) {
                throw SelfDependencyException(id, provId)
            }
        }
    }
}

