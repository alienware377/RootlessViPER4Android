package me.timschneeberger.rootlessjamesdsp.utils

import timber.log.Timber
import android.content.Context
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.TextViewCompat
import android.view.Choreographer
import android.view.DragEvent
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import me.timschneeberger.rootlessjamesdsp.R

/**
 * Lets the user reorder, hide and group the effect cards on the main screen.
 *
 * The cards themselves stay declared in the layout; this class only moves the
 * card views around inside their [LinearLayout] container and persists the
 * resulting order/visibility.
 */
class EffectLayoutManager(
    private val context: Context,
    private val container: LinearLayout,
    private val items: MutableList<Item>
) {
    /**
     * @param key stable identifier used for persistence
     * @param viewId id of the card's inner container (or the header view itself)
     * @param titleRes display name shown while editing
     * @param isHeader true for group headers, which own the cards below them
     */
    data class Item(
        val key: String,
        val viewId: Int,
        val titleRes: Int,
        val isHeader: Boolean = false
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    /**
     * Cards a preset just switched on, waiting for [consumePendingUnfolds].
     * Kept out of [prefs] because that file is copied into every preset and
     * backup, and an old list coming back with one would open groups later.
     */
    private val pendingPrefs = context.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
    private val editRows = HashMap<String, View>()

    var editMode = false
        private set

    init {
        // Orders saved by earlier builds were derived from a wrong default and
        // scrambled the layout; drop them once so everyone starts clean.
        if (prefs.getInt(KEY_SCHEMA, 1) < SCHEMA_VERSION) {
            prefs.edit()
                .remove(KEY_ORDER)
                .putInt(KEY_SCHEMA, SCHEMA_VERSION)
                .apply()
        }
    }

    var onEditModeChanged: ((Boolean) -> Unit)? = null

    private var addGroupButton: Button? = null

    private fun showAddGroupButton() {
        val button = Button(context).apply {
            text = context.getString(R.string.effect_group_add)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                val input = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_TEXT
                    setPadding(dp(24), dp(16), dp(24), dp(8))
                }
                AlertDialog.Builder(context)
                    .setTitle(R.string.effect_group_add)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val name = input.text.toString().trim()
                        if (name.isNotEmpty()) addGroup(name)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        // Top of the list so it is discoverable in edit mode
        container.addView(button, 0)
        addGroupButton = button
    }

    // ---------------------------------------------------------------- helpers

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    /** The view that actually sits in the container (the card, not its content). */
    private fun viewFor(item: Item): View? {
        val inner = container.findViewById<View>(item.viewId) ?: return null
        return if (item.isHeader) inner else inner.parent as? View
    }

    private fun itemForView(view: View): Item? =
        items.firstOrNull { viewFor(it) === view }

    private fun hiddenKeys(): MutableSet<String> =
        HashSet(prefs.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet())

    /** The order the cards currently sit in inside the container. */
    private fun containerOrder(): List<String> {
        // One lookup per item rather than one per item per child: this now
        // also runs whenever a group is folded or the search is cleared.
        val byView = java.util.IdentityHashMap<View, Item>()
        items.forEach { item -> viewFor(item)?.let { byView[it] = item } }
        val keys = ArrayList<String>()
        for (i in 0 until container.childCount) {
            byView[container.getChildAt(i)]?.let { keys.add(it.key) }
        }
        return keys
    }

    private fun headerKeys(): Set<String> =
        items.filter { it.isHeader }.mapTo(HashSet()) { it.key }

    /**
     * Returns the user's saved order, or null if they've never customised the
     * layout - in which case the layout is left exactly as declared.
     */
    private fun storedOrder(): List<String>? {
        val saved = prefs.getString(KEY_ORDER, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?: return null

        val natural = containerOrder()
        val result = ArrayList(saved.filter { natural.contains(it) })
        // Effects added by an app update aren't in the saved order yet; slot
        // them in at their declared position instead of dumping them at the end.
        natural.forEachIndexed { index, key ->
            if (!result.contains(key)) {
                result.add(index.coerceAtMost(result.size), key)
            }
        }
        return result
    }

    // ----------------------------------------------------------------- groups

    /** Custom group headers the user created, in the form "key|name;key|name". */
    private fun storedGroups(): MutableList<Pair<String, String>> {
        val raw = prefs.getString(KEY_GROUPS, "") ?: ""
        val list = ArrayList<Pair<String, String>>()
        raw.split(";").filter { it.isNotBlank() }.forEach { entry ->
            val parts = entry.split("|", limit = 2)
            if (parts.size == 2) list.add(parts[0] to parts[1])
        }
        return list
    }

    private fun saveGroups(list: List<Pair<String, String>>) {
        prefs.edit()
            .putString(KEY_GROUPS, list.joinToString(";") { "${it.first}|${it.second}" })
            .apply()
    }

    /**
     * Display name for a header, honouring any rename the user made.
     *
     * Custom groups have no title resource - titleRes is 0 and the name lives
     * in prefs - so the resource lookup is guarded. getString(0) throws
     * Resources.NotFoundException, and a group can genuinely lose its stored
     * name underneath us when a preset replaces the layout file.
     */
    fun groupName(item: Item): String =
        prefs.getString(KEY_NAME_PREFIX + item.key, null)
            ?: storedGroups().firstOrNull { it.first == item.key }?.second
            ?: item.titleRes.takeIf { it != 0 }?.let(context::getString)
            ?: viewFor(item)?.let(::headerTitle)?.text?.toString()
            ?: item.key

    /** Hides or shows every group heading (used by V4A-only mode). */
    fun setHeadersVisible(visible: Boolean) {
        items.filter { it.isHeader }.forEach { item ->
            container.findViewById<View>(item.viewId)?.isVisible = visible
        }
    }

    /**
     * A group heading: its name in the middle and a fold chevron on the right,
     * with a blank of the same width on the left so the name stays centred.
     * Built to match the ViPER4Android heading declared in fragment_dsp.xml.
     */
    private fun makeHeaderView(text: String): LinearLayout =
        LinearLayout(context).apply {
            id = View.generateViewId()
            tag = TAG_HEADER
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, dp(8), 0, 0)
            addView(View(context), LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(TextView(context).apply {
                tag = TAG_HEADER_TITLE
                gravity = Gravity.CENTER
                this.text = text
                TextViewCompat.setTextAppearance(
                    this, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
                )
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageButton(context).apply {
                tag = TAG_HEADER_FOLD
                val ripple = TypedValue()
                context.theme.resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, ripple, true
                )
                setBackgroundResource(ripple.resourceId)
                setImageResource(R.drawable.ic_baseline_keyboard_arrow_up_24dp)
                isVisible = false
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }

    /** The name part of a heading. */
    private fun headerTitle(view: View): TextView? =
        view as? TextView ?: view.findViewWithTag(TAG_HEADER_TITLE)

    private fun headerChevron(view: View): ImageButton? =
        view.findViewWithTag(TAG_HEADER_FOLD)

    /** Creates views for any custom groups that don't have one yet. */
    private fun ensureGroupViews() {
        storedGroups().forEach { (key, name) ->
            if (items.any { it.key == key }) return@forEach
            val view = makeHeaderView(name)
            container.addView(view)
            items.add(Item(key, view.id, 0, isHeader = true))
        }
        // Refresh titles (renames) on every apply
        items.filter { it.isHeader }.forEach { item ->
            viewFor(item)?.let(::headerTitle)?.text = groupName(item)
        }
    }

    /** The cards that belong to a header: everything until the next header. */
    private fun membersOf(headerKey: String, order: List<String> = containerOrder()): List<Item> =
        GroupFolds.membersOf(order, headerKeys(), headerKey)
            .mapNotNull { key -> items.firstOrNull { it.key == key } }

    // ---------------------------------------------------------------- folding

    /**
     * Whether a heading's fold may hide anything right now. Supplied by the
     * screen, which knows when a heading is not on show (V4A-only mode hides
     * every heading, the classic layout drops the ViPER4Android one). A fold
     * under a heading nobody can see would leave its cards unreachable, so it
     * is ignored, not cleared, and comes back when the heading does.
     */
    var canFold: (Item) -> Boolean = { true }

    /** Called after a fold changes what is on screen, so the screen can re-apply its own rules. */
    var onFoldChanged: (() -> Unit)? = null

    private fun foldedKeys(): Set<String> =
        HashSet(prefs.getStringSet(KEY_FOLDED, emptySet()) ?: emptySet())

    fun isFolded(key: String) = foldedKeys().contains(key)

    /**
     * Cards that are out of sight because their group is folded.
     *
     * Empty in the layout editor, which always shows every card so any of
     * them can still be moved.
     */
    fun foldedAwayKeys(): Set<String> {
        if (editMode) return emptySet()
        val folded = foldedKeys()
        if (folded.isEmpty()) return emptySet()
        val foldable = items.filter { it.isHeader && canFold(it) }.mapTo(HashSet()) { it.key }
        return GroupFolds.foldedAway(containerOrder(), headerKeys(), folded) { it in foldable }
    }

    fun setFolded(item: Item, folded: Boolean) {
        if (!item.isHeader || isFolded(item.key) == folded) return
        val next = if (folded) foldedKeys() + item.key else foldedKeys() - item.key
        prefs.edit().putStringSet(KEY_FOLDED, HashSet(next)).apply()
        applyVisibility()
        onFoldChanged?.invoke()
    }

    /**
     * Opens any folded group holding a card a preset just switched on. The
     * preset leaves the list of those cards behind, because only this class
     * knows which group a card is in when the order was never customised.
     */
    private fun consumePendingUnfolds() {
        val pending = pendingPrefs.getStringSet(KEY_UNFOLD_PENDING, null) ?: return
        pendingPrefs.edit().remove(KEY_UNFOLD_PENDING).apply()
        val folded = foldedKeys()
        val opened = GroupFolds.unfoldFor(containerOrder(), headerKeys(), folded, pending)
        if (opened != folded) {
            Timber.d("Preset opened folded groups: ${folded - opened}")
            prefs.edit().putStringSet(KEY_FOLDED, HashSet(opened)).apply()
        }
    }

    /** Points the chevron the right way and wires it for the current mode. */
    private fun refreshHeader(item: Item, order: List<String>? = null) {
        val view = viewFor(item) ?: return
        val chevron = headerChevron(view) ?: return
        val folded = isFolded(item.key)
        // Nothing to fold in an empty group, unless it was folded before it
        // emptied, in which case the chevron is the way to open it again.
        val hasCards = membersOf(item.key, order ?: containerOrder()).isNotEmpty()
        chevron.isVisible = canFold(item) && (hasCards || folded)
        chevron.setImageResource(
            if (folded) R.drawable.ic_baseline_keyboard_arrow_down_24dp
            else R.drawable.ic_baseline_keyboard_arrow_up_24dp
        )
        chevron.contentDescription = context.getString(
            if (folded) R.string.effect_group_unfold else R.string.effect_group_fold
        )
        if (editMode) {
            // The editor shows every card so they can all be moved; the
            // chevron only shows how the group will look afterwards. Not
            // clickable, so a long press on it still starts dragging the group.
            chevron.setOnClickListener(null)
            chevron.isClickable = false
            chevron.isFocusable = false
            chevron.alpha = 0.5f
        } else {
            chevron.setOnClickListener { setFolded(item, !isFolded(item.key)) }
            chevron.isFocusable = true
            chevron.alpha = 1f
        }
    }

    private fun refreshHeaders(order: List<String> = containerOrder()) {
        items.filter { it.isHeader }.forEach { refreshHeader(it, order) }
    }

    fun addGroup(name: String) {
        val list = storedGroups()
        val key = "group_custom_" + System.currentTimeMillis()
        list.add(key to name)
        saveGroups(list)
        val view = makeHeaderView(name)
        container.addView(view)
        items.add(Item(key, view.id, 0, isHeader = true))
        saveOrderFromContainer()
        if (editMode) {
            collapseCard(view, items.last(), false)
            view.setOnLongClickListener { beginDrag(view); true }
        }
    }

    fun renameGroup(item: Item, name: String) {
        prefs.edit().putString(KEY_NAME_PREFIX + item.key, name).apply()
        val list = storedGroups()
        val index = list.indexOfFirst { it.first == item.key }
        if (index >= 0) {
            list[index] = item.key to name
            saveGroups(list)
        }
    }

    fun deleteGroup(item: Item) {
        // Only user-created groups can be removed; cards fall into the group above.
        if (!item.key.startsWith("group_custom_")) return
        saveGroups(storedGroups().filterNot { it.first == item.key })
        // Its fold goes with it. Keys are never reused, so a stale entry would
        // do no harm, but it would sit in every preset and backup for good.
        prefs.edit()
            .remove(KEY_NAME_PREFIX + item.key)
            .putStringSet(KEY_FOLDED, HashSet(GroupFolds.forget(foldedKeys(), item.key)))
            .apply()
        viewFor(item)?.let { container.removeView(it) }
        items.removeAll { it.key == item.key }
        saveOrderFromContainer()
    }

    private fun promptRename(item: Item) {
        val input = EditText(context).apply {
            setText(groupName(item))
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        val builder = AlertDialog.Builder(context)
            .setTitle(R.string.effect_group_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    renameGroup(item, name)
                    viewFor(item)?.let(::headerTitle)?.text = name
                    editRows[item.key]?.let { row ->
                        ((row as LinearLayout).getChildAt(1) as? TextView)?.text = name
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (item.key.startsWith("group_custom_")) {
            builder.setNeutralButton(R.string.effect_group_delete) { _, _ -> deleteGroup(item) }
        }
        builder.show()
    }

    // ------------------------------------------------------------------ apply

    /** Applies the saved order and visibility to the container. */
    fun applyLayout() {
        // Re-ordering the views under a card that is being dragged would throw
        // it back to where it started; the drop puts the new order in place
        // and then this runs.
        if (dragging != null) {
            applyAfterDrag = true
            return
        }
        ensureGroupViews()
        // Null for an untouched layout, which keeps the declared order as-is
        storedOrder()?.let(::reorder)
        consumePendingUnfolds()
        applyVisibility()
    }

    private fun reorder(order: List<String>) {
        val ordered = order.mapNotNull { key ->
            items.firstOrNull { it.key == key }?.let { item -> viewFor(item) }
        }
        if (ordered.isEmpty()) return

        val startIndex = ordered
            .map { container.indexOfChild(it) }
            .filter { it >= 0 }
            .minOrNull() ?: return

        // Detach from whatever currently holds each view, not just from the
        // root container, and do it again immediately before each add rather
        // than only in one pass up front - an add earlier in the loop can
        // re-parent a view that `ordered` names twice. Together this makes a
        // repeated applyLayout harmless.
        ordered.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        var index = startIndex
        ordered.forEach { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            container.addView(view, index.coerceAtMost(container.childCount))
            index++
        }
    }

    private fun applyVisibility() {
        val order = containerOrder()
        val hidden = HashSet(hiddenKeys())
        // A hidden group takes its cards with it
        items.filter { it.isHeader && hidden.contains(it.key) }.forEach { header ->
            membersOf(header.key, order).forEach { hidden.add(it.key) }
        }
        // And a folded one puts them out of sight while its heading stays.
        // This only ever adds to the hidden cards, so a card the user hid
        // stays hidden when its group is opened again.
        hidden.addAll(foldedAwayKeys())
        items.forEach { item ->
            viewFor(item)?.isVisible = editMode || !hidden.contains(item.key)
        }
        refreshHeaders(order)
    }

    private fun saveOrderFromContainer() {
        val keys = ArrayList<String>()
        for (i in 0 until container.childCount) {
            itemForView(container.getChildAt(i))?.let { keys.add(it.key) }
        }
        // Refuse to persist a partial snapshot. Cards are inflated a few at a
        // time so the first frame is not blocked, so for the first moments the
        // container holds only some of them - and this used to write exactly
        // what it saw. Every card missing from that write lost its place and
        // came back wherever it happened to be inflated next time, which is a
        // customised order quietly decaying over successive launches. Only
        // "there is at least one" was checked before, which does not catch it.
        val placeable = items.count { viewFor(it) != null }
        if (keys.isEmpty() || keys.size < placeable) {
            Timber.w("Not saving card order: have ${keys.size} of $placeable cards")
            return
        }
        prefs.edit().putString(KEY_ORDER, keys.joinToString(",")).apply()
    }

    // -------------------------------------------------------------- edit mode

    fun toggleEditMode() = if (editMode) exitEditMode() else enterEditMode()

    fun enterEditMode() {
        if (editMode) return
        editMode = true
        applyVisibility()

        val hidden = hiddenKeys()
        items.forEach { item ->
            val view = viewFor(item) ?: return@forEach
            collapseCard(view, item, hidden.contains(item.key))
            view.setOnLongClickListener {
                beginDrag(view)
                true
            }
        }

        container.setOnDragListener(dragListener)
        showAddGroupButton()
        onEditModeChanged?.invoke(true)
    }

    /**
     * Re-read the layout from disk and re-apply it.
     *
     * Loading a preset replaces effect_layout.xml underneath a running app, but
     * [prefs] is the copy this process loaded at startup and keeps serving -
     * and the next drag or eye-toggle would write that stale copy straight back
     * over what the preset restored. Re-entering getSharedPreferences with
     * MODE_MULTI_PROCESS is what makes the platform notice the file changed;
     * the mode on the cached instance alone does nothing.
     *
     * Deliberately not a computed property: isHidden() is called once per card
     * on every keystroke of the effect search, and that would put a stat() per
     * card per character on the UI thread.
     */
    fun reload() {
        @Suppress("DEPRECATION")
        context.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS)
        val live = storedGroups().map { it.first }.toSet()
        items.filter { it.isHeader && it.titleRes == 0 && it.key !in live }
            .forEach { stale ->
                viewFor(stale)?.let { container.removeView(it) }
                items.removeAll { it.key == stale.key }
            }
        applyLayout()
    }

    fun exitEditMode() {
        if (!editMode) return
        editMode = false
        // Can happen mid-drag (a preset loading). The drag listener is about
        // to go, so its end event would never arrive to stop the scrolling.
        stopEdgeScroll()
        applyAfterDrag = false

        items.forEach { item ->
            val view = viewFor(item) ?: return@forEach
            view.scaleX = 1f
            view.scaleY = 1f
            view.alpha = 1f
            view.setOnLongClickListener(null)
            view.isLongClickable = false
            expandCard(view, item)
        }

        container.setOnDragListener(null)
        addGroupButton?.let { container.removeView(it) }
        addGroupButton = null
        saveOrderFromContainer()
        applyVisibility()
        onEditModeChanged?.invoke(false)
    }

    /** Replaces the card's content with a compact one-line editing row. */
    private fun collapseCard(view: View, item: Item, isHidden: Boolean) {
        if (item.isHeader) {
            // Headers aren't containers - show the editing affordances inline.
            // The tap goes on the whole heading rather than the name inside
            // it, so a long press anywhere on it still reaches the drag.
            headerTitle(view)?.text =
                context.getString(R.string.effect_group_header_editing, groupName(item))
            view.setOnClickListener { promptRename(item) }
            view.alpha = if (isHidden) 0.45f else 1f
            refreshHeader(item)
            return
        }

        val group = view as? ViewGroup ?: return

        if (!item.isHeader) {
            container.findViewById<View>(item.viewId)?.isVisible = false
        } else {
            (view as? TextView)?.alpha = 0.4f
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(4), dp(10))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_twotone_drag_handle_24dp)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        })

        row.addView(TextView(context).apply {
            text = context.getString(item.titleRes)
            setPadding(dp(12), 0, dp(8), 0)
            maxLines = 1
            alpha = if (isHidden) 0.45f else 1f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        val eye = ImageButton(context).apply {
            setImageResource(
                if (isHidden) R.drawable.ic_twotone_visibility_off_24dp
                else R.drawable.ic_twotone_visibility_24dp
            )
            background = null
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        eye.setOnClickListener {
            val hidden = hiddenKeys()
            val nowHidden = !hidden.contains(item.key)
            if (nowHidden) hidden.add(item.key) else hidden.remove(item.key)
            prefs.edit().putStringSet(KEY_HIDDEN, hidden).apply()
            eye.setImageResource(
                if (nowHidden) R.drawable.ic_twotone_visibility_off_24dp
                else R.drawable.ic_twotone_visibility_24dp
            )
            (row.getChildAt(1) as? TextView)?.alpha = if (nowHidden) 0.45f else 1f
        }
        if (item.isHeader) {
            val rename = ImageButton(context).apply {
                setImageResource(R.drawable.ic_twotone_edit_24dp)
                background = null
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            }
            rename.setOnClickListener { promptRename(item) }
            row.addView(rename)
            if (item.key.startsWith("group_custom_")) {
                val delete = ImageButton(context).apply {
                    setImageResource(R.drawable.ic_twotone_delete_24dp)
                    background = null
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                    setOnClickListener {
                        AlertDialog.Builder(context)
                            .setTitle(R.string.effect_group_delete)
                            .setMessage(groupName(item))
                            .setPositiveButton(android.R.string.ok) { _, _ -> deleteGroup(item) }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
                row.addView(delete)
            }
        }
        row.addView(eye)

        group.addView(row)
        editRows[item.key] = row
    }

    private fun expandCard(view: View, item: Item) {
        editRows.remove(item.key)?.let { (view as? ViewGroup)?.removeView(it) }
        if (!item.isHeader) {
            container.findViewById<View>(item.viewId)?.isVisible = true
        } else {
            view.alpha = 1f
            headerTitle(view)?.text = groupName(item)
            view.setOnClickListener(null)
            view.isClickable = false
            refreshHeader(item)
        }
    }

    private fun beginDrag(view: View) {
        view.alpha = 0.65f
        view.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).start()
        if (!ViewCompat.startDragAndDrop(view, null, View.DragShadowBuilder(view), view, 0)) {
            // The system refused the drag, so no end event will ever come to
            // put the card back to normal.
            view.alpha = 1f
            view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
    }

    private val dragListener = View.OnDragListener { _, event ->
        val dragged = event.localState as? View ?: return@OnDragListener false
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                startEdgeScroll(dragged)
                true
            }
            DragEvent.ACTION_DRAG_LOCATION -> {
                val offset = containerOffset()
                if (offset != null && scroller != null) {
                    // Kept in screen terms rather than list terms, because the
                    // list moves under a finger that is held still.
                    fingerY = event.y + offset - scroller.scrollY
                }
                moveDraggedTo(event.y, dragged)
                updateEdgeScroll()
                true
            }
            // Nothing new on purpose for ACTION_DRAG_EXITED: the finger went
            // off the list, usually past the edge it was scrolling towards, so
            // the last position is kept and the list carries on at that speed
            // until the finger comes back or lets go.
            DragEvent.ACTION_DROP, DragEvent.ACTION_DRAG_ENDED -> {
                stopEdgeScroll()
                dragged.alpha = 1f
                dragged.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                saveOrderFromContainer()
                if (applyAfterDrag) {
                    applyAfterDrag = false
                    applyLayout()
                }
                true
            }
            else -> true
        }
    }

    /** Moves the dragged card to the slot under [y], in container coordinates. */
    private fun moveDraggedTo(y: Float, dragged: View) {
        val target = movableChildUnder(y, dragged) ?: return
        val to = container.indexOfChild(target)
        val from = container.indexOfChild(dragged)
        if (to < 0 || from < 0 || to == from) return
        // Only once the finger is past the middle of the card it is moving
        // over. Swapping on the first pixel made a tall card and a short one
        // trade places back and forth, because after the swap the finger was
        // still over the card that had just moved; with the list scrolling on
        // its own every frame that would have turned into a flicker.
        val middle = target.top + target.height / 2f
        if (to > from && y < middle) return
        if (to < from && y > middle) return
        container.removeView(dragged)
        container.addView(dragged, to)
    }

    private fun movableChildUnder(y: Float, exclude: View): View? {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child === exclude || itemForView(child) == null) continue
            // A hidden card keeps the position it had when last laid out.
            if (child.visibility != View.VISIBLE) continue
            if (y >= child.top && y <= child.bottom) return child
        }
        return null
    }

    // ------------------------------------------------- scrolling while dragging

    /**
     * The scroll view the cards sit in.
     *
     * The system drag does not scroll anything by itself, so without this a
     * card could never be carried past what fits on the screen.
     */
    private val scroller: ViewGroup? = run {
        var parent = container.parent
        while (parent != null) {
            if (parent is NestedScrollView || parent is ScrollView) return@run parent as ViewGroup
            parent = parent.parent
        }
        null
    }

    /**
     * How much of the bottom of the scroll view is covered by something pinned
     * over it, such as the bottom bar and the power button. Supplied by the
     * screen that owns them; without it the bottom edge is the scroll view's.
     */
    var bottomCover: (() -> Int)? = null

    /** Last finger position, relative to the top of the scroll view. */
    private var fingerY = Float.NaN
    private var dragging: View? = null
    private var usableTop = 0f
    private var usableBottom = 0f
    private var zone = 0f
    private var maxSpeed = 0f
    private var lastFrameNanos = 0L
    private var scrollCarry = 0f
    private var frameQueued = false

    /** Set when the layout was asked to re-apply mid-drag; done on drop instead. */
    private var applyAfterDrag = false

    private val frameCallback = Choreographer.FrameCallback { now -> onScrollFrame(now) }

    /** The container's top, measured inside the scroll view's content. */
    private fun containerOffset(): Int? {
        val root = scroller ?: return null
        var offset = 0
        var view: View = container
        while (view !== root) {
            offset += view.top
            view = view.parent as? View ?: return null
        }
        return offset
    }

    private fun startEdgeScroll(dragged: View) {
        stopEdgeScroll()
        dragging = dragged
        val root = scroller ?: return
        // Measured once per drag. The covered part changes only with the
        // window, not while a finger is down.
        val cover = (bottomCover?.invoke() ?: 0).coerceIn(0, root.height)
        usableTop = root.paddingTop.toFloat()
        usableBottom = (root.height - cover).toFloat()
        val density = context.resources.displayMetrics.density
        zone = EdgeAutoScroll.zoneSize(usableBottom - usableTop, MIN_EDGE_ZONE_DP * density)
        maxSpeed = MAX_SCROLL_DP_PER_SECOND * density
    }

    private fun currentVelocity(): Float =
        EdgeAutoScroll.velocity(fingerY, usableTop, usableBottom, zone, maxSpeed)

    private fun updateEdgeScroll() {
        val root = scroller ?: return
        if (dragging == null) return
        val velocity = currentVelocity()
        if (velocity == 0f || !root.canScrollVertically(if (velocity < 0) -1 else 1)) {
            haltFrames()
            return
        }
        if (!frameQueued) {
            frameQueued = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun onScrollFrame(now: Long) {
        frameQueued = false
        val root = scroller ?: return
        val dragged = dragging ?: return
        // The screen can go away mid-drag (the activity being recreated, for
        // one) without the end of the drag ever reaching this list, and the
        // loop would go on scrolling a list nobody can see.
        if (!root.isAttachedToWindow) {
            stopEdgeScroll()
            return
        }
        val velocity = currentVelocity()
        val direction = if (velocity < 0) -1 else 1
        if (velocity == 0f || !root.canScrollVertically(direction)) {
            haltFrames()
            return
        }
        // Time based, so a 120 Hz screen does not scroll twice as fast. The
        // first frame and any long stall count as one ordinary frame.
        val seconds = if (lastFrameNanos == 0L) 1f / 60f
            else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 1f / 20f)
        lastFrameNanos = now
        scrollCarry += velocity * seconds
        val step = scrollCarry.toInt()
        scrollCarry -= step
        if (step != 0) root.scrollBy(0, step)

        // The finger has not moved but the cards under it have, so work out
        // the drop slot again from where the finger now is in the list.
        val offset = containerOffset()
        if (offset != null) moveDraggedTo(fingerY - offset + root.scrollY, dragged)

        frameQueued = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /**
     * Stops the scrolling while the screen is paused mid-drag. The drag goes
     * on, and the next move of the finger starts the scrolling again.
     */
    fun pauseEdgeScroll() = haltFrames()

    /** Stops the frame loop but keeps the drag going. */
    private fun haltFrames() {
        if (frameQueued) Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameQueued = false
        lastFrameNanos = 0L
        scrollCarry = 0f
    }

    private fun stopEdgeScroll() {
        haltFrames()
        dragging = null
        fingerY = Float.NaN
    }

    /** True when the card is currently hidden by the user. */
    fun isHidden(key: String) = hiddenKeys().contains(key)

    fun resetLayout() {
        prefs.edit().remove(KEY_ORDER).remove(KEY_HIDDEN).remove(KEY_FOLDED).apply()
        applyLayout()
    }

    companion object {
        private const val TAG_HEADER = "effect_group_header"
        // Also set on the ViPER4Android heading in fragment_dsp.xml
        private const val TAG_HEADER_TITLE = "effect_group_header_title"
        private const val TAG_HEADER_FOLD = "effect_group_header_fold"

        private const val PREFS = "effect_layout"
        private const val KEY_ORDER = "order"
        private const val KEY_HIDDEN = "hidden"
        private const val KEY_GROUPS = "groups"
        private const val KEY_NAME_PREFIX = "name_"
        private const val KEY_FOLDED = "folded"
        // Cards a preset just switched on, left by Preset.load for
        // consumePendingUnfolds in a file of their own. Both names are
        // repeated in Preset.
        private const val PENDING_PREFS = "effect_layout_pending"
        private const val KEY_UNFOLD_PENDING = "unfold_pending"
        private const val KEY_SCHEMA = "schema"
        private const val SCHEMA_VERSION = 2

        /** Smallest edge zone that drags the list along, roughly a fingertip. */
        private const val MIN_EDGE_ZONE_DP = 64f
        /** Top speed with the finger right at the edge. */
        private const val MAX_SCROLL_DP_PER_SECOND = 1400f
    }
}
