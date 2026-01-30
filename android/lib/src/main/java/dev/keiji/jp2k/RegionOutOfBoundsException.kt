package dev.keiji.jp2k

/**
 * Exception thrown when the requested decoding region is out of the image bounds.
 */
class RegionOutOfBoundsException(message: String? = null) : IllegalArgumentException(message)
