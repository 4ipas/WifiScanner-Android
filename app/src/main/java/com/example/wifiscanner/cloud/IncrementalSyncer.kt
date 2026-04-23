package com.example.wifiscanner.cloud

import android.content.Context
import android.util.Log
import com.example.wifiscanner.utils.DiagnosticLogger
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Инкрементальная синхронизация CSV-файла с Яндекс.Диском во время сканирования.
 *
 * Yandex Disk REST API не поддерживает append — каждый PUT полностью перезаписывает файл.
 * Стратегия: периодический re-upload полного CSV с overwrite=true.
 *
 * - Не блокирует основной цикл сканирования (работает в отдельной coroutine)
 * - Защита от параллельных uploads через AtomicBoolean
 * - Watchdog timeout: если upload висит > 60 сек — считаем его зависшим
 * - flush() — v5.2.1: неблокирующий финальный upload через UploadQueueManager
 */
class IncrementalSyncer(
    private val context: Context,
    private val localFilePath: String,
    private val remotePath: String
) {
    companion object {
        private const val TAG = "IncrementalSyncer"

        /** Синхронизация каждые N снимков. 6 × 5 сек = ~30 сек между uploads */
        const val SYNC_EVERY_N_SNAPSHOTS = 6

        /** Watchdog timeout: если upload висит дольше — считаем зависшим (мс) */
        private const val UPLOAD_WATCHDOG_MS = 60_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isUploading = AtomicBoolean(false)
    private val uploadStartedAt = AtomicLong(0L)
    private var snapshotCounter = 0
    private var lastUploadedSize = 0L
    private var cancelled = false
    private var folderEnsured = false

    /**
     * Вызывается из WifiScanService после каждого успешного snapshot.
     * Если пора синхронизировать — запускает фоновый upload.
     */
    fun onSnapshotWritten() {
        if (cancelled) return
        snapshotCounter++

        if (snapshotCounter % SYNC_EVERY_N_SNAPSHOTS != 0) return

        triggerUpload(reason = "periodic_sync_$snapshotCounter")
    }

    /**
     * v5.2.1: Неблокирующий финальный upload при остановке сервиса.
     * Делегирует загрузку в персистентную очередь UploadQueueManager.
     * НИКОГДА не блокирует Main Thread (вызывается из onDestroy).
     * Файл будет загружен при восстановлении сети через ретрай-механизм очереди.
     */
    fun flush() {
        if (cancelled) return

        // Если размер файла не изменился с последнего upload — пропускаем
        val file = File(localFilePath)
        if (!file.exists() || file.length() == lastUploadedSize) {
            Log.d(TAG, "flush: file unchanged, skip")
            return
        }

        Log.d(TAG, "flush: enqueue final upload (non-blocking)")
        DiagnosticLogger.log("SYNC_FLUSH", "file=${file.name},size=${file.length()}")

        // v5.2.1: Неблокирующий flush — отправляем в персистентную очередь загрузки.
        // UploadQueueManager сам создаст папку на диске и загрузит файл.
        // При отсутствии интернета — файл останется в очереди до следующего запуска.
        UploadQueueManager.enqueue(localFilePath, remotePath)
    }

    /**
     * Полная отмена — вызывается при уничтожении сервиса.
     */
    fun cancel() {
        cancelled = true
        scope.cancel()
    }

    // ─── Внутренние методы ──────────────────────────────────────────

    private fun triggerUpload(reason: String) {
        // Проверяем watchdog: если предыдущий upload завис — сбрасываем флаг
        if (isUploading.get()) {
            val elapsed = System.currentTimeMillis() - uploadStartedAt.get()
            if (elapsed > UPLOAD_WATCHDOG_MS) {
                Log.w(TAG, "Upload watchdog triggered after ${elapsed}ms, resetting flag")
                DiagnosticLogger.log("SYNC_WATCHDOG", "elapsed=${elapsed}ms")
                isUploading.set(false)
            } else {
                Log.d(TAG, "Upload already in progress, skipping ($reason)")
                return
            }
        }

        scope.launch {
            doUpload(reason)
        }
    }

    private suspend fun doUpload(reason: String) {
        if (!isUploading.compareAndSet(false, true)) {
            return // Другой upload уже запущен
        }

        uploadStartedAt.set(System.currentTimeMillis())

        try {
            val file = File(localFilePath)
            if (!file.exists()) {
                Log.w(TAG, "File not found: $localFilePath")
                return
            }

            val fileSize = file.length()
            if (fileSize == 0L) return
            if (fileSize == lastUploadedSize) {
                Log.d(TAG, "File unchanged ($fileSize bytes), skip upload")
                return
            }

            val token = DiskConfig.getToken(context)
            if (token.isBlank()) {
                Log.d(TAG, "No token, skip sync")
                return
            }

            // v5.2.0: Создаём папку на диске при первом upload
            if (!folderEnsured) {
                ensureRemoteFolder(token)
                folderEnsured = true
            }

            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            val result = YandexDiskClient.uploadFile(token, remotePath, bytes, overwrite = true)

            if (result.isSuccess) {
                lastUploadedSize = fileSize
                Log.d(TAG, "Synced: ${file.name} ($fileSize bytes) [$reason]")
                DiagnosticLogger.log("SYNC_OK", "file=${file.name},size=$fileSize,reason=$reason")
            } else {
                val err = result.exceptionOrNull()?.message ?: "unknown"
                Log.w(TAG, "Sync failed: $err [$reason]")
                DiagnosticLogger.log("SYNC_FAIL", "file=${file.name},err=$err,reason=$reason")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync error", e)
            DiagnosticLogger.log("SYNC_ERROR", "err=${e.message},reason=$reason")
        } finally {
            isUploading.set(false)
        }
    }

    /**
     * v5.2.0: Создаёт родительскую папку на Яндекс.Диске, если её нет.
     * Вызывается однократно при первом upload.
     * createFolder возвращает success и при 409 (уже существует).
     */
    private suspend fun ensureRemoteFolder(token: String) {
        // remotePath = "app:/scan_results/diag_scans/filename.csv"
        // Нужно создать "app:/scan_results/diag_scans"
        val folderPath = remotePath.substringBeforeLast('/')
        if (folderPath.isNotBlank() && folderPath != remotePath) {
            val result = YandexDiskClient.createFolder(token, folderPath)
            if (result.isSuccess) {
                Log.d(TAG, "Ensured remote folder: $folderPath")
            } else {
                Log.w(TAG, "Failed to create folder $folderPath: ${result.exceptionOrNull()?.message}")
            }
        }
    }
}
