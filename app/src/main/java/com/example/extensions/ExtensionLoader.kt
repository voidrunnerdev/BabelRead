package com.example.extensions

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import java.io.File
import java.lang.reflect.InvocationTargetException

internal class ExtensionLoader(private val context: Context) {

    data class LoadedExtension(
        val sources: List<CatalogueSource>,
        val meta: ExtensionMeta,
    )

    companion object {
        const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
        const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
        const val METADATA_NSFW = "tachiyomi.extension.nsfw"
        const val METADATA_FORCE_NAME = "tachiyomix.name"
        const val EXTENSION_FEATURE = "tachiyomi.extension"
    }

    @Suppress("DEPRECATION")
    fun load(apk: File): LoadedExtension {
        val pm = context.packageManager
        val pkgInfo = pm.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA,
        ) ?: throw IllegalStateException("APK okunamadı: ${apk.name}")

        val features = pkgInfo.reqFeatures
        if (features == null || features.none { it.name == EXTENSION_FEATURE }) {
            throw IllegalStateException("Bu APK bir Mihon eklentisi değil")
        }

        val appInfo = pkgInfo.applicationInfo
            ?: throw IllegalStateException("APK uygulama bilgisi okunamadı")
        val metaData = appInfo.metaData ?: Bundle()

        val factoryName = metaData.getString(METADATA_SOURCE_FACTORY)
        val classNames = metaData.getString(METADATA_SOURCE_CLASS)
            ?.split(";")
            ?.filter { it.isNotBlank() }
            ?: factoryName?.let { listOf(it) }
            ?: throw IllegalStateException("tachiyomi.extension.class meta verisi bulunamadı")

        // Android 14+ refuses to load writable dex files ("Writable dex file ... is not allowed")
        if (apk.canWrite()) {
            apk.setReadOnly()
        }

        val classLoader = createExtensionClassLoader(apk.absolutePath, null, context.classLoader)

        val sources = mutableListOf<CatalogueSource>()
        for (rawName in classNames) {
            val className = if (rawName.startsWith(".")) appInfo.packageName + rawName else rawName
            val clazz = Class.forName(className, false, classLoader)
            val instance = try {
                clazz.getDeclaredConstructor().newInstance()
            } catch (e: InvocationTargetException) {
                val cause = e.cause ?: e
                throw IllegalStateException(
                    "Başlatılamadı (${cause.javaClass.name}): ${cause.message ?: "mesaj yok"}",
                    cause,
                )
            }
            when (instance) {
                is SourceFactory -> sources += instance.createSources().filterIsInstance<CatalogueSource>()
                is Source -> (instance as? CatalogueSource)?.let { sources += it }
            }
        }
        if (sources.isEmpty()) {
            throw IllegalStateException("Çalıştırılabilir kaynak bulunamadı")
        }

        val nsfwValue = metaData.get(METADATA_NSFW)
        val isNsfw = nsfwValue == true ||
            (nsfwValue is Int && nsfwValue == 1) ||
            nsfwValue?.toString()?.equals("1") == true ||
            nsfwValue?.toString()?.equals("true", ignoreCase = true) == true

        val name = metaData.getString(METADATA_FORCE_NAME)
            ?: pkgInfo.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }

        val versionCode = if (Build.VERSION.SDK_INT >= 28) {
            pkgInfo.longVersionCode
        } else {
            pkgInfo.versionCode.toLong()
        }

        val meta = ExtensionMeta(
            pkgName = pkgInfo.packageName,
            name = name,
            versionName = pkgInfo.versionName,
            versionCode = versionCode,
            language = sources.first().lang,
            isNsfw = isNsfw,
            apkPath = apk.absolutePath,
        )
        return LoadedExtension(sources, meta)
    }
}
