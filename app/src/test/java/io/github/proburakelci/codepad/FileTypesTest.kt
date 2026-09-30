package io.github.proburakelci.codepad

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These run on a plain JVM - `./gradlew test`, no device, no emulator, and
 * they work in CI.
 *
 * What they cover is the part of a phone app with no user interface to catch
 * mistakes in. Open a `.rs` file, get JavaScript highlighting, and nothing
 * anywhere says a word about it. So the mapping is checked against the
 * editor's own language table, read off the assets, which means renaming a
 * language in highlight.js fails this build instead of silently degrading the
 * app.
 */
class FileTypesTest {

    /* ---------------- extensions ---------------- */

    @Test
    fun `an extension is the last dotted part, lowercased`() {
        assertEquals("js", FileTypes.extensionOf("thing.js"))
        assertEquals("py", FileTypes.extensionOf("Thing.PY"))
        assertEquals("gz", FileTypes.extensionOf("archive.tar.gz"))
    }

    @Test
    fun `a dot in a folder name is not an extension`() {
        assertNull(FileTypes.extensionOf("/a.b.c/d.e/Makefile"))
        assertEquals("go", FileTypes.extensionOf("/a.b.c/d.e/main.go"))
        assertEquals("kt", FileTypes.extensionOf("C:\\my.stuff\\A.kt"))
    }

    @Test
    fun `things that are not extensions are not treated as extensions`() {
        assertNull(FileTypes.extensionOf(null))
        assertNull(FileTypes.extensionOf(""))
        assertNull(FileTypes.extensionOf("   "))
        assertNull(FileTypes.extensionOf("Makefile"))
        assertNull(FileTypes.extensionOf(".gitignore")) // a dotfile, not an extension
        assertNull(FileTypes.extensionOf("trailing.")) // nothing after the dot
    }

    /* ---------------- languages ---------------- */

    @Test
    fun `a file becomes the language it looks like`() {
        assertEquals("javascript", FileTypes.languageFor("app.js"))
        assertEquals("javascript", FileTypes.languageFor("mod.mjs"))
        assertEquals("typescript", FileTypes.languageFor("View.tsx"))
        assertEquals("python", FileTypes.languageFor("script.py"))
        assertEquals("rust", FileTypes.languageFor("main.rs"))
        assertEquals("c", FileTypes.languageFor("thing.cpp"))
        assertEquals("c", FileTypes.languageFor("thing.h"))
        assertEquals("shell", FileTypes.languageFor("setup.zsh"))
        assertEquals("markdown", FileTypes.languageFor("NOTES.markdown"))
        assertEquals("yaml", FileTypes.languageFor("ci.yaml"))
    }

    @Test
    fun `an unfamiliar file is null rather than a guess`() {
        assertNull(FileTypes.languageFor("photo.png"))
        assertNull(FileTypes.languageFor("archive.zip"))
        assertNull(FileTypes.languageFor("Makefile"))
        assertNull(FileTypes.languageFor(null))
    }

    /*
     * The check that actually matters. Every language this app claims it can
     * open has to be a language the editor has - and every language the
     * editor has should be reachable by opening a file, or those files can
     * only be pasted in.
     */
    @Test
    fun `every language matches the editor's own table`() {
        val highlight = assetFile("js/highlight.js").readText()

        /*
         * Only the LANGUAGES block, so a `something: {` elsewhere in the file
         * cannot be mistaken for a language. Inside it, a key followed by an
         * opening brace is a language and nothing else is - the properties of
         * each one are strings and arrays.
         */
        val block = Regex("""const LANGUAGES = \{([\s\S]*?)\n  \};""")
            .find(highlight)
            ?.groupValues
            ?.get(1)
            ?: ""

        val editorIds = Regex("""^\s+(\w+):\s*\{""", RegexOption.MULTILINE)
            .findAll(block)
            .map { it.groupValues[1] }
            .toSet()

        assertTrue(
            "the language table could not be read out of highlight.js",
            editorIds.size > 10,
        )

        val claimed = FileTypes.all.map { it.language }.toSet()

        assertEquals(
            "these are offered for opening but the editor has no such language",
            emptySet<String>(),
            claimed - editorIds,
        )
        assertEquals(
            "the editor has these languages but no file extension opens them",
            emptySet<String>(),
            editorIds - claimed,
        )
    }

    @Test
    fun `no extension is claimed by two languages`() {
        val seen = mutableMapOf<String, String>()
        for (type in FileTypes.all) {
            for (extension in type.extensions) {
                val already = seen[extension]
                assertNull(
                    "$extension is claimed by both $already and ${type.language}",
                    already,
                )
                seen[extension] = type.language
            }
        }
    }

    @Test
    fun `every extension is bare and lowercase`() {
        for (type in FileTypes.all) {
            assertTrue("${type.label} has no extensions", type.extensions.isNotEmpty())
            for (extension in type.extensions) {
                assertFalse("$extension starts with a dot", extension.startsWith("."))
                assertEquals("$extension is not lowercase", extension.lowercase(), extension)
            }
        }
    }

    /* ---------------- saving ---------------- */

    @Test
    fun `a new file is offered a sensible name`() {
        assertEquals("snippet.js", FileTypes.suggestedName("js"))
        assertEquals("snippet.py", FileTypes.suggestedName("PY"))
        assertEquals("snippet.txt", FileTypes.suggestedName(null))
        assertEquals("snippet.txt", FileTypes.suggestedName(""))
    }

    @Test
    fun `a suggested name cannot carry a path or a quote out of the editor`() {
        // an extension is whatever the page said it was, so it is scrubbed
        assertEquals("snippet.txt", FileTypes.suggestedName("../../etc"))
        assertEquals("snippet.txt", FileTypes.suggestedName("/"))
        assertEquals("snippet.txt", FileTypes.suggestedName("j\"s"))
        // too long to have come from the language table, so it is not trusted
        assertEquals("snippet.txt", FileTypes.suggestedName("abcdefghijklmnop"))

        // and every real extension still comes through untouched
        for (type in FileTypes.all) {
            for (extension in type.extensions) {
                assertEquals("snippet.$extension", FileTypes.suggestedName(extension))
            }
        }

        for (extension in FileTypes.all.flatMap { it.extensions } + listOf(null, "", "../x")) {
            val name = FileTypes.suggestedName(extension)
            assertFalse("$name contains a separator", name.contains('/') || name.contains('\\'))
            assertFalse("$name contains a quote", name.contains('"') || name.contains('\''))
            assertFalse("$name walks up a directory", name.contains(".."))
        }
    }

    @Test
    fun `the mime type is plain text unless we are sure`() {
        assertEquals("application/javascript", FileTypes.mimeTypeFor("js"))
        assertEquals("application/json", FileTypes.mimeTypeFor("json"))
        assertEquals("text/html", FileTypes.mimeTypeFor("HTML"))
        assertEquals("text/plain", FileTypes.mimeTypeFor("rs"))
        assertEquals("text/plain", FileTypes.mimeTypeFor(null))
        assertEquals("text/plain", FileTypes.mimeTypeFor("something invented"))
    }

    @Test
    fun `every mime type is a single well formed type`() {
        for (extension in FileTypes.all.flatMap { it.extensions }) {
            val mime = FileTypes.mimeTypeFor(extension)
            assertTrue("$mime is not a mime type", Regex("^[a-z]+/[a-z0-9.+-]+$").matches(mime))
        }
    }

    companion object {
        /** Unit tests run from the module folder, so assets are two hops down. */
        fun assetFile(relative: String): File {
            val here = File("src/main/assets/$relative")
            if (here.exists()) return here
            return File("app/src/main/assets/$relative")
        }
    }
}
