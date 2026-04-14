package com.example.stepcounterdemo

import com.example.stepcounterdemo.data.HourlyStepEntity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class SyncRepository(
    internal val jwtSubExtractor: JwtSubExtractor = androidJwtSubExtractor
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun interface JwtSubExtractor {
        fun extract(token: String): String
    }

    sealed class AuthResult {
        data class Success(val token: String, val humanId: String) : AuthResult()
        data class Failure(val message: String) : AuthResult()
    }

    sealed class BatchResult {
        data class Success(val accepted: Int) : BatchResult()
        object Unauthorized : BatchResult()
        data class Failure(val message: String) : BatchResult()
    }

    companion object {
        val androidJwtSubExtractor = JwtSubExtractor { token ->
            val part = token.split(".").getOrNull(1)
                ?: throw IllegalArgumentException("Invalid JWT format")
            val bytes = android.util.Base64.decode(part, android.util.Base64.URL_SAFE)
            JSONObject(String(bytes, Charsets.UTF_8)).getString("sub")
        }
    }

    fun authenticate(
        host: String,
        username: String,
        password: String,
        deviceGuid: String
    ): AuthResult {
        val body = JSONObject().apply {
            put("username", username)
            put("password", password)
            put("device_guid", deviceGuid)
            put("device_type", "stepcounter")
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${host.trimEnd('/')}/api/devices/auth")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.code != 200) {
                    return AuthResult.Failure("HTTP ${response.code}")
                }
                val json = JSONObject(response.body?.string() ?: "{}")
                val token = json.getString("token")
                val humanId = jwtSubExtractor.extract(token)
                AuthResult.Success(token, humanId)
            }
        } catch (e: Exception) {
            AuthResult.Failure(e.message ?: "Network error")
        }
    }

    fun submitBatch(
        host: String,
        token: String,
        humanId: String,
        deviceGuid: String,
        records: List<HourlyStepEntity>
    ): BatchResult {
        val measurements = JSONArray()
        for (record in records) {
            val guid = UUID.nameUUIDFromBytes(
                "$deviceGuid:${record.hourKey}".toByteArray()
            ).toString()
            measurements.put(JSONObject().apply {
                put("guid", guid)
                put("humanId", humanId)
                put("startTime", record.hourKey * 3_600_000L)
                put("endTime", (record.hourKey + 1) * 3_600_000L)
                put("stepCount", record.stepCount)
            })
        }

        val body = JSONObject().put("measurements", measurements).toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${host.trimEnd('/')}/api/devices/measurements/stepcounter")
            .addHeader("Authorization", "Bearer $token")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val json = JSONObject(response.body?.string() ?: "{}")
                        BatchResult.Success(json.getJSONArray("accepted").length())
                    }
                    401 -> BatchResult.Unauthorized
                    else -> BatchResult.Failure("HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            BatchResult.Failure(e.message ?: "Network error")
        }
    }
}
