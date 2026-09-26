package com.saimega.vinayakacablenetwork

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * ReportAdapter
 *
 * Binds a flat list of [PaymentModel] to item_report.xml rows.
 * No pagination — the full date-range list is loaded once and displayed.
 * RecyclerView handles view recycling automatically for smooth scrolling
 * even with 100+ items.
 */
class ReportAdapter(
    initialList: List<PaymentModel>
) : RecyclerView.Adapter<ReportAdapter.ViewHolder>() {

    // Mutable backing list — updated via updateList() so the adapter is never
    // detached and re-attached between data refreshes.
    private val list = initialList.toMutableList()

    /** Replaces the displayed data and refreshes all visible rows. */
    fun updateList(newList: List<PaymentModel>) {
        list.clear()
        list.addAll(newList)
        notifyDataSetChanged()
    }

    // ── ViewHolder ────────────────────────────────────────────────────────────
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName:   TextView = view.findViewById(R.id.tvName)
        val tvSeries: TextView = view.findViewById(R.id.tvSeries)
        val tvAmount: TextView = view.findViewById(R.id.tvAmount)
        val tvMode:   TextView = view.findViewById(R.id.tvMode)
    }

    // ── Adapter overrides ─────────────────────────────────────────────────────
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_report, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = list.size


    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val p = list[position]

        // Name
        val displayName = if (LocaleHelper.getLanguage(holder.itemView.context) == "te" && p.teluguName.isNotBlank()) p.teluguName else p.name
        holder.tvName.text = displayName.ifEmpty { "—" }

        // Series (customerId)
        holder.tvSeries.text = holder.itemView.context.getString(R.string.series_format, p.customerId.ifEmpty { "N/A" })

        // Paid amount — mint for Cash, blue for digital payments
        holder.tvAmount.text = holder.itemView.context.getString(R.string.rupee_value, p.paid.toLong().toString())
        holder.tvAmount.setTextColor(
            if (p.paymentMode.equals("Cash", ignoreCase = true))
                ContextCompat.getColor(holder.itemView.context, R.color.cm_teal)
            else
                ContextCompat.getColor(holder.itemView.context, R.color.cm_blue)
        )

        // Payment mode + optional reference number
        holder.tvMode.text = when {
            p.paymentMode.equals("Cash", ignoreCase = true) -> "Cash"
            p.paymentNumber.isNotEmpty() -> "${p.paymentMode} · ${p.paymentNumber}"
            else -> p.paymentMode
        }
    }
}