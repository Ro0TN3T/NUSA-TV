package com.nusatv.androidtv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load

class ChannelAdapter(
    private val onChannelClick: (TvChannel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    private val differ = AsyncListDiffer(this, object : DiffUtil.ItemCallback<TvChannel>() {
        override fun areItemsTheSame(oldItem: TvChannel, newItem: TvChannel) = oldItem.key == newItem.key
        override fun areContentsTheSame(oldItem: TvChannel, newItem: TvChannel) = oldItem == newItem
    })
    private var selectedKey: String? = null

    fun submitList(newList: List<TvChannel>, currentKey: String? = selectedKey) {
        val oldKey = selectedKey
        selectedKey = currentKey
        differ.submitList(newList) {
            if (oldKey != currentKey) {
                val oldPosition = differ.currentList.indexOfFirst { it.key == oldKey }
                val newPosition = differ.currentList.indexOfFirst { it.key == currentKey }
                if (oldPosition >= 0) notifyItemChanged(oldPosition)
                if (newPosition >= 0 && newPosition != oldPosition) notifyItemChanged(newPosition)
            }
        }
    }

    fun setSelectedChannel(key: String?) {
        val oldPosition = differ.currentList.indexOfFirst { it.key == selectedKey }
        selectedKey = key
        val newPosition = differ.currentList.indexOfFirst { it.key == selectedKey }
        if (oldPosition >= 0) notifyItemChanged(oldPosition)
        if (newPosition >= 0 && newPosition != oldPosition) notifyItemChanged(newPosition)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return ChannelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val channel = differ.currentList[position]
        holder.bind(channel, channel.key == selectedKey)
    }

    override fun getItemCount(): Int = differ.currentList.size

    inner class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivLogo: ImageView = itemView.findViewById(R.id.ivLogo)
        private val tvFallback: TextView = itemView.findViewById(R.id.tvLogoFallback)
        private val tvName: TextView = itemView.findViewById(R.id.tvChannelName)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvChannelMeta)
        private val badgeMulti: TextView = itemView.findViewById(R.id.badgeMulti)
        private val badgeNative: TextView = itemView.findViewById(R.id.badgeNative)

        fun bind(channel: TvChannel, isSelected: Boolean) {
            itemView.isSelected = isSelected
            tvName.text = channel.name
            val now = channel.currentProgramTitle?.takeIf { it.isNotBlank() }
            val next = channel.nextProgramTitle?.takeIf { it.isNotBlank() }
            tvMeta.text = when {
                now != null && next != null -> "● $now  •  Berikutnya: $next"
                now != null -> "● $now"
                channel.programTitle?.isNotBlank() == true -> channel.programTitle
                else -> "${channel.country} • ${channel.sources.size} server"
            }
            badgeMulti.visibility = if (channel.sources.size > 1) View.VISIBLE else View.GONE
            badgeNative.visibility = if (channel.sources.any { it.requiresCustomHeader } ) View.VISIBLE else View.GONE
            tvFallback.text = channel.name.take(3).uppercase()
            if (channel.logo.isNotBlank()) {
                ivLogo.visibility = View.VISIBLE
                tvFallback.visibility = View.GONE
                ivLogo.load(channel.logo) {
                    crossfade(false)
                    size(96, 96)
                    listener(onError = { _, _ ->
                        ivLogo.visibility = View.GONE
                        tvFallback.visibility = View.VISIBLE
                    })
                }
            } else {
                ivLogo.setImageDrawable(null)
                ivLogo.visibility = View.GONE
                tvFallback.visibility = View.VISIBLE
            }
            itemView.setOnClickListener { onChannelClick(channel) }
        }
    }
}
