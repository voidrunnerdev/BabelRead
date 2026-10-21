package com.example

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.example.data.net.WebViewCookieJar
import com.example.extensions.ExtensionManager
import com.example.i18n.UiTranslations
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.StringFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory

class BabelReadApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        registerInjektServices()
        UiTranslations.load(this)
        ExtensionManager.init(this)
    }

    @Suppress("DEPRECATION")
    private fun registerInjektServices() {
        val app = this
        val networkHelper = NetworkHelper(app)
        Injekt.importModule(object : InjektModule {
            override fun InjektRegistrar.registerInjectables() {
                addSingleton<Application>(app)
                addSingletonFactory<Context> { app }
                addSingletonFactory<NetworkHelper> { networkHelper }
                addSingletonFactory<OkHttpClient> { networkHelper.client }
                addSingletonFactory<CookieJar> { WebViewCookieJar }
                addSingletonFactory<SharedPreferences> {
                    PreferenceManager.getDefaultSharedPreferences(app)
                }
                val json = Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                }
                addSingletonFactory<Json> { json }
                addSingletonFactory<StringFormat> { json }
                addSingletonFactory<SerialFormat> { json }
                addSingletonFactory<ProtoBuf> { ProtoBuf { encodeDefaults = true } }
            }
        })
    }
}
