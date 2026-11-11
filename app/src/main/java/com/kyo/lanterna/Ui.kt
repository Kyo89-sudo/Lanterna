package com.kyo.lanterna

import android.content.res.ColorStateList
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

private fun View.focusScale(scale: Float) {
    setOnFocusChangeListener { v, f ->
        v.animate().scaleX(if (f) scale else 1f).scaleY(if (f) scale else 1f).setDuration(120).start()
    }
}

private fun View.optionsKeys(onOptions: () -> Unit) {
    setOnLongClickListener { onOptions(); true }
    // tasto ≡ (menu) del telecomando = opzioni
    setOnKeyListener { _, keyCode, event ->
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP) onOptions()
            true
        } else false
    }
}

// ---------------- righe (liste cartelle, impostazioni) ----------------

data class Row(
    val title: String,
    val subtitle: String = "",
    val icon: Int = 0,
    val tag: Any? = null,
    val progress: Int = -1,
    val trailing: String = "",
    val watched: Boolean = false,
    val accent: Boolean = false
)

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
        val iconBox: View = v.findViewById(R.id.icon_box)
        val icon: ImageView = v.findViewById(R.id.icon)
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView = v.findViewById(R.id.subtitle)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val trailing: TextView = v.findViewById(R.id.trailing)
        val check: ImageView = v.findViewById(R.id.check)
    }

    override fun getItemCount() = rows.size
    override fun getItemId(position: Int) = position.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = rows[position]
        val ctx = h.itemView.context
        if (r.icon != 0) {
            h.iconBox.visibility = View.VISIBLE
            h.icon.setImageResource(r.icon)
            h.icon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, if (r.accent) R.color.accent else R.color.text_dim)
            )
        } else h.iconBox.visibility = View.GONE
        h.title.text = r.title
        h.sub.text = r.subtitle
        h.sub.visibility = if (r.subtitle.isEmpty()) View.GONE else View.VISIBLE
        h.progress.visibility = if (r.progress >= 0) View.VISIBLE else View.GONE
        h.progress.progress = r.progress.coerceIn(0, 100)
        h.trailing.text = r.trailing
        h.trailing.visibility = if (r.trailing.isEmpty()) View.GONE else View.VISIBLE
        h.check.visibility = if (r.watched) View.VISIBLE else View.GONE
        h.itemView.setOnClickListener { onClick(r) }
        h.itemView.optionsKeys { onOptions(r) }
    }
}

// ---------------- schede (Home) ----------------

data class Card(
    val title: String,
    val subtitle: String,
    val icon: Int,
    val tag: Any?,
    val progress: Int = -1
)

class CardAdapter(
    private val onClick: (Card) -> Unit,
    private val onOptions: (Card) -> Unit = {}
) : RecyclerView.Adapter<CardAdapter.VH>() {

    var cards: List<Card> = emptyList()
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView = v.findViewById(R.id.subtitle)
        val progress: ProgressBar = v.findViewById(R.id.progress)
    }

    override fun getItemCount() = cards.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
        v.focusScale(1.06f)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = cards[position]
        h.icon.setImageResource(c.icon)
        h.title.text = c.title
        h.sub.text = c.subtitle
        h.sub.visibility = if (c.subtitle.isEmpty()) View.GONE else View.VISIBLE
        h.progress.visibility = if (c.progress >= 0) View.VISIBLE else View.GONE
        h.progress.progress = c.progress.coerceIn(0, 100)
        h.itemView.setOnClickListener { onClick(c) }
        h.itemView.optionsKeys { onOptions(c) }
    }
}

// ---------------- pannello laterale del player ----------------

sealed class PanelItem {
    class Header(val text: String) : PanelItem()
    class Choice(val title: String, val subtitle: String, val checked: Boolean, val action: () -> Unit) : PanelItem()
    /** Valore regolabile con ◀ ▶ (o OK per avanzare). */
    class Value(val title: String, val value: () -> String, val change: (Int) -> Unit) : PanelItem()
}

class PanelAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var onChoiceDone: (Int) -> Unit = {}

    var items: List<PanelItem> = emptyList()
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    private class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v as TextView
    }

    private class ItemVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView = v.findViewById(R.id.subtitle)
        val value: TextView = v.findViewById(R.id.value)
        val check: ImageView = v.findViewById(R.id.check)
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = if (items[position] is PanelItem.Header) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) HeaderVH(inf.inflate(R.layout.item_panel_header, parent, false))
        else ItemVH(inf.inflate(R.layout.item_panel, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val it = items[position]) {
            is PanelItem.Header -> (holder as HeaderVH).text.text = it.text
            is PanelItem.Choice -> {
                val h = holder as ItemVH
                h.title.text = it.title
                h.sub.text = it.subtitle
                h.sub.visibility = if (it.subtitle.isEmpty()) View.GONE else View.VISIBLE
                h.value.visibility = View.GONE
                h.check.visibility = if (it.checked) View.VISIBLE else View.INVISIBLE
                h.itemView.setOnKeyListener(null)
                h.itemView.setOnClickListener { _ ->
                    val pos = h.bindingAdapterPosition
                    it.action()
                    onChoiceDone(pos)
                }
            }
            is PanelItem.Value -> {
                val h = holder as ItemVH
                h.title.text = it.title
                h.sub.visibility = View.GONE
                h.value.visibility = View.VISIBLE
                h.value.text = "‹  ${it.value()}  ›"
                h.check.visibility = View.GONE
                h.itemView.setOnClickListener { _ ->
                    it.change(1)
                    notifyItemChanged(h.bindingAdapterPosition)
                }
                h.itemView.setOnKeyListener { _, keyCode, event ->
                    val dir = when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> -1
                        KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                        else -> 0
                    }
                    if (dir == 0) false
                    else {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            it.change(dir)
                            notifyItemChanged(h.bindingAdapterPosition)
                        }
                        true
                    }
                }
            }
        }
    }
}
