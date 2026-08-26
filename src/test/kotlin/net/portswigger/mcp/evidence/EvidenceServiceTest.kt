package net.portswigger.mcp.evidence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import javax.imageio.ImageIO

class EvidenceServiceTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `annotates and lists report evidence`() {
        val source = tempDirectory.resolve("repeater.png")
        val image = BufferedImage(500, 260, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().use { graphics ->
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, image.width, image.height)
        }
        ImageIO.write(image, "png", source.toFile())

        val result = EvidenceService.annotate(
            tempDirectory.toString(),
            source.toString(),
            listOf(
                EvidenceAnnotation("rectangle", 40, 40, 300, 120, color = "#ff0000", strokeWidth = 5),
                EvidenceAnnotation("callout", 20, 210, endX = 120, endY = 170, text = "Teste de escrita"),
                EvidenceAnnotation("redact", 360, 40, 100, 35),
            )
        )

        val output = Path.of(result.path)
        assertTrue(Files.isRegularFile(output))
        assertEquals(500, result.width)
        assertEquals(260, result.height)
        assertTrue(EvidenceService.list(tempDirectory.toString(), 10).contains(output.toString()))

        val annotated = ImageIO.read(output.toFile())
        assertTrue(Color(annotated.getRGB(40, 40)).red > 200)
        assertEquals(Color.BLACK.rgb, annotated.getRGB(380, 50))
    }

    @Test
    fun `rejects annotation paths outside evidence directory`() {
        val outside = tempDirectory.parent.resolve("outside.png")
        assertThrows(IllegalArgumentException::class.java) {
            EvidenceService.annotate(tempDirectory.toString(), outside.toString(), emptyList())
        }
    }

    @Test
    fun `lists evidence recursively inside PoC folders`() {
        val pocDirectory = tempDirectory.resolve("poc-auth-bypass").createDirectories()
        val source = pocDirectory.resolve("request.png")
        ImageIO.write(BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), "png", source.toFile())

        val listed = EvidenceService.list(tempDirectory.toString(), 10)

        assertTrue(listed.contains(source.toString()))
    }

    private inline fun <T : java.awt.Graphics2D> T.use(block: (T) -> Unit) {
        try { block(this) } finally { dispose() }
    }
}
