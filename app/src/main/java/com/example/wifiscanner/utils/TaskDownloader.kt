package com.example.wifiscanner.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Загрузка JSON-заданий по URL. Поддержка прямых ссылок и публичных ссылок Яндекс.Диска.
 * Яндекс.Диск API (без ключа): cloud-api.yandex.net/v1/disk/public/resources/download?public_key=URL
 */
object TaskDownloader {

    private const val YANDEX_DISK_API = "https://cloud-api.yandex.net/v1/disk/public/resources/download"
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 15_000

    suspend fun downloadJson(rawUrl: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = sanitizeUrl(rawUrl)

            val downloadUrl = if (isYandexDiskUrl(url)) {
                resolveYandexDiskUrl(url)
            } else {
                url
            }

            val connection = URL(downloadUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.instanceFollowRedirects = true

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val content = connection.inputStream.bufferedReader().readText()
                connection.disconnect()
                Result.success(content)
            } else {
                val code = connection.responseCode
                connection.disconnect()
                Result.failure(Exception("HTTP $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Очистка и валидация URL: trim, авто-добавление https://, проверка формата */
    private fun sanitizeUrl(rawUrl: String): String {
        var url = rawUrl.trim()

        // Убрать кавычки если пользователь скопировал с ними
        url = url.removeSurrounding("\"").removeSurrounding("'")

        // Авто-добавить протокол если отсутствует
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }

        // Валидация: должен быть парсируемый URL
        try {
            URL(url) // throws MalformedURLException
        } catch (e: Exception) {
            throw Exception("Некорректная ссылка: $rawUrl")
        }

        return url
    }

    private fun isYandexDiskUrl(url: String): Boolean {
        return url.contains("disk.yandex.ru") || url.contains("yadi.sk")
    }

    private fun resolveYandexDiskUrl(publicUrl: String): String {
        val encodedUrl = URLEncoder.encode(publicUrl, "UTF-8")
        val apiUrl = "$YANDEX_DISK_API?public_key=$encodedUrl"

        val connection = URL(apiUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT

        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()
            val json = JSONObject(response)
            return json.getString("href")
        } else {
            val code = connection.responseCode
            connection.disconnect()
            throw Exception("Yandex API error: HTTP $code")
        }
    }
}
