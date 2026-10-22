package dev.cactuspilot

import dev.cactuspilot.ai.AgentLoop
import dev.cactuspilot.ai.ChatTurn
import dev.cactuspilot.ai.EngineException
import dev.cactuspilot.ai.EngineJson
import dev.cactuspilot.ai.FunctionCall
import dev.cactuspilot.ai.ModelCatalog
import dev.cactuspilot.ai.ModelStore
import dev.cactuspilot.ai.ToolRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CoreLogicTest {

    @Test fun parsesCompletionWithFunctionCalls() {
        val raw = """{"success":true,"response":"","function_calls":[{"name":"set_timer","arguments":{"seconds":300}}],
            "time_to_first_token_ms":120.5,"decode_tps":42.0,"total_time_ms":900.0}"""
        val r = EngineJson.parseCompletion(raw)
        assertEquals(1, r.functionCalls.size)
        assertEquals("set_timer", r.functionCalls[0].name)
        assertEquals(300, r.functionCalls[0].arguments.getInt("seconds"))
        assertEquals(42.0, r.decodeTps, 0.001)
    }

    @Test fun parsesPlainResponseAndTrailingNulls() {
        val r = EngineJson.parseCompletion("""{"success":true,"response":"hi"}""" + "\u0000\u0000")
        assertEquals("hi", r.text)
        assertTrue(r.functionCalls.isEmpty())
    }

    @Test fun engineErrorBecomesException() {
        try {
            EngineJson.parseCompletion("""{"success":false,"error":"boom","response":null}""")
            fail("expected EngineException")
        } catch (e: EngineException) {
            assertEquals("boom", e.message)
        }
    }

    @Test fun imageGoesIntoMessage() {
        val m = EngineJson.message("user", "what is this?", "/tmp/a.jpg")
        assertEquals("/tmp/a.jpg", m.getJSONArray("images").getString(0))
    }

    @Test fun historyIsTrimmedAndStartsWithSystem() {
        val turns = (1..30).map { ChatTurn(if (it % 2 == 1) "user" else "assistant", "m$it") }
        val msgs = AgentLoop.buildMessages(turns)
        assertEquals(AgentLoop.MAX_HISTORY_TURNS + 1, msgs.length())
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
    }

    @Test fun rendersToolCallsInTemplateForm() {
        val s = AgentLoop.renderCalls(listOf(FunctionCall("set_timer", JSONObject().put("seconds", 60))))
        assertEquals("<|tool_call_start|>set_timer(seconds=60)<|tool_call_end|>", s)
    }

    @Test fun toolSchemasAreWellFormed() {
        val arr = ToolRegistry.toolsJson()
        assertEquals(ToolRegistry.tools.size, arr.length())
        for (i in 0 until arr.length()) {
            val fn = arr.getJSONObject(i).getJSONObject("function")
            assertTrue(fn.getString("name").isNotEmpty())
            assertEquals("object", fn.getJSONObject("parameters").getString("type"))
        }
        assertEquals(ToolRegistry.tools.size, ToolRegistry.tools.map { it.name }.toSet().size)
    }

    @Test fun catalogHasDefaultsForEachRole() {
        for (role in dev.cactuspilot.ai.ModelRole.entries) assertTrue(ModelCatalog.forRole(role).isNotEmpty())
        assertTrue(ModelCatalog.all.all { it.url.startsWith("https://huggingface.co/Cactus-Compute/") })
        assertEquals(
            "https://huggingface.co/Cactus-Compute/LFM2-VL-450M/resolve/v2.0/lfm2-vl-450m-cq4.zip",
            ModelCatalog.defaultFor(dev.cactuspilot.ai.ModelRole.CHAT).url,
        )
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.id }.toSet().size)
    }

    @Test fun bundleValidationRequiresManifest() {
        val dir = File.createTempFile("bundle2", "").apply { delete(); mkdirs() }
        File(dir, "config.txt").writeText("x")
        try {
            ModelStore.validateBundle(dir)
            fail("expected IOException")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("components/manifest.json"))
        }
        File(dir, "components").mkdirs()
        File(dir, "components/manifest.json").writeText("{}")
        ModelStore.validateBundle(dir)
    }

    @Test fun unzipBlocksZipSlip() {
        val dir = File.createTempFile("zip", "").apply { delete(); mkdirs() }
        val zip = File(dir, "evil.zip")
        ZipOutputStream(zip.outputStream()).use {
            it.putNextEntry(ZipEntry("../escape.txt")); it.write(1); it.closeEntry()
        }
        try {
            ModelStore.unzipSafely(zip, File(dir, "out").apply { mkdirs() })
            fail("expected IOException")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("unsafe"))
        }
    }

    @Test fun bundleDirDescendsIntoSingleWrapper() {
        val dir = File.createTempFile("bundle", "").apply { delete(); mkdirs() }
        val inner = File(dir, "wrapper").apply { mkdirs() }
        File(inner, "config.txt").writeText("x")
        assertEquals(inner.canonicalPath, ModelStore.resolveBundleDir(dir).canonicalPath)
    }
}
