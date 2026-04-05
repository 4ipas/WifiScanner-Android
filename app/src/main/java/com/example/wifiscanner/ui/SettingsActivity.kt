package com.example.wifiscanner.ui

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import com.example.wifiscanner.R

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
        }
    }
}
