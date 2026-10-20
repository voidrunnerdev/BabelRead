package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.example.extensions.ExtensionIcons

@Composable
fun rememberExtensionIcon(apkPath: String?): ImageBitmap? {
    if (apkPath == null) return null
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = ExtensionIcons.peek(apkPath), key1 = apkPath) {
        value = ExtensionIcons.load(context, apkPath)
    }
    return icon
}
