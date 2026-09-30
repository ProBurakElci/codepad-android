package io.github.proburakelci.codepad

/**
 * Which language a file is, and what to call a file being saved.
 *
 * This is deliberately plain Kotlin with no Android in it, because it is the
 * part most likely to quietly go wrong and the part with no user interface to
 * notice it going wrong. Open a `.rs` file, get JavaScript highlighting, no
 * complaint from anybody - so it is all checked by unit tests that run on a
 * plain JVM, no device needed.
 *
 * The language ids here must match the ones in `assets/js/highlight.js`. The
 * tests read that file and compare, so renaming a language there fails the
 * build here instead of silently falling back.
 */
object FileTypes {

    data class Type(val label: String, val extensions: List<String>, val language: String)

    val all: List<Type> = listOf(
        Type("JavaScript", listOf("js", "mjs", "cjs"), "javascript"),
        Type("TypeScript", listOf("ts", "tsx"), "typescript"),
        Type("Python", listOf("py", "pyw"), "python"),
        Type("HTML", listOf("html", "htm"), "html"),
        Type("CSS", listOf("css"), "css"),
        Type("JSON", listOf("json"), "json"),
        Type("SQL", listOf("sql"), "sql"),
        Type("Java", listOf("java"), "java"),
        Type("C and C++", listOf("c", "h", "cpp", "hpp", "cc", "cxx"), "c"),
        Type("C#", listOf("cs"), "csharp"),
        Type("Go", listOf("go"), "go"),
        Type("Rust", listOf("rs"), "rust"),
        Type("PHP", listOf("php"), "php"),
        Type("Ruby", listOf("rb"), "ruby"),
        Type("Shell", listOf("sh", "bash", "zsh"), "shell"),
        Type("YAML", listOf("yml", "yaml"), "yaml"),
        Type("Markdown", listOf("md", "markdown"), "markdown"),
    )

    /**
     * The language a name looks like, or null when nothing here recognises it.
     *
     * Null rather than a guess: the caller keeps whatever language is already
     * selected and says so, instead of yanking an unknown file into
     * JavaScript without a word.
     */
    fun languageFor(name: String?): String? {
        val extension = extensionOf(name) ?: return null
        return all.firstOrNull { extension in it.extensions }?.language
    }

    /** The last dotted part of a name, lowercased, or null if there isn't one. */
    fun extensionOf(name: String?): String? {
        if (name.isNullOrBlank()) return null

        // A content:// display name can carry a path, and a folder may have a
        // dot in it, so only look after the last separator.
        val bare = name.substringAfterLast('/').substringAfterLast('\\')
        val dot = bare.lastIndexOf('.')
        if (dot <= 0 || dot == bare.length - 1) return null

        return bare.substring(dot + 1).lowercase()
    }

    /**
     * A name to offer when saving something that has never been saved.
     * Android shows this in the system save sheet, where the user can edit it.
     */
    fun suggestedName(extension: String?): String {
        /*
         * The extension arrives from the page, which reads it off the editor's
         * own language table - so it is always something like "js". Anything
         * that is not already clean is a bug or somebody poking at it, and
         * either way the answer is plain text rather than a scrubbed version
         * of whatever came in. Stripping characters instead would quietly turn
         * "../../etc" into "etc", which is harmless but bewildering.
         */
        val clean = extension?.lowercase()?.takeIf { SAFE_EXTENSION.matches(it) }
        return "snippet." + (clean ?: "txt")
    }

    /** Short, lowercase, letters and digits. Every extension above matches it. */
    private val SAFE_EXTENSION = Regex("^[a-z0-9]{1,8}$")

    /**
     * The MIME type to hand the system save sheet. Android uses it to decide
     * which apps may receive the file, and some file pickers append an
     * extension based on it - so anything we are not sure about is plain text
     * rather than a wrong guess.
     */
    fun mimeTypeFor(extension: String?): String = when (extension?.lowercase()) {
        "js", "mjs", "cjs" -> "application/javascript"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "md", "markdown" -> "text/markdown"
        "csv" -> "text/csv"
        "xml" -> "text/xml"
        else -> "text/plain"
    }
}
