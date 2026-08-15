package livefoto.xyspace.app

/**
 * XySpace Personal Use License v1.0
 * Copyright 2026 XySpace — Haekal Saputra (KALL)
 * 
 * This source is free for personal, educational, non-commercial use.
 * Commercial use requires separate written permission from XySpace.
 * See LICENSE file for full terms.
 */

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import com.google.android.material.button.MaterialButton

object CustomDialogs {

    data class ChoiceItem(val title: String, val desc: String = "")

    fun showChoiceDialog(
        context: Context,
        title: String,
        subtitle: String,
        choices: List<ChoiceItem>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val inflater = LayoutInflater.from(context)
        val parent = FrameLayout(context)
        val view = inflater.inflate(R.layout.dialog_custom_select, parent, false)
        dialog.setContentView(view)

        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.90).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        view.findViewById<TextView>(R.id.tvDialogTitle).text = title
        val subView = view.findViewById<TextView>(R.id.tvDialogSub)
        if (subtitle.isBlank()) {
            subView.visibility = View.GONE
        } else {
            subView.visibility = View.VISIBLE
            subView.text = subtitle
        }

        val container = view.findViewById<LinearLayout>(R.id.containerChoices)
        container.removeAllViews()

        for (i in choices.indices) {
            val item = choices[i]
            val itemView = inflater.inflate(R.layout.item_custom_choice, container, false)
            val tvTitle = itemView.findViewById<TextView>(R.id.tvChoiceTitle)
            val tvDesc = itemView.findViewById<TextView>(R.id.tvChoiceDesc)
            val imgCheck = itemView.findViewById<ImageView>(R.id.imgChoiceCheck)

            tvTitle.text = item.title
            if (item.desc.isBlank()) {
                tvDesc.visibility = View.GONE
            } else {
                tvDesc.visibility = View.VISIBLE
                tvDesc.text = item.desc
            }

            val isSelected = i == selectedIndex
            if (isSelected) {
                itemView.setBackgroundResource(R.drawable.bg_tool_selected)
                tvTitle.setTextColor(Settings.color(context, R.attr.appAccent))
                imgCheck.visibility = View.VISIBLE
            } else {
                itemView.setBackgroundResource(R.drawable.bg_tool_off)
                tvTitle.setTextColor(Settings.color(context, R.attr.appTextHigh))
                imgCheck.visibility = View.GONE
            }

            itemView.setOnClickListener {
                Settings.triggerHaptic(it)
                onSelected(i)
                dialog.dismiss()
            }

            container.addView(itemView)
        }

        view.findViewById<MaterialButton>(R.id.btnDialogCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    fun showConfirmDialog(
        context: Context,
        title: String,
        message: String,
        confirmText: String = "Hapus",
        onConfirm: () -> Unit
    ) {
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Batal", null)
            .setPositiveButton(confirmText) { _, _ ->
                onConfirm()
            }
            .show()
    }
}
