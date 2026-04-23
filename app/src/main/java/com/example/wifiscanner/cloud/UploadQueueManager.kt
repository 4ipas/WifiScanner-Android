package com.example.wifiscanner.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.wifiscanner.utils.DiagnosticLogger
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v5.1.0: Менеджер очереди загрузки файлов на Яндекс Диск.
 *
 * Гарантирует доставку файлов даже после перезапуска приложения:
 * - Очередь хранится в JSON-файле в app_data
 * - При старте приложения — ретрай всех «зависших» файлов
 * - Каждый файл отправляется независимо (сбой одного не блокирует остальные)
 * - После успешной отправки — удаляется из очереди
 * - При неудаче — остаётся в очереди, ретрай через 5 минут
 */
object UploadQueueManager {
    private const val TAG = "UploadQueueManager"
    private const val QUEUE_FILENAME = "upload_queue.json"
    private const val MAX_RETRIES = 10
    private const val RETRY_DELAY_MS = 5 * 60 * 1000L // 5 минут
    private const val PROCESSING_WATCHDOG_MS = 5 * 60 * 1000L // 5 минут — watchdog для isProcessing

    data class QueueItem(
        val localPath: String,
        val remotePath: String,
        val addedAt: Long,
        var retryCount: Int = 0
    )

    private var appContext: Context? = null
    private val queue = mutableListOf<QueueItem>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var isProcessing = false
    private var processingStartedAt = 0L
    @Volatile private var retryRequested = false // v5.2.2: Флаг «сеть появилась, нужен ретрай»
    private val ensuredFolders = mutableSetOf<String>() // v5.2.0: Кэш созданных папок

    /**
     * Инициализация при старте приложения.
     * Загружает очередь из файла и запускает обработку.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        loadQueue()
        Log.d(TAG, "Initialized. Queue size: ${queue.size}")
        DiagnosticLogger.log("UPLOAD_QUEUE_INIT", "size=${queue.size}")

        // v5.2.2: Подписаться на появление сети → немедленный processQueue()
        registerNetworkCallback(context.applicationContext)

        // v5.2.0: Обработать зависшие файлы из предыдущей сессии
        if (queue.isNotEmpty()) {
            processQueue()
        }
    }

    /**
     * Добавить файл в очередь загрузки.
     * Файл будет отправлен при первой возможности.
     */
    @Synchronized
    fun enqueue(localPath: String, remotePath: String) {
        // Не добавлять дубликат (тот же localPath + remotePath)
        val exists = queue.any { it.localPath == localPath && it.remotePath == remotePath }
        if (exists) {
            Log.d(TAG, "Already in queue: $localPath")
            return
        }

        val item = QueueItem(
            localPath = localPath,
            remotePath = remotePath,
            addedAt = System.currentTimeMillis()
        )
        queue.add(item)
        saveQueue()
        Log.d(TAG, "Enqueued: $localPath → $remotePath")
        DiagnosticLogger.log("UPLOAD_ENQUEUE", "file=${File(localPath).name},remote=$remotePath")

        // Попытаться отправить сразу
        processQueue()
    }

    /**
     * Обработать всю очередь — отправить все файлы по очереди.
     * Каждый файл обрабатывается независимо.
     */
    fun processQueue() {
        // v5.2.0: Watchdog — если обработка зависла дольше 5 минут, сбрасываем флаг
        if (isProcessing) {
            val elapsed = System.currentTimeMillis() - processingStartedAt
            if (elapsed > PROCESSING_WATCHDOG_MS) {
                Log.w(TAG, "Processing watchdog triggered after ${elapsed}ms, resetting")
                DiagnosticLogger.log("UPLOAD_QUEUE_WATCHDOG", "elapsed=${elapsed}ms")
                isProcessing = false
            } else {
                return
            }
        }
        val ctx = appContext ?: return
        val token = DiskConfig.getToken(ctx)
        if (token.isBlank()) {
            Log.d(TAG, "No token, skipping queue processing")
            return
        }

        scope.launch {
            isProcessing = true
            processingStartedAt = System.currentTimeMillis()
            try {
                // Копируем очередь, чтобы итерировать безопасно
                val snapshot = synchronized(this@UploadQueueManager) { queue.toList() }

                if (snapshot.isEmpty()) {
                    isProcessing = false
                    return@launch
                }

                Log.d(TAG, "Processing queue: ${snapshot.size} items")
                DiagnosticLogger.log("UPLOAD_QUEUE_PROCESS", "items=${snapshot.size}")

                for (item in snapshot) {
                    try {
                        val file = File(item.localPath)
                        if (!file.exists()) {
                            // Файл исчез — удаляем из очереди
                            Log.w(TAG, "File not found, removing: ${item.localPath}")
                            DiagnosticLogger.log("UPLOAD_FILE_MISSING", "file=${file.name}")
                            removeFromQueue(item)
                            continue
                        }

                        if (item.retryCount >= MAX_RETRIES) {
                            Log.w(TAG, "Max retries exceeded: ${item.localPath}")
                            DiagnosticLogger.log("UPLOAD_MAX_RETRIES", "file=${file.name},retries=${item.retryCount}")
                            removeFromQueue(item)
                            continue
                        }

                        // v5.2.0: Создать папку на диске, если ещё не создана
                        ensureRemoteFolder(token, item.remotePath)

                        val bytes = file.readBytes()
                        val result = YandexDiskClient.uploadFile(token, item.remotePath, bytes)

                        if (result.isSuccess) {
                            Log.d(TAG, "Uploaded: ${file.name}")
                            DiagnosticLogger.log("UPLOAD_SUCCESS", "file=${file.name}")
                            removeFromQueue(item)
                        } else {
                            val err = result.exceptionOrNull()?.message ?: "unknown"
                            Log.w(TAG, "Upload failed: ${file.name} - $err")
                            DiagnosticLogger.log("UPLOAD_FAIL", "file=${file.name},err=$err,retry=${item.retryCount}")
                            incrementRetry(item)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing item: ${item.localPath}", e)
                        DiagnosticLogger.log("UPLOAD_ERROR", "file=${File(item.localPath).name},err=${e.message}")
                        incrementRetry(item)
                    }
                }
            } finally {
                isProcessing = false
            }

            // v5.2.2: Если сеть появилась во время обработки — ретрай немедленно
            if (retryRequested) {
                retryRequested = false
                val pending = synchronized(this@UploadQueueManager) { queue.size }
                if (pending > 0) {
                    Log.d(TAG, "Retry requested (network restored), processing $pending items immediately")
                    DiagnosticLogger.log("UPLOAD_RETRY_IMMEDIATE", "pending=$pending")
                    delay(2000) // 2 сек — дать сети стабилизироваться
                    processQueue()
                    return@launch
                }
            }

            // Если ещё остались элементы — запланировать ретрай через 5 мин
            val remaining = synchronized(this@UploadQueueManager) { queue.size }
            if (remaining > 0) {
                Log.d(TAG, "Scheduling retry in 5 min for $remaining items")
                delay(RETRY_DELAY_MS)
                processQueue()
            }
        }
    }

    /**
     * Количество файлов в очереди (для UI-индикации).
     */
    @Synchronized
    fun getQueueSize(): Int = queue.size

    // ─── Внутренние методы ──────────────────────────────────────────

    @Synchronized
    private fun removeFromQueue(item: QueueItem) {
        queue.removeAll { it.localPath == item.localPath && it.remotePath == item.remotePath }
        saveQueue()
    }

    @Synchronized
    private fun incrementRetry(item: QueueItem) {
        val found = queue.find { it.localPath == item.localPath && it.remotePath == item.remotePath }
        found?.retryCount = (found?.retryCount ?: 0) + 1
        saveQueue()
    }

    @Synchronized
    private fun saveQueue() {
        val ctx = appContext ?: return
        try {
            val jsonArray = JSONArray()
            for (item in queue) {
                val obj = JSONObject().apply {
                    put("localPath", item.localPath)
                    put("remotePath", item.remotePath)
                    put("addedAt", item.addedAt)
                    put("retryCount", item.retryCount)
                }
                jsonArray.put(obj)
            }
            val file = File(ctx.filesDir, QUEUE_FILENAME)
            file.writeText(jsonArray.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save queue", e)
        }
    }

    @Synchronized
    private fun loadQueue() {
        val ctx = appContext ?: return
        try {
            val file = File(ctx.filesDir, QUEUE_FILENAME)
            if (!file.exists()) return

            val jsonArray = JSONArray(file.readText())
            queue.clear()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                queue.add(
                    QueueItem(
                        localPath = obj.getString("localPath"),
                        remotePath = obj.getString("remotePath"),
                        addedAt = obj.getLong("addedAt"),
                        retryCount = obj.optInt("retryCount", 0)
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load queue", e)
            queue.clear()
        }
    }

    /**
     * v5.2.0: Создаёт родительскую папку на Яндекс.Диске перед upload.
     * Кэширует созданные папки, чтобы не вызывать API повторно.
     */
    private suspend fun ensureRemoteFolder(token: String, remotePath: String) {
        val folderPath = remotePath.substringBeforeLast('/')
        if (folderPath.isBlank() || folderPath == remotePath) return
        if (ensuredFolders.contains(folderPath)) return

        try {
            val result = YandexDiskClient.createFolder(token, folderPath)
            if (result.isSuccess) {
                ensuredFolders.add(folderPath)
                Log.d(TAG, "Ensured remote folder: $folderPath")
            } else {
                Log.w(TAG, "Failed to create folder $folderPath: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error creating folder $folderPath", e)
        }
    }

    /**
     * v5.2.2: Подписка на изменения сети.
     * При появлении интернета — немедленно обрабатываем очередь,
     * вместо ожидания 5-минутного таймера.
     */
    private fun registerNetworkCallback(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val pending = synchronized(this@UploadQueueManager) { queue.size }
                    if (pending > 0) {
                        Log.d(TAG, "Network available, pending=$pending, isProcessing=$isProcessing")
                        DiagnosticLogger.log("UPLOAD_NETWORK_AVAILABLE", "pending=$pending,processing=$isProcessing")
                        if (isProcessing) {
                            // Текущий upload ещё висит (HTTP timeout) —
                            // выставляем флаг, processQueue() подхватит после finally
                            retryRequested = true
                        } else {
                            processQueue()
                        }
                    }
                }
            })
            Log.d(TAG, "Network callback registered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback", e)
        }
    }
}
