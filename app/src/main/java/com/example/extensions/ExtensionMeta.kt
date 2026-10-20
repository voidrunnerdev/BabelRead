package com.example.extensions

data class ExtensionMeta(
    val pkgName: String,
    val name: String,
    val versionName: String?,
    val versionCode: Long,
    val language: String,
    val isNsfw: Boolean,
    val apkPath: String,
) {
    val displayLanguage: String get() = language.uppercase()
}
