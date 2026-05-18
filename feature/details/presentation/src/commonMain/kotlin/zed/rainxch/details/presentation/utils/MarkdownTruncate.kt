package zed.rainxch.details.presentation.utils

/**
 * Returns a markdown substring suitable for rendering as a collapsed
 * "preview" without the cost of composing the full tree. Truncates at a
 * sensible boundary (double newline, then single newline, then `maxChars`)
 * so headings and code fences aren't sliced mid-block.
 *
 * Always callable from any thread — no Compose APIs touched.
 */
fun truncateMarkdownPreview(content: String, maxChars: Int): String {
    if (content.length <= maxChars) return content
    val window = content.substring(0, maxChars)

    // Prefer cutting at paragraph break (blank line) inside the last 25%
    // of the window so the preview ends on a natural section boundary.
    val searchFrom = (maxChars * 0.75).toInt().coerceAtLeast(0)
    val paragraphBreak = window.lastIndexOf("\n\n", maxChars).takeIf { it >= searchFrom }
    if (paragraphBreak != null && paragraphBreak > 0) {
        return window.substring(0, paragraphBreak).trimEnd() + "\n"
    }
    val newline = window.lastIndexOf('\n', maxChars).takeIf { it >= searchFrom }
    if (newline != null && newline > 0) {
        return window.substring(0, newline).trimEnd() + "\n"
    }
    return window.trimEnd() + "…"
}
