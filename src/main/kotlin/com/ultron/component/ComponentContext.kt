package com.ultron.component

interface ComponentContext {
    fun <T : Any> requireService(key: ServiceKey<T>): T

    fun <T : Any> publishService(
        key: ServiceKey<T>,
        service: T
    )
}

