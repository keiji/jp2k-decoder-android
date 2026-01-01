package dev.keiji.jp2k

data class Config(
    val maxPixels: Int = DEFAULT_MAX_PIXELS,
    val maxHeapSizeBytes: Long = DEFAULT_MAX_HEAP_SIZE_BYTES,
    val maxEvaluationReturnSizeBytes: Int = DEFAULT_MAX_EVALUATION_RETURN_SIZE_BYTES,
    val logLevel: Int? = null
)
