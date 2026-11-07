package org.graphiks.hellodawn4k

import org.graphiks.kalligraphie.FontDirectoryCatalog
import org.graphiks.kalligraphie.FontDirectoryCatalogOptions
import org.graphiks.kalligraphie.MacosSystemFontCatalog
import org.graphiks.kalligraphie.MacosSystemFontCatalogOptions
import org.graphiks.kalligraphie.api.FontOperationResult

/**
 * Desktop font capture for the shared glyph chain. Liberation Sans ships in the JVM
 * resources (OFL-1.1, the font Kalligraphie's own benches exercise most) and is extracted
 * to a private directory — deterministic on every run; the directory is wiped at process
 * exit. A limits-raised macOS system scan stays as fallback: the default catalog caps
 * acceptance at 32 faces in enumeration order, and on recent macOS the SFNT name tables
 * of system fonts such as Menlo are rejected, so success is not guaranteed.
 */
object DesktopHudFont {

    fun open(fontPx: Float = 18f): HudText? {
        extractBundledFont()?.let { dir ->
            val opened = FontDirectoryCatalog.open(FontDirectoryCatalogOptions(roots = listOf(dir.toString())))
            (opened as? FontOperationResult.Success)?.value?.let { catalog ->
                HudText.openFromCatalog(catalog, listOf("Liberation Sans"), fontPx)?.let { return it }
            }
        }
        val raisedLimits = MacosSystemFontCatalogOptions(
            maxPathsToVisit = 2048,
            maxFaces = 512,
            maxFacesToExamine = 2048,
            maxTotalSourceBytes = 512 * 1024 * 1024,
        )
        val opened = MacosSystemFontCatalog.open(raisedLimits)
        val catalog = (opened as? FontOperationResult.Success)?.value
        if (catalog == null) {
            println("hud disabled: macOS catalog refused ($opened)")
            return null
        }
        return HudText.openFromCatalog(catalog, listOf("Menlo", "Monaco", "Courier New"), fontPx)
    }

    /** Extracts the bundled Liberation Sans into a private temp dir registered for exit-time cleanup. */
    private fun extractBundledFont(): java.nio.file.Path? = runCatching {
        val resource = requireNotNull(javaClass.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            "bundled font missing"
        }
        val dir = java.nio.file.Files.createTempDirectory("hellodawn4k-font")
        java.nio.file.Files.copy(resource, dir.resolve("LiberationSans-Regular.ttf"))
        Runtime.getRuntime().addShutdownHook(Thread { dir.toFile().deleteRecursively() })
        dir
    }.getOrNull()
}
