package com.saimega.vinayakacablenetwork

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class PaymentHistoryAdapter(
    private var list: List<PaymentModel>,
    private val canEdit: Boolean,
    private val onActionClick: (paymentId: String, action: String) -> Unit
) : RecyclerView.Adapter<PaymentHistoryAdapter.ViewHolder>() {

    fun updateData(newList: List<PaymentModel>) {
        list = newList
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDate: TextView   = view.findViewById(R.id.tvDate)
        val tvMode: TextView   = view.findViewById(R.id.tvMode)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        val tvAmount: TextView = view.findViewById(R.id.tvAmount)
        val btnView: MaterialButton  = view.findViewById(R.id.btnView)
        val btnEdit: MaterialButton  = view.findViewById(R.id.btnEdit)
        val btnPrint: MaterialButton = view.findViewById(R.id.btnPrint)
        val btnShare: MaterialButton = view.findViewById(R.id.btnShare)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_payment_history, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = list.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val p = list[position]

        holder.tvDate.text = p.date.ifEmpty { "N/A" }
        
        holder.tvMode.text = when {
            p.paymentMode.equals("Cash", ignoreCase = true) -> "Cash"
            p.paymentNumber.isNotEmpty() -> "${p.paymentMode} · ${p.paymentNumber}"
            else -> p.paymentMode
        }

        // Amount
        val amountStr = if (p.paid == p.paid.toLong().toDouble()) p.paid.toLong().toString() else String.format("%.2f", p.paid)
        holder.tvAmount.text = holder.tvAmount.context.getString(R.string.rupee_value, amountStr)

        // Status
        if (p.remaining <= 0) {
            holder.tvStatus.text = holder.tvStatus.context.getString(R.string.paid_fully_label)
            holder.tvStatus.setTextColor(ContextCompat.getColor(holder.tvStatus.context, R.color.cm_teal))
            holder.tvStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(holder.tvStatus.context, R.color.cm_teal_soft)
            )
        } else {
            holder.tvStatus.text = holder.tvStatus.context.getString(R.string.partial_due_format, p.remaining.toString())
            holder.tvStatus.setTextColor(ContextCompat.getColor(holder.tvStatus.context, R.color.cm_coral))
            holder.tvStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(holder.tvStatus.context, R.color.cm_coral_soft)
            )
        }

        holder.btnEdit.visibility = if (canEdit) View.VISIBLE else View.GONE

        holder.btnView.setOnClickListener { onActionClick(p.paymentId, "VIEW") }
        holder.btnEdit.setOnClickListener { onActionClick(p.paymentId, "EDIT") }
        holder.btnPrint.setOnClickListener { onActionClick(p.paymentId, "PRINT") }
        holder.btnShare.setOnClickListener { onActionClick(p.paymentId, "SHARE") }
    }
}
