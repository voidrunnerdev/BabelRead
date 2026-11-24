package com.example.service

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
class ParSeqEngineTest {

    @Test
    fun `decodeParSeq decodes words correctly and stops at eos token without truncating on zero`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = ParSeqEngine(context)

        val vocab = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~°£€¥¢฿àâéèêëîïôùûüçÀÂÉÈÊËÎÏÔÙÛÜÇáãíóõúÁÃÍÓÕÚñÑ¡¿äößÄÖẞčďěňřšťůýžČĎĚŇŘŠŤŮÝŽąćęłńśźżĄĆĘŁŃŚŹŻìòÌÒæøåÆØÅ§"
        val eosIndex = vocab.length // 118
        val classCount = vocab.length + 3 // 121

        // Target word to encode: "100% COOL!"
        // Notice it contains '0' at index 0 in vocab!
        val targetText = "100% COOL!"
        val timesteps = targetText.length + 3 // target + EOS + padding

        val buffer = ByteBuffer.allocateDirect(timesteps * classCount * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        for (t in 0 until timesteps) {
            val base = t * classCount
            val targetChar = if (t < targetText.length) targetText[t] else null
            val targetIndex = when {
                targetChar != null -> vocab.indexOf(targetChar)
                t == targetText.length -> eosIndex // EOS
                else -> eosIndex + 2 // PAD
            }
            for (c in 0 until classCount) {
                buffer.put(base + c, if (c == targetIndex) 8.0f else -8.0f)
            }
        }
        buffer.position(0)

        val result = engine.decodeParSeq(buffer, timesteps, classCount, vocab)

        assertEquals("Decoded text should match target exactly without truncating at '0'", targetText, result.text)
        assertTrue("Confidence should be very high for strong logits", result.confidence > 0.95f)
    }
}
