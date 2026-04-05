package com.example.wifiscanner.utils

/**
 * Конвертирует частоту Wi-Fi (МГц) в номер канала.
 * Поддерживает диапазоны 2.4 GHz, 5 GHz и 6 GHz.
 *
 * @param frequencyMhz Частота в мегагерцах (например, 2412, 5180, 5955).
 * @return Номер канала (например, 1, 36, 1). Возвращает -1 если частота не распознана.
 */
fun frequencyToChannel(frequencyMhz: Int): Int {
    return when {
        // 2.4 GHz band: channels 1-13 (2412-2472 MHz, step 5 MHz)
        frequencyMhz in 2412..2472 -> (frequencyMhz - 2412) / 5 + 1
        // 2.4 GHz band: channel 14 (2484 MHz, Japan only)
        frequencyMhz == 2484 -> 14

        // 5 GHz band: channels 7-196 (5035-5980 MHz, step 5 MHz)
        frequencyMhz in 5035..5980 -> (frequencyMhz - 5000) / 5

        // 6 GHz band: channels 1-233 (5955-6415+ MHz, step 5 MHz)
        frequencyMhz in 5955..7115 -> (frequencyMhz - 5950) / 5

        else -> -1
    }
}
