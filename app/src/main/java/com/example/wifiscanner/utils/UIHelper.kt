package com.example.wifiscanner.utils

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.wifiscanner.R
import com.google.android.material.bottomsheet.BottomSheetDialog

object UIHelper {

    fun showActionSheet(context: Context, items: List<Pair<String, () -> Unit>>) {
        val bottomSheetDialog = BottomSheetDialog(context)
        
        val view = android.view.LayoutInflater.from(context).inflate(R.layout.component_action_sheet, null)
        val container = view.findViewById<LinearLayout>(R.id.llActionItems)
        val btnCancel = view.findViewById<Button>(R.id.btnActionCancel)
        
        val density = context.resources.displayMetrics.density
        val dpToPx = { dp: Int -> (dp * density).toInt() }
        
        for ((index, item) in items.withIndex()) {
            val itemView = android.view.LayoutInflater.from(context).inflate(R.layout.component_action_sheet_item, container, false) as TextView
            itemView.text = item.first
            
            if (index > 0) {
                val params = itemView.layoutParams as LinearLayout.LayoutParams
                params.topMargin = dpToPx(2)
                itemView.layoutParams = params
            }
            
            itemView.setOnClickListener {
                item.second.invoke()
                bottomSheetDialog.dismiss()
            }
            container.addView(itemView)
        }
        
        btnCancel.setOnClickListener {
            bottomSheetDialog.dismiss()
        }
        
        bottomSheetDialog.setContentView(view)
        bottomSheetDialog.show()
    }
}
