package com.example.wifiscanner.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceViewHolder
import com.example.wifiscanner.R

class RightValuePreference(context: Context, attrs: AttributeSet?) : EditTextPreference(context, attrs) {

    init {
        widgetLayoutResource = R.layout.pref_value_widget
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val tvValue = holder.findViewById(R.id.tvWidgetValue) as? TextView
        tvValue?.text = text // 'text' содержит текущее значение (например, "5")
    }
}
