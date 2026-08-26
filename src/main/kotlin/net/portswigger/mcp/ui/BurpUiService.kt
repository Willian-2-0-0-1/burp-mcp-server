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

        val target = onEdt {
            val pane = findRepeaterRequestPane(api)
                ?: error("No Repeater tab pane found")
            val index = when {
                tabName != null -> (0 until pane.tabCount).firstOrNull {
                    pane.getTitleAt(it).equals(tabName.trim(), ignoreCase = true)
                } ?: error("Repeater tab not found: ${tabName.trim()}")
                else -> tabIndex!! - 1
            }
            require(index in 0 until pane.tabCount) {
                "Repeater tab index out of range: ${tabIndex!!} (available: ${pane.tabCount})"
            }
            pane.selectedIndex = index
            pane.revalidate()
            pane.repaint()
            val bounds = pane.getBoundsAt(index)
                ?: error("Unable to locate the Repeater tab header")
            val screen = pane.locationOnScreen
            TargetTab(pane, index, pane.getTitleAt(index), Point(
                screen.x + bounds.x + (bounds.width - 12).coerceAtLeast(4),
                screen.y + bounds.y + bounds.height / 2,
            ))
        }

        Robot().apply {
            autoDelay = 60
            mouseMove(target.closePoint.x, target.closePoint.y)
            mousePress(InputEvent.BUTTON1_DOWN_MASK)
            mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        }

        Thread.sleep(250)
        val remaining = onEdt {
            (0 until target.pane.tabCount).map { target.pane.getTitleAt(it) }
        }
        require(remaining.none { it.equals(target.title, ignoreCase = true) }) {
            "Burp did not close Repeater tab: ${target.title}"
        }
        return "Closed Repeater tab: ${target.title}"
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
        val common = setOf("pretty", "raw", "hex", "render", "request", "response")
        return (0 until pane.tabCount).any { pane.getTitleAt(it).trim().lowercase() !in common }
    }

    private fun requestPaneScore(pane: JTabbedPane): Int = (0 until pane.tabCount).sumOf { index ->
        val title = pane.getTitleAt(index).trim().lowercase()
        when {
            title.matches(Regex("\\d+")) -> 4
            title.startsWith("qa-") || title.startsWith("poc-") -> 3
            title !in setOf("pretty", "raw", "hex", "render", "request", "response") -> 2
            else -> 0
        }
    }

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
