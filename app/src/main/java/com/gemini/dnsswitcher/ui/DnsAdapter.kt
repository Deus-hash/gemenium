package com.gemini.dnsswitcher.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.gemini.dnsswitcher.R
import com.gemini.dnsswitcher.data.model.DnsServer
import com.gemini.dnsswitcher.databinding.ItemDnsBinding

class DnsAdapter(
    private val onSelectClick: (DnsServer) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onEditClick: (DnsServer) -> Unit,
    private val onDeleteClick: (DnsServer) -> Unit
) : RecyclerView.Adapter<DnsAdapter.DnsViewHolder>() {

    private var items: MutableList<DnsServer> = mutableListOf()
    private var selectedId: String? = null
    private var activeId: String? = null

    @SuppressLint("NotifyDataSetChanged")
    fun submitData(newList: List<DnsServer>, selected: String?, active: String?) {
        items = newList.toMutableList()
        selectedId = selected
        activeId = active
        notifyDataSetChanged()
    }

    fun getItems(): List<DnsServer> = items

    fun onItemMove(fromPosition: Int, toPosition: Int) {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                java.util.Collections.swap(items, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                java.util.Collections.swap(items, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
    }

    inner class DnsViewHolder(val binding: ItemDnsBinding) :
        RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("ClickableViewAccessibility")
        fun bind(item: DnsServer) {
            binding.tvDnsName.text = item.name
            binding.tvDnsIp.text = item.host

            val isSelected = item.id == selectedId
            val isActive = item.id == activeId

            binding.rbSelected.isChecked = isSelected

            if (isActive) {
                binding.tvActiveBadge.visibility = View.VISIBLE
                binding.cardDns.strokeColor = ContextCompat.getColor(binding.root.context, R.color.status_green)
                binding.cardDns.strokeWidth = 4
            } else if (isSelected) {
                binding.tvActiveBadge.visibility = View.GONE
                binding.cardDns.strokeColor = ContextCompat.getColor(binding.root.context, R.color.purple_500)
                binding.cardDns.strokeWidth = 3
            } else {
                binding.tvActiveBadge.visibility = View.GONE
                binding.cardDns.strokeColor = Color.parseColor("#334155")
                binding.cardDns.strokeWidth = 2
            }

            binding.cardDns.setOnClickListener { onSelectClick(item) }
            binding.rbSelected.setOnClickListener { onSelectClick(item) }

            binding.btnEdit.setOnClickListener { onEditClick(item) }
            binding.btnDelete.setOnClickListener { onDeleteClick(item) }

            binding.ivDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartDrag(this)
                }
                false
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DnsViewHolder {
        val binding = ItemDnsBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DnsViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DnsViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}
