package com.example.extensions

import android.content.pm.PackageInfo
import java.security.MessageDigest

object ExtensionSignature {

    const val KEIYOUSHI_SIGNING_KEY = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"

    @Suppress("DEPRECATION")
    fun apkCertificateSha256(pkgInfo: PackageInfo): String? {
        val signature = pkgInfo.signatures?.firstOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun isTrusted(pkgInfo: PackageInfo): Boolean =
        apkCertificateSha256(pkgInfo)?.equals(KEIYOUSHI_SIGNING_KEY, ignoreCase = true) == true
}
