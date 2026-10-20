package eu.kanade.tachiyomi

import com.example.BuildConfig

@Suppress("unused")
object AppInfo {
    fun getVersionCode(): Int = BuildConfig.VERSION_CODE

    fun getVersionName(): String = BuildConfig.VERSION_NAME
}
