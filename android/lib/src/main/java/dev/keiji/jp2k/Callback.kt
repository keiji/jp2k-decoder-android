package dev.keiji.jp2k

interface Callback<T> {
    fun onSuccess(result: T)
    fun onError(error: Exception)
}
