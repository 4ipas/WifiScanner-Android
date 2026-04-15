package com.example.wifiscanner.ui

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.example.wifiscanner.R
import com.example.wifiscanner.cloud.DiskConfig
import com.example.wifiscanner.cloud.YandexDiskClient
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
            
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Настройки"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            
            val intervalPref: RightValuePreference? = findPreference("pref_scan_interval")
            val cooldownPref: RightValuePreference? = findPreference("pref_scan_cooldown")
            
            val bindNumbers = { pref: RightValuePreference? ->
                pref?.setOnBindEditTextListener { editText ->
                    editText.inputType = InputType.TYPE_CLASS_NUMBER
                }
                
                pref?.setOnPreferenceChangeListener { _, newValue ->
                    val stringValue = newValue.toString()
                    if (stringValue.isEmpty()) return@setOnPreferenceChangeListener false
                    
                    try {
                        val value = stringValue.toInt()
                        if (value in 1..1440) {
                            // Обязательно оповещаем адаптер кастомной View, что текст изменился
                            pref.text = stringValue
                            return@setOnPreferenceChangeListener true
                        } else {
                            Toast.makeText(requireContext(), "Значение должно быть от 1 до 1440", Toast.LENGTH_SHORT).show()
                            return@setOnPreferenceChangeListener false
                        }
                    } catch (e: NumberFormatException) {
                        return@setOnPreferenceChangeListener false
                    }
                }
            }
            
            bindNumbers(intervalPref)
            bindNumbers(cooldownPref)
            
            val testPref: Preference? = findPreference("pref_yadisk_test")
            testPref?.setOnPreferenceClickListener {
                val token = DiskConfig.getToken(requireContext())
                if (token.isBlank()) {
                    Toast.makeText(requireContext(), "Токен не задан", Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceClickListener true
                }
                
                Toast.makeText(requireContext(), "Проверка...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    val result = YandexDiskClient.listFiles(token, "app:/")
                    if (result.isSuccess) {
                        Toast.makeText(requireContext(), "Успешно! Доступ к app:/ есть", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "Ошибка: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }
                true
            }
        }
    }
}
