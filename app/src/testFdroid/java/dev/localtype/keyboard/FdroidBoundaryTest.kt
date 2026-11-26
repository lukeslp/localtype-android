package dev.localtype.keyboard

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FdroidBoundaryTest {
    @Test fun `catalog build is its own app, never the full distribution's package`() {
        // A repository client updates by package and signer. Sharing the full build's package would let a
        // client swap one build for the other; docs/catalog-application-id-2026-11-10.md has the reasons.
        assertEquals("dev.localtype.keyboard.fdroid", BuildConfig.APPLICATION_ID)
    }

    @Test fun `catalog build does not ask the full distribution for updates`() {
        assertFalse(BuildConfig.INCLUDES_NANO)
        assertFalse(BuildConfig.DIRECT_UPDATES)
        assertNull(UpdateChecker.latest())
    }

    @Test fun `catalog build excludes optional Nano and Gemma runtimes`() {
        assertFalse(BuildConfig.INCLUDES_GEMMA)
        // modelkit (Gemma's engine and download) and LiteRT-LM are full-distribution dependencies only.
        for (name in listOf("dev.localtype.modelkit.GemmaEngine", "com.google.ai.edge.litertlm.Engine",
            "dev.localtype.keyboard.ModelInferenceService", "dev.localtype.keyboard.LiteRtGemmaCompletionProvider")) {
            assertTrue(name, runCatching { Class.forName(name) }.isFailure)
        }
        val context = android.content.ContextWrapper(null)
        assertTrue(FoundationModelProviderFactory.chatProviders(context).isEmpty())
        assertEquals(FoundationModelStatus.NOT_SUPPORTED, runBlocking { FoundationModelProviderFactory.create(context).status() })
        assertFalse(ModelKitBridge.sharingOffered(99))
    }

    @Test fun `catalog build names no Nano, Gemma or Proofread in setup and the tools row summary`() {
        // Setup's last step drops "Ask Nano/Gemma only when I tap", and Reset the tools row lists only what the row shows.
        assertFalse(SetupFlow.offersModelOnTap())
        assertFalse(StripTools.buildHasModels)
        assertFalse(StripTool.PROOFREAD in StripTools.described(null))
        assertEquals(StripTools.ROW_ORDER - StripTool.PROOFREAD, StripTools.described(null))
    }
}
