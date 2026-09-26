package com.saimega.vinayakacablenetwork

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.saimega.vinayakacablenetwork.databinding.ItemCustomerCardBinding
import android.util.Log

/**
 * CustomerAdapter
 *
 * Uses [ListAdapter] + [DiffUtil.ItemCallback] for efficient, flicker-free updates.
 * Internally keeps a FULL list and a FILTERED list so search works instantly
 * without re-fetching from Firestore.
 *
 * Usage:
 *   adapter.submitFullList(viewModel.customers)  ← on LiveData update
 *   adapter.filter("search term")                ← on SearchView text change
 */
class CustomerAdapter(
    private val onItemClick: (CustomerModel) -> Unit
) : ListAdapter<CustomerModel, CustomerAdapter.CustomerViewHolder>(DIFF_CALLBACK) {

    // ── Full (unfiltered) backing list ────────────────────────────────────────
    private var fullList: List<CustomerModel> = emptyList()

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<CustomerModel>() {
            // Two items represent the same customer iff their IDs match (Series number)
            override fun areItemsTheSame(old: CustomerModel, new: CustomerModel) =
                old.id == new.id

            // Check for data changes — data class equals() handles this perfectly
            override fun areContentsTheSame(old: CustomerModel, new: CustomerModel) =
                old == new
        }

        // Avatar colours cycling through the app palette
        private val AVATAR_COLORS = listOf(
            R.color.cm_blue, R.color.cm_teal, R.color.cm_amber, R.color.cm_coral
        )
    }

    // ── ViewHolder ────────────────────────────────────────────────────────────
    inner class CustomerViewHolder(
        private val binding: ItemCustomerCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CustomerModel) {
            // Debug log – high visibility
            Log.e("DATABASE_CHECK", "Customer: ${item.name} | Raw Status: ${item.status}")
            val monthStatus = monthlyStatuses[item.id]
            val activeForMonth = isActiveForMonth(item)
            val displayStatus = statusForMonth(item)

            // Name
            binding.tvCustomerName.text = item.displayName(LocaleHelper.getLanguage(binding.root.context))

            // Series
            binding.tvSeries.text = binding.root.context.getString(
                R.string.customer_series_vc_format,
                item.id,
                item.vcNumber.ifBlank { item.id }
            )
            binding.tvPackageRate.visibility = android.view.View.VISIBLE
            binding.tvPackageRate.text = binding.root.context.getString(
                R.string.customer_package_rate_format,
                item.packageId.ifBlank { binding.root.context.getString(R.string.package_unspecified) },
                (monthStatus?.bill ?: item.monthlyCharge.takeIf { it > 0.0 } ?: item.baseAmount).toInt()
            )

            // Determine UI state from Firestore status field — never from local arithmetic.
            // DC rule: pendingAmount is the authoritative bill; we never re-add baseAmount.
            when {
                !activeForMonth -> {
                    binding.tvStatus.visibility = android.view.View.GONE
                    binding.tvAmount.visibility = if (item.pendingAmount > 0.0) android.view.View.VISIBLE else android.view.View.GONE
                    binding.tvAmountBreakdown.visibility = android.view.View.GONE
                    if (item.pendingAmount > 0.0) {
                        binding.tvAmount.text = binding.root.context.getString(
                            R.string.total_due_amount_format,
                            item.pendingAmount.toInt()
                        )
                    }
                }
                displayStatus == "paid" -> {
                    binding.tvStatus.visibility = android.view.View.VISIBLE
                    binding.tvStatus.text = binding.root.context.getString(R.string.paid_caps)
                    binding.tvStatus.setTextColor(Color.WHITE)
                    binding.tvStatus.backgroundTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(binding.root.context, R.color.cm_teal))
                    binding.tvAmount.visibility = android.view.View.VISIBLE
                    binding.tvAmountBreakdown.visibility = android.view.View.GONE
                    val billedAmount = monthStatus?.bill ?: item.monthlyCharge.takeIf { it > 0.0 } ?: item.baseAmount
                    binding.tvAmount.text = if (billedAmount > 0) "₹${billedAmount.toInt()}" else "₹0"
                }
                displayStatus == "partial" -> {
                    binding.tvStatus.visibility = android.view.View.VISIBLE
                    binding.tvStatus.text = binding.root.context.getString(R.string.partial_caps)
                    binding.tvStatus.setTextColor(Color.WHITE)
                    binding.tvStatus.backgroundTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(binding.root.context, R.color.cm_amber))
                    
                    // DC Rule: pendingAmount is already the full outstanding bill — don't re-add baseAmount.
                    val totalDue = monthStatus?.remaining ?: item.pendingAmount
                    binding.tvAmount.visibility = android.view.View.VISIBLE
                    binding.tvAmount.text = binding.root.context.getString(R.string.due_amount_format, totalDue.toInt())

                    binding.tvAmountBreakdown.visibility = android.view.View.VISIBLE
                    binding.tvAmountBreakdown.text = binding.root.context.getString(R.string.pending_amount_format, totalDue.toInt())
                }
                else -> {
                    // unpaid
                    binding.tvStatus.visibility = android.view.View.VISIBLE
                    binding.tvStatus.text = binding.root.context.getString(R.string.unpaid_caps)
                    binding.tvStatus.setTextColor(Color.WHITE)
                    binding.tvStatus.backgroundTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(binding.root.context, R.color.cm_coral))
                    
                    // DC Rule: pendingAmount is already the full outstanding bill — don't re-add baseAmount.
                    val totalDue = monthStatus?.remaining ?: item.pendingAmount
                    binding.tvAmount.visibility = android.view.View.VISIBLE
                    binding.tvAmount.text = binding.root.context.getString(R.string.total_due_amount_format, totalDue.toInt())

                    binding.tvAmountBreakdown.visibility = android.view.View.VISIBLE
                    binding.tvAmountBreakdown.text = binding.root.context.getString(R.string.pending_amount_format, totalDue.toInt())
                }
            }

            // Avatar: first letter of name, colour determined by hashCode for consistency
            val letter = item.displayName(LocaleHelper.getLanguage(binding.root.context))
                .firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            binding.tvAvatar.text = letter
            val color = AVATAR_COLORS[Math.abs(item.id.hashCode()) % AVATAR_COLORS.size]
            binding.tvAvatar.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(binding.root.context, color))

            // Click
            binding.root.setOnClickListener { onItemClick(item) }

            if (!activeForMonth) {
                binding.chipDeactivated.visibility = android.view.View.VISIBLE
                binding.chipDeactivated.text = binding.root.context.getString(R.string.cut_inactive)
                binding.tvPackageRate.visibility = android.view.View.GONE
            } else {
                binding.chipDeactivated.visibility = android.view.View.GONE
            }
        }
    }

    // ── ListAdapter overrides ─────────────────────────────────────────────────
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CustomerViewHolder {
        val binding = ItemCustomerCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CustomerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CustomerViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called when the ViewModel emits a new page.
     * Stores the full list and re-applies any active search filter.
     * Returns the resulting (post-filter) item count so callers can drive
     * empty-state visibility without racing ListAdapter's async DiffUtil dispatch.
     */
    fun submitFullList(newList: List<CustomerModel>): Int {
        fullList = newList
        return applyFilter(currentQuery)
    }

    /**
     * Instantly filters the displayed list without touching Firestore.
     * Returns the resulting item count (see [submitFullList]).
     */
    fun filter(query: String): Int {
        currentQuery = query
        return applyFilter(query)
    }

    /**
     * Sets the status filter (paid/unpaid/partial/all) applied when there is
     * no active search query. Returns the resulting item count.
     */
    fun setStatusFilter(status: String): Int {
        statusFilter = status.lowercase()
        return applyFilter(currentQuery)
    }

    private var currentQuery = ""
    private var statusFilter = "all"
    private var selectedMonthKey = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.getDefault()).format(java.util.Date())
    private var monthlyStatuses: Map<String, CustomerMonthStatus> = emptyMap()

    fun setMonthFilter(monthKey: String, statuses: Map<String, CustomerMonthStatus>): Int {
        selectedMonthKey = monthKey
        monthlyStatuses = statuses
        return applyFilter(currentQuery)
    }

    private fun statusForMonth(customer: CustomerModel): String {
        monthlyStatuses[customer.id]?.status?.let { return it }
        val currentMonth = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.getDefault()).format(java.util.Date())
        if (selectedMonthKey == currentMonth) return customer.status.lowercase()
        return if (customer.lastPaidMonth == selectedMonthKey) "paid" else "unpaid"
    }

    private fun isActiveForMonth(customer: CustomerModel): Boolean = BillingCycle.isActiveForMonth(
        CustomerBillingState(
            id = customer.id,
            connectionStatus = customer.connectionStatus,
            pendingAmount = customer.pendingAmount,
            monthlyCharge = customer.monthlyCharge,
            lastBilledMonth = customer.lastBilledMonth,
            deactivatedMonth = customer.deactivatedMonth,
            reconnectedMonth = customer.reconnectedMonth
        ),
        selectedMonthKey
    )

    private fun applyFilter(query: String): Int {
        val result = if (query.isBlank()) {
            // No active search — browse the status-filtered list (e.g. "Paid" from a stat card).
            val byStatus = when (statusFilter) {
                "paid" -> fullList.filter { isActiveForMonth(it) && statusForMonth(it) == "paid" }
                "unpaid" -> fullList.filter { isActiveForMonth(it) && statusForMonth(it) == "unpaid" }
                "partial" -> fullList.filter { isActiveForMonth(it) && statusForMonth(it) == "partial" }
                "active" -> fullList.filter { isActiveForMonth(it) }
                "inactive" -> fullList.filterNot { isActiveForMonth(it) }
                else -> fullList
            }
            byStatus.sortedBy { it.name.lowercase() }
        } else {
            // Active search always searches every customer, regardless of the
            // status filter — typing a name/series number that belongs to a
            // different status than the current filter must still find it.
            val lower = query.lowercase()
            fullList.filter { c ->
                c.name.lowercase().contains(lower) ||
                c.teluguName.lowercase().contains(lower) ||
                c.id.lowercase().contains(lower) ||
                c.phone.lowercase().contains(lower) ||
                c.vcNumber.lowercase().contains(lower) ||
                c.boxNumber.lowercase().contains(lower) ||
                c.crfNumber.lowercase().contains(lower)
            }.sortedWith(
                compareByDescending<CustomerModel> { it.name.lowercase().startsWith(lower) }
                    .thenBy { it.name.lowercase() }
            )
        }
        submitList(result)
        return result.size
    }
}
