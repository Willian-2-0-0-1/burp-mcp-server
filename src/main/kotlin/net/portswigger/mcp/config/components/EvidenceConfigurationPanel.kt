package net.portswigger.mcp.config.components

import burp.api.montoya.MontoyaApi
import net.portswigger.mcp.config.Design
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.evidence.EvidenceCaptureResult
import net.portswigger.mcp.evidence.EvidenceService
import java.awt.Desktop
import java.awt.FlowLayout
import java.awt.event.ItemEvent
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingWorker

class EvidenceConfigurationPanel(
    private val api: MontoyaApi,
    private val config: McpConfig,
) : JPanel() {
    private val directoryField = JTextField(config.evidenceDirectory, 28)
    private val pocField = JTextField(config.evidenceDefaultPoc, 18)
    private val scopeField = JComboBox(arrayOf("burp", "full")).apply {
        selectedItem = config.evidenceDefaultScope
    }
    private val statusLabel = JLabel(" ")

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
        updateColors()
        buildPanel()
    }

    override fun updateUI() {
        super.updateUI()
        updateColors()
    }

    private fun updateColors() {
        background = Design.Colors.surface
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Design.Colors.outlineVariant, 1),
            BorderFactory.createEmptyBorder(
                Design.Spacing.MD, Design.Spacing.MD, Design.Spacing.MD, Design.Spacing.MD
            )
        )
    }

    private fun buildPanel() {
        add(Design.createSectionLabel("Evidence capture"))
        add(Box.createVerticalStrut(Design.Spacing.SM))
        add(JLabel("Capture Burp screenshots and annotate report evidence through MCP."))
        add(Box.createVerticalStrut(Design.Spacing.MD))

        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(JButton("Open screenshots folder").apply {
                toolTipText = "Open the evidence root; screenshots are organized inside PoC subfolders"
                addActionListener { openFolder() }
            })
        })
        add(Box.createVerticalStrut(Design.Spacing.MD))

        add(JCheckBox("Enable screenshot tools", config.evidenceCaptureEnabled).apply {
            alignmentX = LEFT_ALIGNMENT
            addItemListener { config.evidenceCaptureEnabled = it.stateChange == ItemEvent.SELECTED }
        })
        add(Box.createVerticalStrut(Design.Spacing.SM))

        add(row("Evidence directory", directoryField, JButton("Browse").apply {
            addActionListener { chooseDirectory() }
        }))
        add(Box.createVerticalStrut(Design.Spacing.SM))
        add(row("Default PoC folder", pocField))
        add(Box.createVerticalStrut(Design.Spacing.SM))
        add(row("Default capture scope", scopeField))
        add(Box.createVerticalStrut(Design.Spacing.SM))

        add(JCheckBox("Return PNG image to the MCP client", config.evidenceIncludeImage).apply {
            alignmentX = LEFT_ALIGNMENT
            addItemListener { config.evidenceIncludeImage = it.stateChange == ItemEvent.SELECTED }
        })
        add(Box.createVerticalStrut(Design.Spacing.MD))

        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(JButton("Save evidence settings").apply { addActionListener { saveSettings() } })
            add(Box.createHorizontalStrut(Design.Spacing.SM))
            add(JButton("Capture now").apply { addActionListener { captureNow(this) } })
        })
        add(Box.createVerticalStrut(Design.Spacing.SM))
        add(statusLabel.apply {
            alignmentX = LEFT_ALIGNMENT
            foreground = Design.Colors.onSurfaceVariant
        })
    }

    private fun row(label: String, vararg components: java.awt.Component): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(JLabel("$label: "))
            components.forEachIndexed { index, component ->
                if (index > 0) add(Box.createHorizontalStrut(Design.Spacing.SM))
                add(component)
            }
        }

    private fun chooseDirectory() {
        val chooser = JFileChooser(directoryField.text).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Choose evidence directory"
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            directoryField.text = chooser.selectedFile.absolutePath
        }
    }

    private fun saveSettings() {
        try {
            val directory = Paths.get(directoryField.text.trim()).toAbsolutePath().normalize()
            Files.createDirectories(directory)
            config.evidenceDirectory = directory.toString()
            config.evidenceDefaultPoc = pocField.text.trim().ifBlank { "poc-1" }
            config.evidenceDefaultScope = if (scopeField.selectedItem == "full") "full" else "burp"
            statusLabel.text = "Saved: $directory"
        } catch (error: Exception) {
            statusLabel.text = "Invalid directory: ${error.message}"
        }
    }

    private fun openFolder() {
        try {
            val directory = Paths.get(directoryField.text.trim()).toAbsolutePath().normalize()
            Files.createDirectories(directory)
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(directory.toFile())
            else statusLabel.text = "Desktop integration is unavailable"
        } catch (error: Exception) {
            statusLabel.text = "Unable to open folder: ${error.message}"
        }
    }

    private fun captureNow(button: JButton) {
        saveSettings()
        button.isEnabled = false
        statusLabel.text = "Capturing Burp..."
        object : SwingWorker<EvidenceCaptureResult, Void>() {
            override fun doInBackground(): EvidenceCaptureResult = EvidenceService.capture(
                api = api,
                configuredDirectory = config.evidenceDirectory,
                label = "manual-burp-capture",
                scope = config.evidenceDefaultScope,
                poc = config.evidenceDefaultPoc,
            )

            override fun done() {
                button.isEnabled = true
                try {
                    val result = get()
                    statusLabel.text = "Captured: ${result.path}"
                } catch (error: Exception) {
                    statusLabel.text = "Capture failed: ${error.cause?.message ?: error.message}"
                }
            }
        }.execute()
    }
}
