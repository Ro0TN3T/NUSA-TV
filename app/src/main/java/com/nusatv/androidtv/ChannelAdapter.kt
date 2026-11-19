package com.nusatv.androidtv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class ChannelAdapter(
    private val onChannelClick: (TvChannel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    private var items: List<TvChannel> = emptyList()
    private var selectedKey: String? = null

    fun submitList(newList: List<TvChannel>, currentKey: String? = selectedKey) {
        items = newList
        selectedKey = currentKey
        notifyDataSetChanged()
    }

    fun setSelectedChannel(key: String?) {
        selectedKey = key
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return ChannelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(items[position], items[position].key == selectedKey)
    }

    override fun getItemCount(): Int = items.size

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
            badgeNative.visibility = if (channel.sources.any { it.requiresCustomHeader }) View.VISIBLE else View.GONE

            val initials = channel.name.take(3).uppercase()
            tvFallback.text = initials

            if (channel.logo.isNotEmpty()) {
                ivLogo.visibility = View.VISIBLE
                tvFallback.visibility = View.GONE
                ivLogo.load(channel.logo) {
                    crossfade(true)
                    listener(
                        onError = { _, _ ->
                            ivLogo.visibility = View.GONE
                            tvFallback.visibility = View.VISIBLE
                        }
                    )
                }
            } else {
                ivLogo.visibility = View.GONE
                tvFallback.visibility = View.VISIBLE
            }

            itemView.setOnClickListener {
                onChannelClick(channel)
            }
        }
    }
}
