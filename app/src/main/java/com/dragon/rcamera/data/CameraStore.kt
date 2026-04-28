package com.dragon.rcamera.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class CameraStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("rcamera_store", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_CAMERAS = "cameras"
        private const val KEY_SERVER_PASSWORD = "server_password"
        private const val KEY_SERVER_PORT = "server_port"
        private const val DEFAULT_PASSWORD = "123456"
        private const val DEFAULT_PORT = 8888
    }

    // ===== Remote Cameras =====

    fun getCameras(): List<RemoteCamera> {
        val json = prefs.getString(KEY_CAMERAS, null) ?: return emptyList()
        val type = object : TypeToken<List<RemoteCamera>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addCamera(camera: RemoteCamera) {
        val cameras = getCameras().toMutableList()
        cameras.add(camera)
        saveCameras(cameras)
    }

    fun removeCamera(cameraId: String) {
        val cameras = getCameras().filter { it.id != cameraId }
        saveCameras(cameras)
    }

    fun updateCamera(camera: RemoteCamera) {
        val cameras = getCameras().map {
            if (it.id == camera.id) camera else it
        }
        saveCameras(cameras)
    }

    private fun saveCameras(cameras: List<RemoteCamera>) {
        prefs.edit().putString(KEY_CAMERAS, gson.toJson(cameras)).apply()
    }

    // ===== Server Config =====

    fun getServerPassword(): String {
        return prefs.getString(KEY_SERVER_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD
    }

    fun setServerPassword(password: String) {
        prefs.edit().putString(KEY_SERVER_PASSWORD, password).apply()
    }

    fun getServerPort(): Int {
        return prefs.getInt(KEY_SERVER_PORT, DEFAULT_PORT)
    }

    fun setServerPort(port: Int) {
        prefs.edit().putInt(KEY_SERVER_PORT, port).apply()
    }
}
