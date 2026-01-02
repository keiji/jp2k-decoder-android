package dev.keiji.jp2k

/**
 * Interface for receiving asynchronous results.
 *
 * @param T The type of the result.
 */
interface Callback<T> {
    /**
     * Called when the operation completes successfully.
     *
     * @param result The result of the operation.
     */
    fun onSuccess(result: T)

    /**
     * Called when the operation fails.
     *
     * @param error The exception that occurred.
     */
    fun onError(error: Exception)
}
