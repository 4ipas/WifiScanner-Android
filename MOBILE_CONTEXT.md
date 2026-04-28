# Контекст мобильного приложения (WifiScanner)

## Версия 5 (Текущая) - [28.04.2026] (Full Snapshot: Doze Fix + Permissions Onboarding)

### 1. Стек технологий и Инфраструктура
- **Язык**: Kotlin (DSL `build.gradle.kts`)
- **Архитектура**: MVVM + Singleton State (`WifiRepository`) + Foreground Service (`LOCATION` + `HEALTH`) + IMU Sensor Fusion + Yandex Disk Cloud Sync + Offline-First Upload Queue
- **Версия**: 5.3.0 (`versionCode` 11)
- **SDK**: `compileSdk` 34, `minSdk` 24, `targetSdk` 34
- **Java**: `sourceCompatibility` / `targetCompatibility` = Java 17
- **Build Features**: ViewBinding, BuildConfig

### 2. Зависимости (Dependencies)
| Библиотека | Версия | Назначение |
|---|---|---|
| `androidx.core:core-ktx` | 1.12.0 | Kotlin расширения для Android |
| `androidx.appcompat` | 1.6.1 | Обратная совместимость UI |
| `com.google.android.material` | 1.11.0 | Material Design компоненты (Cards, Buttons, Tabs) |
| `androidx.constraintlayout` | 2.1.4 | Гибкие лейауты |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | 2.7.0 | MVVM ViewModel + Kotlin Coroutines |
| `androidx.lifecycle:lifecycle-livedata-ktx` | 2.7.0 | Реактивные данные |
| `androidx.activity:activity-ktx` | 1.8.2 | Activity Result API |
| `androidx.fragment:fragment-ktx` | 1.6.2 | Fragment API |
| `androidx.recyclerview` | 1.3.2 | Списки (сессии, сети, задания) |
| `androidx.preference:preference-ktx` | 1.2.1 | Экран настроек |
| `com.google.android.gms:play-services-location` | 21.2.0 | FusedLocationProvider (Anti-Throttling + GPS) |
| `com.google.code.gson` | 2.10.1 | JSON-парсинг (Task Blueprints, Upload Queue) |
| `kotlinx.coroutines` | (transitive) | Асинхронность (Dispatchers.IO, StateFlow) |

### 3. Структура модулей (Package Map)
```
com.example.wifiscanner/
├── MainActivity.kt              — Точка входа, ViewPager2 + TabLayout (4 вкладки), Runtime Permissions Onboarding
├── WifiScanService.kt           — Foreground Service: Wi-Fi polling + GPS + IMU + WakeLock + Doze Whitelist + IncrementalSync
├── adapters/
│   └── ViewPagerAdapter.kt      — Адаптер для 4-х вкладок ViewPager2
├── cloud/
│   ├── DiskConfig.kt            — Конфигурация OAuth-токена и путей Yandex Disk
│   ├── IncrementalSyncer.kt     — Периодический re-upload CSV (~30 сек) + неблокирующий flush через очередь
│   ├── UploadQueueManager.kt    — Персистентная JSON-очередь загрузки (offline-first, ретрай, watchdog)
│   └── YandexDiskClient.kt      — HTTP-клиент Yandex Disk REST API (list/download/upload/createFolder)
├── models/
│   ├── ScanSession.kt           — Модель сессии (для «Истории»)
│   ├── TaskModels.kt            — NodeDTO / ScanTaskDTO + deepCopyWithReset()
│   └── WifiScanResult.kt        — Модель одной Wi-Fi записи (20 полей)
├── repository/
│   └── WifiRepository.kt        — Singleton: StateFlow (isScanning, results, sensors, location, history)
├── ui/
│   ├── ScanFragment.kt          — Вкладка «Запись» (ручное сканирование)
│   ├── TasksFragment.kt         — Вкладка «Задания» (Smart Tasks с Yandex Disk)
│   ├── ControllerFragment.kt    — Режим контролёра: кнопки этажей/улица/подъезд + CSV-лог Ground Truth
│   ├── ViewFragment.kt          — Вкладка «Текущие сети» (live view)
│   ├── HistoryFragment.kt       — Вкладка «История» (список сессий)
│   ├── SettingsActivity.kt      — Экран настроек (интервал, cooldown, OAuth-токен)
│   └── RightValuePreference.kt  — Кастомная Preference с правым значением
├── utils/
│   ├── ControllerCsvLogger.kt   — Логирование контроллерных данных
│   ├── CsvLogger.kt             — Запись Wi-Fi данных в CSV (Manual + Tasks форматы)
│   ├── DiagnosticLogger.kt      — Диагностический CSV-лог (событийный, расширенный)
│   ├── FrequencyToChannel.kt    — Конвертер частоты → Wi-Fi канал
│   ├── PermissionHelper.kt      — Запрос runtime-разрешений
│   ├── SensorCollector.kt       — Сбор IMU-данных (шагомер, азимут, ориентация)
│   ├── TaskDownloader.kt        — Загрузка JSON-заданий с Yandex Disk
│   ├── TaskPersistence.kt       — Локальное сохранение дерева заданий (SharedPreferences + Gson)
│   ├── OemBatteryHelper.kt      — v5.3.0: Doze whitelist + OEM autostart guide (TECNO/Infinix/Itel)
│   ├── UIHelper.kt              — Action Sheet (Bottom Dialog)
│   └── WifiStateMonitor.kt      — Реалтайм-мониторинг WiFi on/off через BroadcastReceiver
└── viewmodel/
    └── ...                      — ViewModels для фрагментов
```

### 4. UI (4 вкладки)
| # | Вкладка | Fragment | Назначение |
|---|---|---|---|
| 0 | Текущие сети | `ViewFragment` | Live-просмотр Wi-Fi сетей вокруг |
| 1 | Запись | `ScanFragment` | Ручное сканирование с настраиваемым кол-вом слепков. Авто-выгрузка CSV + диаг.лога в Yandex Disk. 3-колоночный Dashboard (Шаги/IMU/GPS) через StateFlow |
| 2 | Задания | `TasksFragment` | Smart Tasks: загрузка JSON Blueprint-ов с Yandex Disk, иерархическая навигация (Дом→Подъезд→Этаж→Локация), Material Cards, авто-выгрузка при завершении подъезда |
| 3 | История | `HistoryFragment` | Список завершённых сессий с возможностью шаринга/удаления CSV |

### 5. Принцип работы Wi-Fi сканирования (Anti-Throttling)
Внедрена 100% защита от жестких блокировок ОС (Doze Mode и ограничение 4 скана в 2 минуты):
1. **Foreground Service** (`WifiScanService`): Служба переднего плана типов `TYPE_LOCATION` и `TYPE_HEALTH` (Android 14+), чтобы не «засыпали» шагомер и GPS.
2. **WakeLock** (`PARTIAL_WAKE_LOCK`): CPU не спит даже при выключенном экране. Захватывается при `onCreate()`, освобождается при `onDestroy()`.
3. **Doze Whitelist** (v5.3.0): `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — системный in-app диалог при старте сервиса. Без whitelist'а Doze игнорирует WakeLock и замораживает `delay()` в scanning loop (подтверждено на TECNO Pova 7 Ultra / HiOS 15).
4. **OEM Autostart Guide** (v5.3.0): На Transsion-устройствах (TECNO/Infinix/Itel) — однократный AlertDialog с инструкцией по включению автозапуска в Phone Master.
5. **Троянский конь (FusedLocationProvider)**: Прямые вызовы `wifiManager.startScan()` запрещены (приведут к блокировке сканера на 2 минуты). Служба раз в 5 секунд запрашивает локацию `PRIORITY_HIGH_ACCURACY` у Google Play Services. Высшие привилегии Google аппаратно принуждают включиться Wi-Fi антенну.
6. **Пассивный Polling**: Приложение бесконечно считывает системный кэш `wifiManager.scanResults`, куда «нечаянно» только что просканировал сервис Google.
7. **Анти-Дубликаты**: Полностью идентичные слепки (ОС не сканировала, вернула 100% старый кэш) отбраковываются: `if (currentMaxTimestamp == lastMaxTimestamp) { return }`. Счётчик прогресса (1/5) растёт только при реальных обновлениях эфира.
8. **Защита «Грязного старта» (Cooldown)**: Задержка перед первым сканом (по умолчанию 5 сек, `pref_scan_cooldown`), стабилизирующая телефон и выжигающая кэш-призраки.

### 6. IMU/PDR Edge Computing (`SensorCollector.kt`)
Сырые данные IMU-сенсоров обрабатываются локально с частотой `SENSOR_DELAY_UI`:
- **Шагомер**: связка `STEP_COUNTER` + fallback на мгновенный `STEP_DETECTOR` (для устройств без аппаратного счётчика).
- **Азимут**: `ROTATION_VECTOR` (Sensor Fusion: гироскоп + акселерометр) → 0-360° + `AzimuthConfidence` (0.0-1.0).
- **Ориентация устройства**: акселерометр (low-pass filter α=0.8) → классификация: `PORTRAIT`, `REVERSE_PORTRAIT`, `LANDSCAPE`, `REVERSE_LANDSCAPE`, `FACE_UP`, `FACE_DOWN`, `TILTED`, `UNKNOWN`. Компенсация эффекта «антенна вниз» на бэкенде.
- **Graceful Degradation**: нет шагомера → `stepsDelta`/`stepsTotal` = null; нет гироскопа → `azimuth` = null; акселерометр есть всегда.

### 7. Архитектура обмена данными (Yandex Disk REST API + Offline Queue)
- HTTP-клиент `YandexDiskClient.kt` (`java.net.HttpURLConnection`, Kotlin Coroutines), работающий исключительно с изолированной областью `app:/` (scope: `cloud_api:disk.app_folder`).
- **Автоматизация Заданий (Tasks)**: `TasksFragment` загружает JSON Blueprint-файлы из `app:/tasks/`. При завершении всех локаций внутри подъезда CSV **автоматически** выгружается в `app:/results/`.
- **Автоматизация Свободного Поиска (Scan)**: При остановке ручного сканирования (`ScanFragment`) CSV + диагностический лог **автоматически** выгружаются в `app:/results/`.
- **OAuth-токен**: зашит в `BuildConfig.YANDEX_DISK_TOKEN`, может быть переопределён через «Настройки».

#### 7.1. Инкрементальная синхронизация (`IncrementalSyncer`)
Периодический re-upload полного CSV на Яндекс.Диск прямо во время сканирования:
- Каждые 6 снимков (~30 сек при интервале 5 сек) файл перезаписывается на диске (`overwrite=true`).
- Watchdog: если upload висит >60 сек — флаг `isUploading` сбрасывается, следующий upload разрешается.
- Два параллельных синхронизатора: `incrementalSyncer` (CSV-данные) + `diagSyncer` (диагностический лог).
- **Маршрутизация папок**: `scan_results/` vs `task_results/` для данных; `diag_scans/` vs `diag_tasks/` для диагностики.
- Автоматическое создание удалённых папок при первом upload (`ensureRemoteFolder()`).

#### 7.2. Персистентная очередь загрузки (`UploadQueueManager`)
Offline-first механизм гарантированной доставки файлов:
- Файлы добавляются в JSON-очередь (`upload_queue.json`) мгновенно (< 1 мс, не блокирует UI).
- Очередь переживает перезапуск приложения и крэши.
- При наличии сети — немедленная загрузка. При отсутствии — ожидание с ретраем каждые 5 минут.
- Макс. 10 попыток на файл. Watchdog 5 минут для зависших задач.
- Инициализируется в `MainActivity.onCreate()`.

#### 7.3. Неблокирующий Shutdown (v5.2.1)
Критическое исправление: `onDestroy()` сервиса никогда не блокирует Main Thread:
1. `serviceScope.cancel()` — немедленная остановка scanning loop.
2. `flush()` → `UploadQueueManager.enqueue()` — мгновенное добавление в очередь (не `runBlocking` с сетевым вызовом).
3. Логирование `SERVICE_STOP`, освобождение ресурсов.
- В task-режиме `stopSelf()` не вызывается из `handleScanResults()` — жизненный цикл управляется только `TasksFragment.observeScanning()`.

### 8. Диагностический логгер (`DiagnosticLogger.kt`)
Событийный CSV-лог для анализа проблем с фоновым сканированием на разных устройствах:
- **Формат**: `Timestamp;ElapsedSec;Event;Detail;ScreenOn;DozeMode;BatteryPct;DeviceModel;AndroidVer`
- **События сканирования**: `SERVICE_START`, `SCAN_REQUEST`, `SCAN_RESULT`, `SCAN_SKIP` (duplicate/empty), `SCAN_ERROR`, `LOOP_TICK` (heartbeat ~10 мин), `WIFI_DISABLED`, `SERVICE_STOP`
- **События синхронизации**: `SYNC_INIT`, `SYNC_OK`, `SYNC_FAIL`, `SYNC_FLUSH`, `SYNC_WATCHDOG`, `SYNC_ERROR`, `SYNC_DIAG_INIT`
- **События системы**: `WAKELOCK_ACQ/REL`, `WIFI_STATE` (enabled/disabled/enabling/disabling)
- **Хранение**: `Documents/MyWifiScans/` (та же папка, что и сканы). Синхронизируется на Яндекс.Диск через `diagSyncer`.

#### 8.1. WiFi State Monitor (`WifiStateMonitor.kt`)
- Реалтайм-мониторинг WiFi through BroadcastReceiver во фрагментах UI.
- Дублирующий `wifiStateReceiver` в `WifiScanService` — работает при выключенном экране, когда фрагменты уничтожены.

### 9. Логирование и точность данных (Data Science Grade)
- **Философия «Пиши всё, фильтруй потом»**: Приложение собирает максимум сырых данных с радиоэфира. Ответственность за отбраковку «призраков» (Ghost networks) — на Python-бэкенде.
- `NetworkTimestamp` синхронизируется на `yyyy-MM-dd HH:mm:ss`. Python использует для отбраковки старых сетей: `df = df[df['Age'] < 7]`.
- GPS-координаты — **7 знаков** после запятой, строго с разделителем `.` (`Locale.US`).
- Упорядоченный `RecordNumber`: список аппаратно сортируется по RSSI **до** записи в CSV.

### 10. Формат экспорта данных (Flat-Table CSV)
**Ручные сканы (ScanFragment)**:
```
LocationName;Timestamp;MAC;RSSI;SSID;Frequency;RecordNumber;Channel;NetworkTimestamp;Latitude;Longitude;StepsDelta;StepsTotal;Azimuth;AzimuthConfidence;DeviceOrientation
```

**Задания (TasksFragment)**:
```
Timestamp;NodeId;Address;Entrance;Floor;LocationName;SSID;MAC;RSSI;Frequency;RecordNumber;Channel;NetworkTimestamp;Latitude;Longitude;StepsDelta;StepsTotal;Azimuth;AzimuthConfidence;DeviceOrientation
```

### 11. UI/UX дизайн-решения
- **Material Cards** (`CardView`, radius 8dp, elevation 4dp) для заданий и истории.
- **Динамическая навигация (Displacement)**: кнопка новой локации — в Breadcrumbs (`[ + ]`). Кнопка `[⬇ СЛЕДУЮЩИЙ ЭТАЖ]` всплывает снизу только после завершения текущих локаций.
- **Стабилизация верстки**: `android:minLines="2"` на строке статуса — нет «прыгающей кнопки».
- **MaterialButton** 56dp + векторные иконки (`Play`/`Stop`).
- **Smart Locking**: при старте скана кнопки соседних локаций прячутся для предотвращения параллельного сбора.
- **Компонентный подход**: все UI-элементы — через `component_*.xml` + `styles_components.xml`. Хардкод через `LayoutParams` запрещён.

---
## Версия 4 (Предыдущая) - [16.04.2026] (Full Snapshot: Yandex Disk + Diagnostics + Sensor Fusion)
### 1. Архитектура
- MVVM + Singleton State + Foreground Service (LOCATION + HEALTH) + IMU Sensor Fusion + Yandex Disk Cloud Sync
- Версия 5.0.0 (versionCode 8)
- compileSdk 34, minSdk 24, targetSdk 34, Java 17

### 2. Ключевые компоненты
- `WifiScanService.kt` — Foreground Service с Anti-Throttling через FusedLocationProvider
- `YandexDiskClient.kt` — HTTP-клиент Yandex Disk REST API
- `DiagnosticLogger.kt` — Событийный CSV-лог
- `SensorCollector.kt` — Edge Computing IMU/PDR (шагомер + азимут + ориентация)
- `DiskConfig.kt` — OAuth-токен и пути на Yandex Disk

### 3. UI: 4 вкладки (Текущие сети, Запись, Задания, История)
### 4. Форматы CSV: 2 формата (Manual + Tasks), 20 полей каждый

---
## Версия 3 (Устаревшая) - [15.04.2026] (Yandex Disk Automation)
### 1. Архитектура Обмена Данными (Yandex Disk REST API)
- Внедрен HTTP-клиент `YandexDiskClient.kt` (`java.net.HttpURLConnection`, Kotlin Coroutines), работающий исключительно с изолированной областью `app:/` (scope: `cloud_api:disk.app_folder`), обеспечивая безопасность личного диска пользователя.
- **Автоматизация Заданий (Tasks)**: В `TasksFragment` интегрирована загрузка файлов напрямую из облака `app:/tasks/`. При завершении всех локаций внутри объекта "Подъезд" файлы CSV **автоматически** выгружаются в `app:/results/` в фоновом режиме.
- **Автоматизация Свободного Поиска (Scan)**: При остановке ручного сканирования (`ScanFragment`) CSV файл с результатами (вместе с диагностическим логом) **автоматически** выгружается в `app:/results/`. 
- **Настройки OAuth**: Токен зашит в `BuildConfig.YANDEX_DISK_TOKEN` по умолчанию, но может быть переопределен пользователем через "Настройки".

