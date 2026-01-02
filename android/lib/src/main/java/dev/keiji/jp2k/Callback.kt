package dev.keiji.jp2k

/**
 * Interface definition for a callback to be invoked when an asynchronous operation finishes.
 *
 * @param T The type of the result returned on success.
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
     * @param error The exception describing the error.
     */
    fun onError(error: Exception)
}
