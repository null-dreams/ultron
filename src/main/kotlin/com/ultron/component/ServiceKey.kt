package com.ultron.component

import kotlin.reflect.KClass

data class ServiceKey<T : Any>(
    val id: String,
    val type: KClass<T>
) {
    init {
        require(id.isNotBlank()) {
            "Service key ID must not be blank"
        }
    }

    companion object {
        inline fun <reified T : Any> of(id: String): ServiceKey<T> = ServiceKey(id, T::class)
    }
}

