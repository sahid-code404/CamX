package com.sahidcode404.camx.core.camera.diagnostics

data class NativeCoreSnapshot(
    val schema: Long,
    val androidApi: Long,
    val pointerBits: Long,
    val counters: LongArray,
)

object NativeCore {
    private val loaded = runCatching { System.loadLibrary("camx_core") }.isSuccess

    fun snapshotOrNull(): NativeCoreSnapshot? {
        if (!loaded) return null
        val values = nativeSnapshot()
        if (values.size < HEADER_SIZE) return null
        return NativeCoreSnapshot(
            schema = values[0],
            androidApi = values[1],
            pointerBits = values[2],
            counters = values.copyOfRange(HEADER_SIZE, values.size),
        )
    }

    private external fun nativeSnapshot(): LongArray

    private const val HEADER_SIZE = 3
}
