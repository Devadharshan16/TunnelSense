package com.example.idrnavigator.map

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray

 data class GeocodingResult(
    val name: String,
    val latitude: Double,
    val longitude: Double
)

class GeocodingService {
    suspend fun search(query: String): Result<List<GeocodingResult>> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (query.isBlank()) return@withContext Result.success(emptyList())
        val encodedQuery = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val connection = (URL("https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=5").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", "IDRNavigator/1.0")
            setRequestProperty("Accept", "application/json")
        }

        try {
            if (connection.responseCode !in 200..299) {
                return@withContext Result.failure(IOException("Geocoding HTTP ${connection.responseCode}"))
            }
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONArray(response)
            val results = buildList {
                for (index in 0 until json.length()) {
                    val item = json.getJSONObject(index)
                    val latitude = item.optString("lat").toDoubleOrNull() ?: continue
                    val longitude = item.optString("lon").toDoubleOrNull() ?: continue
                    add(GeocodingResult(item.optString("display_name", "Unnamed place"), latitude, longitude))
                }
            }
            Result.success(results)
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            connection.disconnect()
        }
    }
}
