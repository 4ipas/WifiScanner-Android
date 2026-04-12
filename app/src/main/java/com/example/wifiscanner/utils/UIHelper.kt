package com.example.wifiscanner.utils

import android.content.Context
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.wifiscanner.R
import com.google.android.material.bottomsheet.BottomSheetDialog

data class ActionSheetItem(
    val text: String,
    val isDanger: Boolean = false,
    val action: () -> Unit
)

object UIHelper {

    fun showActionSheet(context: Context, items: List<ActionSheetItem>) {
        val bottomSheetDialog = BottomSheetDialog(context)
        
        val view = android.view.LayoutInflater.from(context).inflate(R.layout.component_action_sheet, null)
        val container = view.findViewById<LinearLayout>(R.id.llActionItems)
        val btnCancel = view.findViewById<Button>(R.id.btnActionCancel)
        
        val density = context.resources.displayMetrics.density
        val dpToPx = { dp: Int -> (dp * density).toInt() }
        
        for ((index, item) in items.withIndex()) {
            val layoutRes = if (item.isDanger) R.layout.component_action_sheet_item_danger
                            else R.layout.component_action_sheet_item
            val itemView = android.view.LayoutInflater.from(context)
                .inflate(layoutRes, container, false) as TextView
            itemView.text = item.text
            
            if (index > 0) {
                val params = itemView.layoutParams as LinearLayout.LayoutParams
                params.topMargin = dpToPx(2)
                itemView.layoutParams = params
            }
            
            itemView.setOnClickListener {
                item.action.invoke()
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
