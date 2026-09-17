package com.holopengin.instantjpdict

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.holopengin.instantjpdict.data.AppDatabase
import com.holopengin.instantjpdict.data.DictionaryMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The installed-dictionary manager: reorder, and remove.
 *
 * Rows are drag handles + names on a tonal card in the app's "Harbour" Material 3
 * language ([HarbourUi]); the confirmations are `MaterialAlertDialog`s. Built-in
 * dictionaries (#43) are hidden — they are app data, not user dictionaries.
 */
object DictionaryManagerDialog {

    fun show(context: Context) {
        val ui = HarbourUi.of(context)
        val db = AppDatabase.getDatabase(context)
        val lifecycleOwner = context as? LifecycleOwner

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), 0, ui.dp(24), 0)
        }
        root.addView(ui.body(
            "Drag the handles on the left to re-order the dictionaries. " +
                "Click the bin on the right to delete dictionaries."
        ))

        val empty = TextView(context).apply {
            text = "No dictionaries installed yet."
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(ui.onSurfaceVariant)
            gravity = Gravity.CENTER
            setPadding(ui.dp(12), ui.dp(24), ui.dp(12), ui.dp(24))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }
        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val card = ui.card().apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        card.addView(ui.cardBody(padH = 8, padV = 8).apply {
            // The card resolves its height from the root's weight; inside the card
            // (a FrameLayout) the body fills it and the list takes the remainder.
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(empty)
            addView(recyclerView)
        })
        root.addView(card)

        val adapter = DictionaryAdapter(ui) { dict ->
            confirmDelete(context, ui, db, lifecycleOwner, dict) { reload(context, recyclerView, empty) }
        }
        recyclerView.adapter = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val fromPos = vh.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                val list = adapter.dictionaries
                val item = list.removeAt(fromPos)
                list.add(toPos, item)
                adapter.notifyItemMoved(fromPos, toPos)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                lifecycleOwner?.lifecycleScope?.launch(Dispatchers.IO) {
                    adapter.dictionaries.forEachIndexed { index, dict ->
                        db.dictionaryDao().updatePriority(dict.id, index)
                    }
                }
            }
        })
        touchHelper.attachToRecyclerView(recyclerView)
        adapter.touchHelper = touchHelper

        reload(context, recyclerView, empty)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Dictionary Manager")
            .setView(root)
            .setPositiveButton("Close", null)
            .create()
        dialog.setOnShowListener { ui.sizeDialogWindow(dialog, heightFraction = 0.75f) }
        dialog.show()
    }

    /** Reload the list and show the empty state when there is nothing to manage. */
    private fun reload(context: Context, rv: RecyclerView, empty: View) {
        val db = AppDatabase.getDatabase(context)
        val lifecycleOwner = context as? LifecycleOwner ?: return
        lifecycleOwner.lifecycleScope.launch {
            val dicts = withContext(Dispatchers.IO) { db.dictionaryDao().getAllDictionaries() }
                // #43: built-ins are app data, not user dictionaries — nothing
                // to reorder and nothing to delete, so they are not listed.
                .filterNot { it.builtIn }
            (rv.adapter as? DictionaryAdapter)?.update(dicts)
            empty.visibility = if (dicts.isEmpty()) View.VISIBLE else View.GONE
            rv.visibility = if (dicts.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun confirmDelete(
        context: Context,
        ui: HarbourUi,
        db: AppDatabase,
        owner: LifecycleOwner?,
        dict: DictionaryMeta,
        onDone: () -> Unit,
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Remove Dictionary")
            .setMessage("Delete '${dict.name}'? All of its entries and tags will be removed.")
            .setPositiveButton("Remove") { _, _ ->
                val spinner = CircularProgressIndicator(context).apply {
                    isIndeterminate = true
                    layoutParams = LinearLayout.LayoutParams(ui.dp(48), ui.dp(48))
                        .apply { gravity = Gravity.CENTER_HORIZONTAL }
                }
                val box = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(8))
                    addView(spinner)
                    addView(TextView(context).apply {
                        text = "Removing ${dict.name}…"
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                        setTextColor(ui.onSurfaceVariant)
                        gravity = Gravity.CENTER
                        setPadding(0, ui.dp(16), 0, 0)
                    })
                }
                val progress = MaterialAlertDialogBuilder(context)
                    .setView(box)
                    .setCancelable(false)
                    .create()
                progress.show()

                owner?.lifecycleScope?.launch {
                    withContext(Dispatchers.IO) {
                        db.dictionaryDao().deleteEntriesForDictionary(dict.id)
                        db.dictionaryDao().deleteTagsForDictionary(dict.id)
                        db.dictionaryDao().deleteDictionary(dict.id)
                    }
                    progress.dismiss()
                    onDone()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class DictionaryAdapter(
        private val ui: HarbourUi,
        val dictionaries: MutableList<DictionaryMeta> = mutableListOf(),
        val onDelete: (DictionaryMeta) -> Unit,
    ) : RecyclerView.Adapter<DictionaryAdapter.ViewHolder>() {

        class Row(
            val root: View,
            val name: TextView,
            val delete: ImageButton,
            val handle: ImageButton,
        )

        class ViewHolder(val row: Row) : RecyclerView.ViewHolder(row.root)

        var touchHelper: ItemTouchHelper? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val context = parent.context
            val handle = ui.iconButton(R.drawable.ic_drag_handle, "Reorder")
            val name = TextView(context).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                setTextColor(ui.onSurface)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val delete = ui.iconButton(R.drawable.ic_delete, "Remove")
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ui.dp(56)
                setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
                addView(handle)
                addView(name)
                addView(delete)
            }
            return ViewHolder(Row(row, name, delete, handle))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val dict = dictionaries[position]
            holder.row.name.text = dict.name
            holder.row.delete.setOnClickListener { onDelete(dict) }
            holder.row.delete.contentDescription = "Remove ${dict.name}"
            holder.row.handle.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    holder.row.handle.performClick()
                    touchHelper?.startDrag(holder)
                    return@setOnTouchListener true
                }
                false
            }
        }

        override fun getItemCount() = dictionaries.size

        fun update(newList: List<DictionaryMeta>) {
            dictionaries.clear()
            dictionaries.addAll(newList)
            notifyDataSetChanged()
        }
    }
}
