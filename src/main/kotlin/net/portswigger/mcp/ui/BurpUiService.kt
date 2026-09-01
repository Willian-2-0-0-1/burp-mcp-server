package net.portswigger.mcp.ui

import burp.api.montoya.MontoyaApi
import java.awt.Point
import java.awt.Robot
import java.awt.event.InputEvent
import javax.swing.JTabbedPane
import java.awt.Component
import java.awt.Container
import javax.swing.SwingUtilities

/** Small, defensive UI bridge for Burp tabs that are not exposed by Montoya. */
object BurpUiService {
    fun listRepeaterTabs(api: MontoyaApi): String = onEdt {
        val pane = findRepeaterRequestPane(api) ?: return@onEdt "No Repeater tab pane found"
        (0 until pane.tabCount).joinToString("\n") { index ->
            "${index + 1}: ${pane.getTitleAt(index)}"
        }.ifBlank { "No Repeater tabs found" }
    }

    fun deleteRepeaterTab(api: MontoyaApi, tabName: String?, tabIndex: Int?): String {
        require((tabName != null) xor (tabIndex != null)) {
            "Provide exactly one of tabName or tabIndex"
        }
        require(tabIndex == null || tabIndex > 0) { "tabIndex is 1-based and must be greater than zero" }

        val pane = findRepeaterRequestPane(api) ?: error("No Repeater tab pane found")
        val index = onEdt {
            when {
                tabName != null -> (0 until pane.tabCount).firstOrNull {
                    pane.getTitleAt(it).equals(tabName.trim(), ignoreCase = true)
                } ?: error("Repeater tab not found: ${tabName.trim()}")
                else -> tabIndex!! - 1
            }
        }
        require(index in 0 until onEdt { pane.tabCount }) {
            "Repeater tab index out of range: $index"
        }
        val title = onEdt { pane.getTitleAt(index) }
        if (!closeTabAt(pane, index)) error("Burp did not close Repeater tab: $title")
        return "Closed Repeater tab: $title"
    }

    fun deleteAllRepeaterTabs(api: MontoyaApi, titlePrefix: String?): String {
        val pane = findRepeaterRequestPane(api) ?: return "No Repeater tab pane found"
        val closed = mutableListOf<String>()
        var guard = 0
        while (guard++ < 500) {
            val index = onEdt {
                (0 until pane.tabCount).firstOrNull {
                    val t = pane.getTitleAt(it).trim()
                    t.lowercase() !in NON_REQUEST_TITLES &&
                        (titlePrefix.isNullOrBlank() || t.startsWith(titlePrefix, ignoreCase = true))
                }
            } ?: break
            val title = onEdt { pane.getTitleAt(index) }
            if (!closeTabAt(pane, index)) {
                return "Stopped: Burp did not close tab '$title'. Closed ${closed.size} before it."
            }
            closed += title
        }
        if (closed.isEmpty()) return "No matching Repeater tabs to close."
        return "Closed ${closed.size} Repeater tabs: ${closed.take(12).joinToString(", ")}" +
            if (closed.size > 12) " ... (+${closed.size - 12})" else ""
    }

    /** Closes one tab. Prefers direct Swing removal (works headless / without macOS
     *  Accessibility permission); falls back to synthesising a click on the tab's close button. */
    private fun closeTabAt(pane: JTabbedPane, index: Int): Boolean {
        val title = onEdt { if (index in 0 until pane.tabCount) pane.getTitleAt(index) else null }
            ?: return false

        onEdt {
            runCatching {
                val comp = pane.getComponentAt(index)
                // Burp wires close actions on the tab header component when present.
                val header = pane.getTabComponentAt(index)
                if (header is Container) {
                    val button = allButtons(header).firstOrNull()
                    if (button != null) { button.doClick(); return@runCatching }
                }
                pane.remove(index)
                if (comp != null) pane.remove(comp)
            }
            pane.revalidate()
            pane.repaint()
        }
        Thread.sleep(120)
        val gone = onEdt { (0 until pane.tabCount).none { pane.getTitleAt(it) == title } }
        if (gone) return true

        val point = onEdt {
            val bounds = pane.getBoundsAt(index) ?: return@onEdt null
            val screen = pane.locationOnScreen
            Point(
                screen.x + bounds.x + (bounds.width - 12).coerceAtLeast(4),
                screen.y + bounds.y + bounds.height / 2,
            )
        } ?: return false
        runCatching {
            Robot().apply {
                autoDelay = 40
                mouseMove(point.x, point.y)
                mousePress(InputEvent.BUTTON1_DOWN_MASK)
                mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            }
        }
        Thread.sleep(200)
        return onEdt { (0 until pane.tabCount).none { pane.getTitleAt(it) == title } }
    }

    private fun allButtons(c: Container): List<javax.swing.AbstractButton> {
        val out = mutableListOf<javax.swing.AbstractButton>()
        if (c is javax.swing.AbstractButton) out += c
        c.components.forEach { if (it is Container) out += allButtons(it) }
        return out
    }

    /** Selects a Repeater tab and activates its Send control, then waits for the response.
     *  Burp's Send is not an AbstractButton, so we dispatch Swing mouse events in-process
     *  (no Robot, no macOS Accessibility permission needed). */
    fun sendRepeaterTab(api: MontoyaApi, tabName: String?, tabIndex: Int?, waitMs: Int): String {
        require((tabName != null) xor (tabIndex != null)) {
            "Provide exactly one of tabName or tabIndex"
        }
        val pane = findRepeaterRequestPane(api) ?: error("No Repeater tab pane found")
        val index = onEdt {
            when {
                tabName != null -> (0 until pane.tabCount).firstOrNull {
                    pane.getTitleAt(it).equals(tabName.trim(), ignoreCase = true)
                } ?: error("Repeater tab not found: ${tabName.trim()}")
                else -> tabIndex!! - 1
            }
        }
        require(index in 0 until onEdt { pane.tabCount }) { "Repeater tab index out of range" }

        val title = onEdt {
            pane.selectedIndex = index
            pane.revalidate()
            pane.repaint()
            pane.getTitleAt(index)
        }
        Thread.sleep(150)

        val ok = onEdt {
            var root: Component = pane
            repeat(6) { root = root.parent ?: root }
            val label = textOf(root, "Send") ?: return@onEdt false
            if (label is javax.swing.AbstractButton) {
                if (!label.isEnabled) return@onEdt false
                label.doClick(); return@onEdt true
            }
            // Burp renders Send as a JLabel with no listeners; the clickable container is
            // an ancestor. Walk up to the first ancestor that actually has MouseListeners
            // and invoke them directly - dispatchEvent on the label goes nowhere.
            var target: Component? = label.parent
            var hops = 0
            while (target != null && hops++ < 4 && target.mouseListeners.isEmpty()) {
                target = target.parent
            }
            val host = target ?: return@onEdt false
            val listeners = host.mouseListeners.filter { it !is javax.swing.ToolTipManager }
            if (listeners.isEmpty()) return@onEdt false
            val x = host.width.coerceAtLeast(2) / 2
            val y = host.height.coerceAtLeast(2) / 2
            val now = System.currentTimeMillis()
            fun ev(id: Int) = java.awt.event.MouseEvent(
                host, id, now, InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false,
                java.awt.event.MouseEvent.BUTTON1
            )
            listeners.forEach { l ->
                runCatching {
                    l.mouseEntered(ev(java.awt.event.MouseEvent.MOUSE_ENTERED))
                    l.mousePressed(ev(java.awt.event.MouseEvent.MOUSE_PRESSED))
                    l.mouseReleased(ev(java.awt.event.MouseEvent.MOUSE_RELEASED))
                    l.mouseClicked(ev(java.awt.event.MouseEvent.MOUSE_CLICKED))
                }
            }
            true
        }
        if (!ok) error("Send control not found or disabled for Repeater tab: $title")
        Thread.sleep(waitMs.coerceIn(100, 30000).toLong())
        // Burp's Send is a custom component that ignores synthesised Swing mouse events.
        // Verify the status label instead of assuming success, so callers are never told
        // a request was issued when it was not.
        val status = onEdt {
            var root: Component = pane
            repeat(6) { root = root.parent ?: root }
            (namedComponent(root, "repeaterStatusLabel") as? javax.swing.JLabel)?.text?.trim()
        }
        return if (status.isNullOrBlank() || status.equals("Ready", ignoreCase = true)) {
            "NOT SENT: dispatched activation to the Send control but Burp did not issue the " +
                "request (status='$status'). Press Send manually, or use the Proxy history instead."
        } else {
            "Sent Repeater tab: $title (status=$status)"
        }
    }

    /** Debug: full introspection of the Repeater Send control. */
    fun inspectSendControl(api: MontoyaApi): String = onEdt {
        val pane = findRepeaterRequestPane(api) ?: return@onEdt "No Repeater pane"
        var root: Component = pane
        repeat(6) { root = root.parent ?: root }
        val c = textOf(root, "Send") ?: return@onEdt "Send control not found"
        val sb = StringBuilder()
        sb.append("class=").append(c.javaClass.name).append("\n")
        var k: Class<*>? = c.javaClass
        sb.append("hierarchy=")
        while (k != null && k != Any::class.java) { sb.append(k.simpleName).append(" < "); k = k.superclass }
        sb.append("Object\n")
        sb.append("interfaces=").append(c.javaClass.interfaces.joinToString { it.simpleName }).append("\n")
        sb.append("enabled=").append(c.isEnabled).append(" showing=").append(c.isShowing)
          .append(" size=").append(c.width).append("x").append(c.height).append("\n")
        sb.append("mouseListeners=").append(c.mouseListeners.joinToString { it.javaClass.name }).append("\n")
        if (c is javax.swing.AbstractButton) {
            sb.append("actionListeners=").append(c.actionListeners.joinToString { it.javaClass.name }).append("\n")
            sb.append("action=").append(c.action?.javaClass?.name ?: "-").append("\n")
        }
        if (c is javax.swing.JComponent) {
            val im = c.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
            sb.append("inputMapKeys=").append(im?.allKeys()?.joinToString { it.toString() } ?: "-").append("\n")
            sb.append("actionMapKeys=").append(c.actionMap?.allKeys()?.joinToString { it.toString() } ?: "-").append("\n")
        }
        sb.append("methods=").append(
            c.javaClass.declaredMethods.filter { it.parameterCount == 0 }
                .joinToString { it.name }.take(600)
        ).append("\n")
        sb.append("parentClass=").append(c.parent?.javaClass?.name ?: "-").append("\n")
        sb.append("parentMouseListeners=")
          .append(c.parent?.mouseListeners?.joinToString { it.javaClass.name } ?: "-")
        sb.toString()
    }

    /** Scrolls the Repeater response viewer so the body (JSON) is visible.
     *  fraction 0.0 = top, 1.0 = bottom. Picks scroll panes on the right-hand
     *  (response) half of the Repeater split. */
    fun scrollRepeaterResponse(api: MontoyaApi, fraction: Double, requestSide: Boolean): String = onEdt {
        val pane = findRepeaterRequestPane(api) ?: return@onEdt "No Repeater pane"
        var root: Component = pane
        repeat(6) { root = root.parent ?: root }
        // Collect every showing scroll pane under the Repeater view. Side detection by
        // screen position proved unreliable across layouts, so selection is driven by
        // which pane actually has content to scroll (below), not by geometry.
        val panes = mutableListOf<javax.swing.JScrollPane>()
        fun walk(c: Component) {
            if (c is javax.swing.JScrollPane && c.isShowing) panes += c
            if (c is Container) c.components.forEach { walk(it) }
        }
        walk(root)
        // Only panes that actually have somewhere to scroll; the Inspector and small
        // side panels report maximum == visibleAmount and would swallow the request.
        // Only panes with somewhere to scroll; the Inspector and side panels report
        // maximum == visibleAmount and would otherwise swallow the request.
        val scrollable = panes.filter {
            val vb = it.verticalScrollBar
            vb != null && vb.maximum > vb.visibleAmount + 4
        }
        if (scrollable.isEmpty()) return@onEdt "No scrollable pane found (nothing to scroll)"
        // The request editor is usually short; the response holds the payload. Largest
        // scrollable pane is the response unless the caller asks for the request side.
        val ordered = scrollable.sortedByDescending { it.height * it.width }
        val target = if (requestSide && ordered.size > 1) ordered[1] else ordered[0]
        val bar = target.verticalScrollBar ?: return@onEdt "No vertical scrollbar"
        val range = (bar.maximum - bar.visibleAmount).coerceAtLeast(0)
        val value = (range * fraction.coerceIn(0.0, 1.0)).toInt()
        bar.value = value
        target.revalidate(); target.repaint()
        "Scrolled to $value of $range (visible=${bar.visibleAmount}, max=${bar.maximum})"
    }

    /** Selects a view tab (Pretty / Raw / Hex / Render / JQ) in the Repeater request or
     *  response editor. JQ shows the JSON body alone, with no headers above it. */
    fun selectEditorView(api: MontoyaApi, viewName: String, rightHalf: Boolean): String = onEdt {
        val pane = findRepeaterRequestPane(api) ?: return@onEdt "No Repeater pane"
        var root: Component = pane
        repeat(6) { root = root.parent ?: root }
        val rootX = runCatching { root.locationOnScreen.x }.getOrDefault(0)
        val mid = rootX + root.width / 2
        val hits = mutableListOf<JTabbedPane>()
        fun walk(c: Component) {
            if (c is JTabbedPane && c.isShowing && c !== pane) {
                val titles = (0 until c.tabCount).map { c.getTitleAt(it).trim().lowercase() }
                if (titles.contains("pretty") && titles.contains("raw")) {
                    val x = runCatching { c.locationOnScreen.x }.getOrDefault(0)
                    if ((x >= mid) == rightHalf) hits += c
                }
            }
            if (c is Container) c.components.forEach { walk(it) }
        }
        walk(root)
        val editor = hits.firstOrNull() ?: return@onEdt "Editor view tabs not found"
        val idx = (0 until editor.tabCount).firstOrNull {
            editor.getTitleAt(it).trim().equals(viewName.trim(), ignoreCase = true)
        } ?: return@onEdt "View '$viewName' not available (have: " +
            (0 until editor.tabCount).joinToString { editor.getTitleAt(it) } + ")"
        editor.selectedIndex = idx
        editor.revalidate(); editor.repaint()
        "Selected view '${editor.getTitleAt(idx)}'"
    }

    private fun namedComponent(root: Component, name: String): Component? {
        if (root.name == name) return root
        if (root is Container) root.components.forEach { c -> namedComponent(c, name)?.let { return it } }
        return null
    }

    /** Finds the first showing component whose rendered text equals [label]. */
    private fun textOf(root: Component, label: String): Component? {
        if (root.isShowing) {
            val t = when (root) {
                is javax.swing.AbstractButton -> root.text
                is javax.swing.JLabel -> root.text
                else -> null
            }
            if (t != null && t.trim().equals(label, ignoreCase = true)) return root
        }
        if (root is Container) root.components.forEach { c -> textOf(c, label)?.let { return it } }
        return null
    }

    /** Debug helper: dumps candidate clickable components under the Repeater view. */
    fun dumpRepeaterUi(api: MontoyaApi): String = onEdt {
        val pane = findRepeaterRequestPane(api) ?: return@onEdt "No Repeater tab pane found"
        var root: Component = pane
        repeat(6) { root = root.parent ?: root }
        val sb = StringBuilder()
        fun walk(c: Component, depth: Int) {
            val cls = c.javaClass.simpleName
            val txt = when (c) {
                is javax.swing.AbstractButton -> c.text
                is javax.swing.JLabel -> c.text
                else -> null
            }
            val tip = (c as? javax.swing.JComponent)?.toolTipText
            val name = c.name
            // Tabbed panes carry no component text, so they never matched the clickable filter
            // below. select_editor_view locates the Pretty/Raw/Hex selector by tab title, so the
            // dump has to show tab titles or the failure cannot be diagnosed from here.
            if (c is JTabbedPane && c.isShowing) {
                val titles = (0 until c.tabCount).joinToString("|") { c.getTitleAt(it) }
                val x = runCatching { c.locationOnScreen.x }.getOrDefault(-1)
                sb.append("  ".repeat(depth.coerceAtMost(8)))
                  .append(cls)
                  .append(" TABS=[").append(titles).append("]")
                  .append(" name=").append(name ?: "-")
                  .append(" x=").append(x)
                  .append(" showing=true")
                  .append("\n")
            }
            if (c.isShowing && (c is javax.swing.AbstractButton || (txt != null && txt.isNotBlank()))) {
                sb.append("  ".repeat(depth.coerceAtMost(8)))
                  .append(cls)
                  .append(" text=").append(txt ?: "-")
                  .append(" name=").append(name ?: "-")
                  .append(" tip=").append(tip ?: "-")
                  .append(" showing=").append(c.isShowing)
                  .append("\n")
            }
            if (c is Container) c.components.forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        sb.toString().lines().filter { it.isNotBlank() }.take(300).joinToString("\n")
    }

    private fun findRepeaterRequestPane(api: MontoyaApi): JTabbedPane? = onEdt {
        val frame = api.userInterface().swingUtils().suiteFrame()
        val mainPane = allTabbedPanes(frame).firstOrNull { pane ->
            (0 until pane.tabCount).any { pane.getTitleAt(it).equals("Repeater", ignoreCase = true) }
        } ?: return@onEdt null

        val repeaterIndex = (0 until mainPane.tabCount).first {
            mainPane.getTitleAt(it).equals("Repeater", ignoreCase = true)
        }
        mainPane.selectedIndex = repeaterIndex
        mainPane.revalidate()
        mainPane.repaint()

        val content = mainPane.selectedComponent ?: return@onEdt null
        allTabbedPanes(content)
            .filter { isRequestTabPane(it) }
            .maxByOrNull { requestPaneScore(it) }
    }

    private fun isRequestTabPane(pane: JTabbedPane): Boolean {
        if (pane.tabCount == 0) return false
        return (0 until pane.tabCount).any { pane.getTitleAt(it).trim().lowercase() !in NON_REQUEST_TITLES }
    }

    private fun requestPaneScore(pane: JTabbedPane): Int = (0 until pane.tabCount).sumOf { index ->
        val title = pane.getTitleAt(index).trim().lowercase()
        when {
            title.matches(Regex("\\d+")) -> 4
            title.startsWith("qa-") || title.startsWith("poc-") -> 3
            title in NON_REQUEST_TITLES -> -4
            else -> 2
        }
    }

    private val NON_REQUEST_TITLES = setOf(
            "pretty", "raw", "hex", "render", "request", "response",
            "inspector", "notes", "explanations", "custom actions",
            "target", "proxy", "intruder", "repeater", "collaborator",
            "sequencer", "decoder", "comparer", "logger", "extensions",
        "dashboard", "learn", "settings", "organizer", "+"
    )

    private fun allTabbedPanes(component: Component): List<JTabbedPane> {
        val result = mutableListOf<JTabbedPane>()
        if (component is JTabbedPane) result += component
        if (component is Container) {
            component.components.forEach { child -> result += allTabbedPanes(child) }
        }
        return result
    }

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private data class TargetTab(
        val pane: JTabbedPane,
        val index: Int,
        val title: String,
        val closePoint: Point,
    )
}
