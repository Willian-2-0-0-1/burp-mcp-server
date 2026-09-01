package net.portswigger.mcp.evidence

import burp.api.montoya.MontoyaApi
import kotlinx.serialization.Serializable
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.Locale
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@Serializable
data class EvidenceCaptureResult(
    val path: String,
    val width: Int,
    val height: Int,
    val scope: String,
)

@Serializable
data class EvidenceAnnotation(
    val type: String,
    val x: Int,
    val y: Int,
    val width: Int = 0,
    val height: Int = 0,
    val endX: Int? = null,
    val endY: Int? = null,
    val text: String? = null,
    val color: String = "#ff2020",
    val strokeWidth: Int = 4,
    val fontSize: Int = 28,
)

object EvidenceService {
    fun root(configuredDirectory: String): Path {
        val root = Paths.get(configuredDirectory).toAbsolutePath().normalize()
        Files.createDirectories(root)
        return root
    }

    fun capture(
        api: MontoyaApi,
        configuredDirectory: String,
        label: String,
        scope: String,
        poc: String? = null,
        cropX: Int? = null,
        cropY: Int? = null,
        cropWidth: Int? = null,
        cropHeight: Int? = null,
        scale: Double? = null,
    ): EvidenceCaptureResult {
        val normalizedScope = if (scope.lowercase() == "full") "full" else "burp"
        val frame = api.userInterface().swingUtils().suiteFrame()
        val effective = if (normalizedScope == "full") 1.0 else captureScale(frame, scale)
        var image = if (normalizedScope == "full") captureFullScreen() else captureBurp(api, effective)
        // Crop arguments stay in logical (on-screen) coordinates so callers do not have to
        // know the display scale; they are converted here.
        fun s(v: Int?) = v?.let { (it * effective).toInt() }
        image = crop(image, s(cropX), s(cropY), s(cropWidth), s(cropHeight))

        val safeLabel = label.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .ifBlank { "burp-evidence" }
        val safePoc = sanitizeSegment(poc, "poc-1")
        val timestamp = Instant.now().toString().replace(':', '-')
        val pocDirectory = root(configuredDirectory).resolve(safePoc)
        Files.createDirectories(pocDirectory)
        val output = pocDirectory.resolve("$timestamp-$safeLabel.png")
        check(ImageIO.write(image, "png", output.toFile())) { "PNG writer is unavailable" }
        return EvidenceCaptureResult(output.toString(), image.width, image.height, normalizedScope)
    }

    fun annotate(
        configuredDirectory: String,
        capturePath: String,
        annotations: List<EvidenceAnnotation>,
    ): EvidenceCaptureResult {
        val root = root(configuredDirectory)
        val input = resolveInsideRoot(root, capturePath)
        require(Files.isRegularFile(input)) { "Capture does not exist: $input" }
        val image = ImageIO.read(input.toFile()) ?: error("Unsupported image: $input")
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            annotations.forEach { drawAnnotation(graphics, image, it) }
        } finally {
            graphics.dispose()
        }

        val fileName = input.fileName.toString()
        val stem = fileName.substringBeforeLast('.', fileName)
        val output = input.parent.resolve("$stem-annotated.png")
        check(ImageIO.write(image, "png", output.toFile())) { "PNG writer is unavailable" }
        return EvidenceCaptureResult(output.toString(), image.width, image.height, "annotated")
    }

    fun list(configuredDirectory: String, limit: Int): List<String> {
        val root = root(configuredDirectory)
        return Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".png") }
                .sorted(compareByDescending<Path> { Files.getLastModifiedTime(it).toMillis() })
                .limit(limit.coerceIn(1, 100).toLong())
                .map { it.toString() }
                .toList()
        }
    }

    private fun sanitizeSegment(value: String?, fallback: String): String = value.orEmpty()
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-')
        .ifBlank { fallback }

    fun readPng(configuredDirectory: String, capturePath: String): ByteArray {
        val path = resolveInsideRoot(root(configuredDirectory), capturePath)
        require(Files.isRegularFile(path)) { "Capture does not exist: $path" }
        return Files.readAllBytes(path)
    }

    /** Scale factor used when rendering Swing into the capture. On a HiDPI display
     *  printAll() at logical size throws away half the resolution, which shows up as low
     *  PPI in a report PDF. Rendering through a scaled Graphics2D re-renders text and
     *  vectors at the higher resolution - this is genuine detail, not an upscale. */
    private fun captureScale(frame: java.awt.Component, requested: Double?): Double {
        val device = runCatching {
            frame.graphicsConfiguration.defaultTransform.scaleX
        }.getOrDefault(1.0)
        val chosen = requested ?: maxOf(device, 2.0)
        return chosen.coerceIn(1.0, 4.0)
    }

    private fun captureBurp(api: MontoyaApi, requestedScale: Double? = null): BufferedImage {
        val frame = api.userInterface().swingUtils().suiteFrame()
        val scale = captureScale(frame, requestedScale)
        val width = max(1, frame.width)
        val height = max(1, frame.height)
        val image = BufferedImage((width * scale).toInt(), (height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
        val render = Runnable {
            val graphics = image.createGraphics()
            try {
                graphics.setRenderingHint(
                    java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON
                )
                graphics.setRenderingHint(
                    java.awt.RenderingHints.KEY_RENDERING,
                    java.awt.RenderingHints.VALUE_RENDER_QUALITY
                )
                graphics.scale(scale, scale)
                graphics.color = frame.background
                graphics.fillRect(0, 0, width, height)
                frame.printAll(graphics)
            } finally {
                graphics.dispose()
            }
        }
        if (SwingUtilities.isEventDispatchThread()) render.run() else SwingUtilities.invokeAndWait(render)
        return image
    }

    private fun captureFullScreen(): BufferedImage {
        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { it.defaultConfiguration.bounds }
            .reduce { current, next -> current.union(next) }
        return Robot().createScreenCapture(bounds)
    }

    private fun crop(
        source: BufferedImage,
        x: Int?,
        y: Int?,
        width: Int?,
        height: Int?,
    ): BufferedImage {
        if (x == null && y == null && width == null && height == null) return source
        val safeX = (x ?: 0).coerceIn(0, source.width - 1)
        val safeY = (y ?: 0).coerceIn(0, source.height - 1)
        val safeWidth = (width ?: source.width - safeX).coerceIn(1, source.width - safeX)
        val safeHeight = (height ?: source.height - safeY).coerceIn(1, source.height - safeY)
        val cropped = BufferedImage(safeWidth, safeHeight, BufferedImage.TYPE_INT_RGB)
        val graphics = cropped.createGraphics()
        try {
            graphics.drawImage(source, 0, 0, safeWidth, safeHeight, safeX, safeY, safeX + safeWidth, safeY + safeHeight, null)
        } finally {
            graphics.dispose()
        }
        return cropped
    }

    private fun resolveInsideRoot(root: Path, requestedPath: String): Path {
        val requested = Paths.get(requestedPath).toAbsolutePath().normalize()
        require(requested.startsWith(root)) { "Evidence path must stay inside $root" }
        return requested
    }

    private fun drawAnnotation(graphics: Graphics2D, image: BufferedImage, annotation: EvidenceAnnotation) {
        val color = parseColor(annotation.color)
        val stroke = BasicStroke(annotation.strokeWidth.coerceIn(1, 20).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        graphics.stroke = stroke
        graphics.color = color

        when (annotation.type.lowercase()) {
            "rectangle" -> graphics.drawRect(annotation.x, annotation.y, annotation.width, annotation.height)
            "highlight" -> {
                val originalComposite = graphics.composite
                graphics.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.28f)
                graphics.fillRect(annotation.x, annotation.y, annotation.width, annotation.height)
                graphics.composite = originalComposite
                graphics.drawRect(annotation.x, annotation.y, annotation.width, annotation.height)
            }
            "redact" -> {
                graphics.color = Color.BLACK
                graphics.fillRect(annotation.x, annotation.y, annotation.width, annotation.height)
            }
            "blur" -> pixelate(graphics, image, annotation)
            "text" -> drawText(graphics, annotation)
            "arrow" -> drawArrow(graphics, annotation)
            "callout" -> drawCallout(graphics, annotation)
            else -> error("Unsupported annotation type: ${annotation.type}")
        }
    }

    private fun drawText(graphics: Graphics2D, annotation: EvidenceAnnotation) {
        val text = annotation.text?.takeIf { it.isNotBlank() } ?: return
        graphics.color = parseColor(annotation.color)
        graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, annotation.fontSize.coerceIn(10, 96))
        val lines = text.lines()
        val lineHeight = graphics.fontMetrics.height
        lines.forEachIndexed { index, line ->
            graphics.drawString(line, annotation.x, annotation.y + index * lineHeight)
        }
    }

    private fun drawArrow(graphics: Graphics2D, annotation: EvidenceAnnotation) {
        val endX = annotation.endX ?: annotation.x + annotation.width
        val endY = annotation.endY ?: annotation.y + annotation.height
        drawArrowLine(graphics, annotation.x, annotation.y, endX, endY, annotation)
    }

    private fun drawCallout(graphics: Graphics2D, annotation: EvidenceAnnotation) {
        drawText(graphics, annotation)
        val text = annotation.text.orEmpty().lineSequence().maxByOrNull { it.length }.orEmpty()
        graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, annotation.fontSize.coerceIn(10, 96))
        val labelWidth = if (annotation.width > 0) annotation.width else graphics.fontMetrics.stringWidth(text) + 12
        val startX = annotation.x + labelWidth
        val startY = annotation.y - graphics.fontMetrics.ascent / 3
        val endX = annotation.endX ?: startX + 80
        val endY = annotation.endY ?: startY
        drawArrowLine(graphics, startX, startY, endX, endY, annotation)
    }

    private fun drawArrowLine(
        graphics: Graphics2D,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        annotation: EvidenceAnnotation,
    ) {
        graphics.color = parseColor(annotation.color)
        graphics.draw(Line2D.Double(startX.toDouble(), startY.toDouble(), endX.toDouble(), endY.toDouble()))
        val angle = atan2((endY - startY).toDouble(), (endX - startX).toDouble())
        val arrowSize = max(12.0, annotation.strokeWidth * 4.0)
        val leftX = endX - arrowSize * cos(angle - Math.PI / 6)
        val leftY = endY - arrowSize * sin(angle - Math.PI / 6)
        val rightX = endX - arrowSize * cos(angle + Math.PI / 6)
        val rightY = endY - arrowSize * sin(angle + Math.PI / 6)
        graphics.draw(Line2D.Double(endX.toDouble(), endY.toDouble(), leftX, leftY))
        graphics.draw(Line2D.Double(endX.toDouble(), endY.toDouble(), rightX, rightY))
    }

    private fun pixelate(graphics: Graphics2D, image: BufferedImage, annotation: EvidenceAnnotation) {
        val x = annotation.x.coerceIn(0, image.width - 1)
        val y = annotation.y.coerceIn(0, image.height - 1)
        val width = min(annotation.width, image.width - x).coerceAtLeast(1)
        val height = min(annotation.height, image.height - y).coerceAtLeast(1)
        val source = image.getSubimage(x, y, width, height)
        val pixelWidth = max(1, width / 12)
        val pixelHeight = max(1, height / 12)
        val tiny = BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_RGB)
        tiny.createGraphics().use { it.drawImage(source, 0, 0, pixelWidth, pixelHeight, null) }
        graphics.drawImage(tiny, x, y, width, height, null)
    }

    private fun parseColor(value: String): Color = try {
        Color.decode(value)
    } catch (_: Exception) {
        Color.RED
    }

    private inline fun <T : Graphics2D> T.use(block: (T) -> Unit) {
        try { block(this) } finally { dispose() }
    }
}
