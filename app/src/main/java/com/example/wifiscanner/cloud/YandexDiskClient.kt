package com.example.wifiscanner.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class DiskFile(val name: String, val path: String, val size: Long, val modified: String)

object YandexDiskClient {
    private const val BASE_URL = "https://cloud-api.yandex.net/v1/disk/resources"
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 15_000

    suspend fun listFiles(token: String, remotePath: String): Result<List<DiskFile>> = withContext(Dispatchers.IO) {
        try {
            val encodedPath = URLEncoder.encode(remotePath, "UTF-8")
            val url = URL("$BASE_URL?path=$encodedPath&fields=_embedded.items")
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("Authorization", "OAuth $token")
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                
                // Проверка на случай пустой папки или отсутствия items
                val embedded = json.optJSONObject("_embedded")
                if (embedded == null) {
                    return@withContext Result.success(emptyList<DiskFile>())
                }
                
                val items = embedded.optJSONArray("items")
                if (items == null) {
                    return@withContext Result.success(emptyList<DiskFile>())
                }
                
                val result = mutableListOf<DiskFile>()
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    if (item.getString("type") == "file") {
                        result.add(
                            DiskFile(
                                name = item.getString("name"),
                                path = item.getString("path"),
                                size = item.optLong("size", 0L),
                                modified = item.optString("modified", "")
                            )
                        )
                    }
                }
                Result.success(result)
            } else {
                val code = connection.responseCode
                val stream = if (code >= 400) connection.errorStream else connection.inputStream
                val err = stream?.bufferedReader()?.readText() ?: ""
                Result.failure(Exception("HTTP $code: $err"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadFile(token: String, remotePath: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Шаг 1: получение ссылки на скачивание
            val encodedPath = URLEncoder.encode(remotePath, "UTF-8")
            val url = URL("$BASE_URL/download?path=$encodedPath")
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("Authorization", "OAuth $token")
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(Exception("Не удалось получить ссылку: HTTP ${connection.responseCode}"))
            }

            val response = connection.inputStream.bufferedReader().readText()
            val downloadUrlStr = JSONObject(response).getString("href")

            // Шаг 2: скачивание содержимого
            val downloadUrl = URL(downloadUrlStr)
            val downloadConn = downloadUrl.openConnection() as HttpURLConnection
            downloadConn.connectTimeout = CONNECT_TIMEOUT
            downloadConn.readTimeout = READ_TIMEOUT

            if (downloadConn.responseCode == HttpURLConnection.HTTP_OK) {
                val content = downloadConn.inputStream.bufferedReader().readText()
                Result.success(content)
            } else {
                 Result.failure(Exception("Ошибка скачивания: HTTP ${downloadConn.responseCode}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadFile(token: String, remotePath: String, data: ByteArray, overwrite: Boolean = true): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Шаг 1: получение ссылки для загрузки
            val encodedPath = URLEncoder.encode(remotePath, "UTF-8")
            val overwriteParam = if (overwrite) "true" else "false"
            val url = URL("$BASE_URL/upload?path=$encodedPath&overwrite=$overwriteParam")
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("Authorization", "OAuth $token")
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                val stream = connection.errorStream
                val errText = stream?.bufferedReader()?.readText() ?: ""
                return@withContext Result.failure(Exception("Не удалось ссылку загрузки: HTTP ${connection.responseCode} $errText"))
            }

            val response = connection.inputStream.bufferedReader().readText()
            val uploadUrlStr = JSONObject(response).getString("href")

            // Шаг 2: загрузка файла
            val uploadUrl = URL(uploadUrlStr)
            val uploadConn = uploadUrl.openConnection() as HttpURLConnection
            uploadConn.requestMethod = "PUT"
            uploadConn.doOutput = true
            uploadConn.connectTimeout = CONNECT_TIMEOUT
            uploadConn.readTimeout = READ_TIMEOUT
            
            uploadConn.outputStream.use { it.write(data) }

            if (uploadConn.responseCode in 200..202) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Ошибка загрузки файла: HTTP ${uploadConn.responseCode}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
