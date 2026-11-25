package dev.vvivid.newsfeed.ui.cover

/**
 * The 20 fallback cover patterns from the design, in design order (01 Halftone to 20 Columns).
 *
 * The order is part of the [fallbackCoverIndex] contract: an article's cover is
 * `CoverPattern.entries[fallbackCoverIndex(url)]`, so reordering, adding or removing a pattern
 * changes the covers of existing articles.
 */
enum class CoverPattern {
    Halftone,
    Hatch,
    Ruled,
    CornerArcs,
    Sunburst,
    Checker,
    GridPaper,
    Bauhaus,
    Scallop,
    Awning,
    Stagger,
    Zigzag,
    Arches,
    Crosshatch,
    Quarters,
    Horizon,
    Target,
    Plaid,
    Split,
    Columns,
}

/**
 * Picks the fallback cover for an article without a thumbnail. The same URL always gets the same
 * index in `0 until 20`, on every device and in every app version.
 *
 * The index is the 32-bit FNV-1a hash of the URL's UTF-8 bytes, modulo the number of patterns.
 * The URL is hashed exactly as given, without normalization. `String.hashCode()` is not used
 * because its value is a JVM implementation detail rather than a documented, portable contract.
 */
fun fallbackCoverIndex(articleUrl: String): Int {
    val hash = fnv1a32(articleUrl.encodeToByteArray())
    return (hash % CoverPattern.entries.size.toUInt()).toInt()
}

/** 32-bit FNV-1a: for each byte, xor it into the hash, then multiply by the FNV prime. */
internal fun fnv1a32(bytes: ByteArray): UInt {
    var hash = FNV_OFFSET_BASIS
    for (byte in bytes) {
        hash = (hash xor byte.toUByte().toUInt()) * FNV_PRIME
    }
    return hash
}

private const val FNV_OFFSET_BASIS: UInt = 0x811C9DC5u
private const val FNV_PRIME: UInt = 0x01000193u
