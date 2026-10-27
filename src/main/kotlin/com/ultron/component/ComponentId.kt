package com.ultron.component

@JvmInline
value class ComponentId(val value: String) : Comparable<ComponentId> {
    init {
        require(value.isNotBlank()) {
            "Component ID must not be blank"
        }
    }

    override fun compareTo(other: ComponentId): Int = value.compareTo(other.value)

    override fun toString(): String = value
}

