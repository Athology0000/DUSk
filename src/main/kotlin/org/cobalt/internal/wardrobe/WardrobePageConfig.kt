package org.cobalt.internal.wardrobe

object WardrobePageConfig {

    /**
     * Parse a comma-separated string of set slot numbers (1–27) into a [Set<Int>].
     * Invalid entries (non-integer, out-of-range) are silently ignored.
     */
    fun parseSlots(text: String): Set<Int> =
        text.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..27 }
            .toSet()

    /**
     * Resolve which custom page (1, 2, or 3) each set ID (1–27) belongs to.
     *
     * - Sets whose IDs appear in [page1Text] → page 1
     * - Sets whose IDs appear in [page2Text] → page 2
     * - All remaining sets → page 3
     *
     * If a set ID appears in both page 1 and page 2, page 1 takes precedence.
     */
    fun resolvePages(page1Text: String, page2Text: String): Map<Int, Int> {
        val page1 = parseSlots(page1Text)
        val page2 = parseSlots(page2Text)
        return (1..27).associateWith { id ->
            when {
                id in page1 -> 1
                id in page2 -> 2
                else        -> 3
            }
        }
    }
}
