package com.example.extensions

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ExtensionIcons {

    private val cache = LruCache<String, ImageBitmap>(64)

    fun peek(apkPath: String): ImageBitmap? = cache.get(apkPath)

    suspend fun load(context: Context, apkPath: String): ImageBitmap? = withContext(Dispatchers.IO) {
        cache.get(apkPath)?.let { return@withContext it }
        runCatching {
            val pm = context.packageManager
            val pkgInfo = pm.getPackageArchiveInfo(apkPath, 0) ?: return@withContext null
            val appInfo = pkgInfo.applicationInfo ?: return@withContext null
            appInfo.sourceDir = apkPath
            appInfo.publicSourceDir = apkPath
            val drawable = appInfo.loadIcon(pm) ?: return@withContext null
            val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                drawable.bitmap
            } else {
                val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
                val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { target ->
                    val canvas = Canvas(target)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                }
            }
            bitmap.asImageBitmap().also { cache.put(apkPath, it) }
        }.getOrNull()
    }
}
