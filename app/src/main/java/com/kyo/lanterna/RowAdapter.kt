package com.kyo.lanterna

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class Row(val title: String, val subtitle: String = "", val icon: String = "", val tag: Any? = null)

class RowAdapter(
    private val onClick: (Row) -> Unit,
    private val onOptions: (Row) -> Unit = {}
) : RecyclerView.Adapter<RowAdapter.VH>() {

    init {
        setHasStableIds(true)
    }

    var rows: List<Row> = emptyList()
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: TextView = v.findViewById(R.id.icon)
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView = v.findViewById(R.id.subtitle)
    }

    override fun getItemCount() = rows.size
    override fun getItemId(position: Int) = position.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val row = rows[position]
        h.icon.text = row.icon
        h.title.text = row.title
        h.sub.text = row.subtitle
        h.sub.visibility = if (row.subtitle.isEmpty()) View.GONE else View.VISIBLE
        h.itemView.setOnClickListener { onClick(row) }
        h.itemView.setOnLongClickListener { onOptions(row); true }
        // Tasto ≡ (menu) del telecomando Fire TV = opzioni
        h.itemView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                onOptions(row); true
            } else false
        }
    }
}
