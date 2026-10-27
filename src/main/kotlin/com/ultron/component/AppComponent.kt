package com.ultron.component

interface AppComponent {
    val descriptor: ComponentDescriptor

    suspend fun initialize(context: ComponentContext)
    suspend fun start()
    suspend fun stop()
}

