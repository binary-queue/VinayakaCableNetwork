package com.saimega.vinayakacablenetwork

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class RecentBillsAdapter(
    private var list: List<PaymentModel>
) : RecyclerView.Adapter<RecentBillsAdapter.ViewHolder>() {

    fun updateData(newList: List<PaymentModel>) {
        list = newList
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val vAvatarBg: View = view.findViewById(R.id.vAvatarBg)
        val tvAvatarInitial: TextView = view.findViewById(R.id.tvAvatarInitial)
        val tvCustomerName: TextView = view.findViewById(R.id.tvCustomerName)
        val tvAmount: TextView = view.findViewById(R.id.tvAmount)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_bill, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = list.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val p = list[position]

        val name = p.name.ifEmpty { "Unknown" }
        holder.tvCustomerName.text = name
        holder.tvAvatarInitial.text = name.firstOrNull()?.uppercase() ?: "?"

        val colors = intArrayOf(R.color.cm_blue, R.color.cm_teal, R.color.cm_amber, R.color.cm_coral)
        val colorHash = kotlin.math.abs(name.hashCode()) % colors.size
        holder.vAvatarBg.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(holder.vAvatarBg.context, colors[colorHash])
        )

        val amountStr = if (p.paid == p.paid.toLong().toDouble()) p.paid.toLong().toString() else String.format("%.2f", p.paid)
        holder.tvAmount.text = "+₹$amountStr"

        holder.tvTime.text = p.date.ifEmpty { "Today" }
    }
}
