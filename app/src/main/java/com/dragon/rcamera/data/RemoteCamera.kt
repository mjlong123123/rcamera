package com.dragon.rcamera.data

import java.util.UUID

data class RemoteCamera(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val wsUrl: String,
    val password: String,
    val ipv4Addresses: List<String>? = null,
    val ipv6Addresses: List<String>? = null,
    val port: Int = 8888,
    val addedAt: Long = System.currentTimeMillis()
)
