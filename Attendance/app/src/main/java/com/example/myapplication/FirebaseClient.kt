package com.example.myapplication

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object FirebaseClient {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun get(path: String, callback: (JSONObject?) -> Unit) {
        request("GET", path, null, callback)
    }

    fun put(path: String, body: JSONObject, callback: (JSONObject?) -> Unit = {}) {
        request("PUT", path, body.toString(), callback)
    }

    fun patch(path: String, body: JSONObject, callback: (JSONObject?) -> Unit = {}) {
        request("PATCH", path, body.toString(), callback)
    }

    fun putRawBoolean(path: String, value: Boolean, callback: (JSONObject?) -> Unit = {}) {
        request("PUT", path, value.toString(), callback)
    }

    private fun request(
        method: String,
        path: String,
        bodyText: String?,
        callback: (JSONObject?) -> Unit
    ) {
        Thread {
            var connection: HttpURLConnection? = null

            try {
                val cleanPath = path.trim().trim('/')
                val urlText = if (cleanPath.isEmpty()) {
                    "${FirebaseConfig.BASE_URL}/.json"
                } else {
                    "${FirebaseConfig.BASE_URL}/$cleanPath.json"
                }

                connection = URL(urlText).openConnection() as HttpURLConnection
                connection.requestMethod = method
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")

                if (bodyText != null) {
                    connection.doOutput = true
                    OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use {
                        it.write(bodyText)
                        it.flush()
                    }
                }

                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val responseText = stream?.let {
                    BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader ->
                        reader.readText()
                    }
                }.orEmpty()

                val json = when {
                    responseText.isBlank() -> null
                    responseText.trim() == "null" -> null
                    responseText.trim().startsWith("{") -> JSONObject(responseText)
                    else -> JSONObject().put("value", responseText)
                }

                mainHandler.post {
                    callback(json)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    callback(null)
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }
}