package com.dragon.rcamera.data

import java.util.UUID

data class RemoteCamera(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val wsUrl: String,
    val password: String,
    val addedAt: Long = System.currentTimeMillis()
)
