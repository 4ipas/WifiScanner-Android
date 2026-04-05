package com.example.wifiscanner.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

/**
 * Результат агрегированного снимка сенсоров за период.
 * Передается вместе с каждым Wi-Fi слепком в CSV.
 */
data class SensorSnapshot(
    val stepsDelta: Int?,          // Шаги за последний интервал (null если сенсор недоступен)
    val stepsTotal: Int?,          // Общий счётчик шагов с начала сессии
    val azimuth: Float?,           // Направление движения 0-360° (null если гироскоп недоступен)
    val azimuthConfidence: Float?, // Достоверность азимута 0.0-1.0
    val deviceOrientation: String  // FACE_UP, FACE_DOWN, PORTRAIT, LANDSCAPE, TILTED_DOWN, UNKNOWN
)

/**
 * Сборщик данных IMU-сенсоров для PDR (Pedestrian Dead Reckoning).
 * Инкапсулирует работу с SensorManager: акселерометр, шагомер, rotation vector.
 *
 * Принцип: сенсоры работают непрерывно в фоне, но данные агрегируются
 * и запрашиваются только раз в 5 секунд (синхронно с Wi-Fi сканом).
 * В CSV записывается только агрегированный SensorSnapshot, а не сырые значения.
 *
 * Graceful Degradation:
 * - Нет шагомера → stepsDelta/stepsTotal = null
 * - Нет гироскопа → azimuth/azimuthConfidence = null
 * - Акселерометр есть всегда → deviceOrientation всегда заполнен
 */
class SensorCollector(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // --- Доступность сенсоров ---
    private val hasStepCounter: Boolean
    private val hasStepDetector: Boolean
    private val hasRotationVector: Boolean
    private val hasAccelerometer: Boolean

    // --- Текущие значения сенсоров (обновляются в реальном времени) ---
    private var currentStepCount: Int? = null    // Абсолютный аппаратный счётчик
    private var sessionStartSteps: Int? = null   // Значение счётчика на момент start()
    private var lastSnapshotSteps: Int? = null    // Значение счётчика на момент прошлого getSnapshot()
    private var manualStepCount: Int = 0          // Fallback: ручной счётчик через STEP_DETECTOR
    private var manualStepSession: Int = 0        // Ручной счётчик с момента старта сессии
    private var manualStepLastSnapshot: Int = 0   // Ручной счётчик на момент последнего snapshot

    private var currentAzimuth: Float? = null     // Текущий азимут (0-360°)
    private var currentAzimuthAccuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE

    // Гравитационный вектор для определения ориентации
    private var gravityX: Float = 0f
    private var gravityY: Float = 0f
    private var gravityZ: Float = 0f
    private var hasGravityData: Boolean = false

    companion object {
        private const val TAG = "SensorCollector"
    }

    init {
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        hasStepCounter = stepSensor != null
        hasStepDetector = stepDetector != null
        hasRotationVector = rotationSensor != null
        hasAccelerometer = accelSensor != null

        Log.d(TAG, "Sensors: StepCounter=$hasStepCounter, StepDetector=$hasStepDetector, RotationVector=$hasRotationVector, Accel=$hasAccelerometer")
    }

    /**
     * Запускает сбор данных с сенсоров.
     * Вызывать при старте WifiScanService.
     */
    fun start() {
        // Шагомер — UI задержка для быстрого отклика
        if (hasStepCounter) {
            val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
            Log.d(TAG, "Registered TYPE_STEP_COUNTER (UI Delay)")
        }

        // Step Detector — мгновенная реакция на каждый шаг (backup)
        if (hasStepDetector) {
            val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
            Log.d(TAG, "Registered TYPE_STEP_DETECTOR (UI Delay)")
        }

        // Rotation Vector — для азимута (Sensor Fusion: гироскоп + акселерометр)
        if (hasRotationVector) {
            val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }

        // Акселерометр — для определения ориентации устройства
        if (hasAccelerometer) {
            val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }

        // Сброс сессионных счётчиков
        sessionStartSteps = null
        lastSnapshotSteps = null
        manualStepCount = 0
        manualStepSession = 0
        manualStepLastSnapshot = 0
    }

    /**
     * Останавливает сбор данных с сенсоров.
     * Вызывать при остановке WifiScanService.
     */
    fun stop() {
        sensorManager.unregisterListener(this)
        sessionStartSteps = null
        lastSnapshotSteps = null
        currentStepCount = null
        currentAzimuth = null
        hasGravityData = false
    }

    /**
     * Возвращает агрегированный снимок данных сенсоров.
     * Вызывается один раз на каждый Wi-Fi слепок (каждые ~5 сек).
     * После вызова обнуляет дельту шагов.
     */
    fun getSnapshot(): SensorSnapshot {
        // --- Шаги ---
        val stepsDelta: Int?
        val stepsTotal: Int?

        if (hasStepCounter && currentStepCount != null) {
            val current = currentStepCount!!

            // Инициализация сессии при первом вызове
            if (sessionStartSteps == null) {
                sessionStartSteps = current
                lastSnapshotSteps = current
            }

            stepsTotal = current - sessionStartSteps!!
            stepsDelta = current - (lastSnapshotSteps ?: current)
            lastSnapshotSteps = current
            Log.d(TAG, "StepCounter: total=$stepsTotal, delta=$stepsDelta")
        } else if (hasStepDetector) {
            // Fallback: используем ручной подсчёт из STEP_DETECTOR
            stepsTotal = manualStepSession
            stepsDelta = manualStepSession - manualStepLastSnapshot
            manualStepLastSnapshot = manualStepSession
            Log.d(TAG, "StepDetector fallback: total=$stepsTotal, delta=$stepsDelta")
        } else {
            stepsDelta = null
            stepsTotal = null
        }

        // --- Азимут ---
        val azimuth = currentAzimuth
        val azimuthConfidence: Float? = if (azimuth != null) {
            when (currentAzimuthAccuracy) {
                SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 1.0f
                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 0.7f
                SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 0.3f
                else -> 0.1f
            }
        } else {
            null
        }

        // --- Ориентация устройства ---
        val orientation = computeDeviceOrientation()

        return SensorSnapshot(
            stepsDelta = stepsDelta,
            stepsTotal = stepsTotal,
            azimuth = azimuth,
            azimuthConfidence = azimuthConfidence,
            deviceOrientation = orientation
        )
    }

    // === SensorEventListener ===

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                // Android отдает абсолютное кол-во шагов с момента последней перезагрузки
                currentStepCount = event.values[0].toInt()
                Log.d(TAG, "STEP_COUNTER event: ${currentStepCount}")
            }

            Sensor.TYPE_STEP_DETECTOR -> {
                // Мгновенное событие на каждый шаг
                manualStepCount++
                manualStepSession++
                Log.d(TAG, "STEP_DETECTOR event: session=$manualStepSession")
            }

            Sensor.TYPE_ROTATION_VECTOR -> {
                // Вычисляем азимут из Rotation Vector (Sensor Fusion)
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientationAngles = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)

                // orientationAngles[0] = azimuth в радианах (-PI..PI)
                var azimuthDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                if (azimuthDeg < 0) azimuthDeg += 360f
                currentAzimuth = azimuthDeg
            }

            Sensor.TYPE_ACCELEROMETER -> {
                // Low-pass filter для сглаживания (альфа = 0.8 даёт плавный гравитационный вектор)
                val alpha = 0.8f
                gravityX = alpha * gravityX + (1 - alpha) * event.values[0]
                gravityY = alpha * gravityY + (1 - alpha) * event.values[1]
                gravityZ = alpha * gravityZ + (1 - alpha) * event.values[2]
                hasGravityData = true
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            currentAzimuthAccuracy = accuracy
        }
    }

    // === Вычисление ориентации через вектор гравитации ===

    /**
     * Определяет ориентацию устройства по направлению вектора гравитации.
     *
     * FACE_UP — экран смотрит вверх (лежит на столе)
     * FACE_DOWN — экран смотрит вниз (антенна вниз — ПРОБЛЕМНЫЙ СЛУЧАЙ)
     * PORTRAIT — вертикально, как при звонке
     * LANDSCAPE — горизонтально, как при видео
     * TILTED_DOWN — наклонён экраном вниз (антенна частично вниз)
     * UNKNOWN — нет данных
     */
    private fun computeDeviceOrientation(): String {
        if (!hasGravityData) return "UNKNOWN"

        val magnitude = sqrt(gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ)
        if (magnitude < 1f) return "UNKNOWN" // Нереальные данные

        // Нормализованные компоненты
        val nz = gravityZ / magnitude  // Ось Z: перпендикулярно экрану
        val ny = gravityY / magnitude  // Ось Y: вдоль длинной стороны
        val nx = gravityX / magnitude  // Ось X: вдоль короткой стороны

        return when {
            // Экран смотрит вверх (не обязательно лежит)
            nz > 0.7f -> "FACE_UP"

            // Экран смотрит вниз (не обязательно лежит)
            nz < -0.7f -> "FACE_DOWN"

            // Наклонён экраном вниз
            nz < -0.3f -> "TILTED"

            // Вертикально (Y доминирует)
            ny > 0.7f -> "PORTRAIT"
            ny < -0.7f -> "REVERSE_PORTRAIT" // CAMERA_DOWN

            // Горизонтально (X доминирует)
            nx > 0.7f -> "LANDSCAPE"
            nx < -0.7f -> "REVERSE_LANDSCAPE"

            else -> "UNKNOWN"
        }
    }
}
