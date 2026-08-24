package com.sahidcode404.camx.ui.components

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceBinding
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceIdentity
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceIdentityAllocator

@Composable
fun StableSurfaceView(
    modifier: Modifier = Modifier,
    onSurfaceAvailable: (PreviewSurfaceBinding) -> Unit,
    onSurfaceDestroyed: (PreviewSurfaceIdentity) -> Unit,
) {
    val context = LocalContext.current
    val view = remember(context) { SurfaceView(context) }
    val currentAvailable by rememberUpdatedState(onSurfaceAvailable)
    val currentDestroyed by rememberUpdatedState(onSurfaceDestroyed)
    val callback = remember(view) {
        object : SurfaceHolder.Callback {
            private var activeIdentity: PreviewSurfaceIdentity? = null

            override fun surfaceCreated(holder: SurfaceHolder) {
                ensureIdentity()
                publishCurrent(holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                ensureIdentity()
                if (holder.surface.isValid && width > 0 && height > 0) {
                    currentAvailable(
                        PreviewSurfaceBinding(
                            surface = holder.surface,
                            viewSize = IntSize(width, height),
                            identity = checkNotNull(activeIdentity),
                        ),
                    )
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                destroyCurrent()
            }

            fun destroyCurrent() {
                val destroyedIdentity = activeIdentity ?: return
                activeIdentity = null
                currentDestroyed(destroyedIdentity)
            }

            fun publishCurrent(holder: SurfaceHolder) {
                ensureIdentity()
                val frame = holder.surfaceFrame
                if (holder.surface.isValid && frame.width() > 0 && frame.height() > 0) {
                    currentAvailable(
                        PreviewSurfaceBinding(
                            surface = holder.surface,
                            viewSize = IntSize(frame.width(), frame.height()),
                            identity = checkNotNull(activeIdentity),
                        ),
                    )
                }
            }

            private fun ensureIdentity() {
                if (activeIdentity != null) return
                activeIdentity = PreviewSurfaceIdentityAllocator.next()
            }
        }
    }

    DisposableEffect(view, callback) {
        view.holder.addCallback(callback)
        if (view.holder.surface.isValid) callback.publishCurrent(view.holder)
        onDispose {
            callback.destroyCurrent()
            view.holder.removeCallback(callback)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { view },
        update = { it.setZOrderOnTop(false) },
    )
}
