package app.flit.share

/**
 * Pure helpers behind the share sheet's visual identity. Split out for the
 * JVM test suite — same values power the Home banner and shortcut icons.
 */
object SheetUi {

    /**
     * Up to two glyphs standing in for a peer: the first letter of each of
     * the first two word-ish chunks ("Anne Katrin" → "AK", "andré" → "A").
     * Unicode-aware: accented and CJK letters count as letters.
     */
    fun initials(alias: String): String {
        val words = alias.splitToSequence(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
            .take(2)
            .toList()
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(1)
            else -> words[0].take(1) + words[1].take(1)
        }.uppercase()
    }

    /** Deterministic color hue for a fingerprint — stable per peer across sessions. */
    fun hueFor(fp: String): Int {
        var x = 0x9E3779B9.toInt() // golden ratio seed
        for (c in fp) x = x * 31 + c.code
        // finalizer avalanche — fingerprints differing in one char scatter hard
        x = x xor (x ushr 16)
        x *= -0x7A143595
        x = x xor (x ushr 15)
        return ((x % 360) + 360) % 360
    }

    /** "1 file" / "3 files" phrasing used by the banner and shortcut label. */
    fun countSummary(n: Int): String = "$n file${if (n != 1) "s" else ""}"
}
