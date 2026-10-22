package dev.cactuspilot

import dev.cactuspilot.ai.AgentLoop
import dev.cactuspilot.ai.ChatTurn
import dev.cactuspilot.ai.EngineException
import dev.cactuspilot.ai.EngineJson
import dev.cactuspilot.ai.FunctionCall
import dev.cactuspilot.ai.ModelCatalog
import dev.cactuspilot.ai.ModelStore
import dev.cactuspilot.ai.ToolRegistry
import dev.cactuspilot.ai.ToolRouter
import dev.cactuspilot.ai.WebContext
import dev.cactuspilot.ui.ConversationStore
import dev.cactuspilot.ui.Kind
import dev.cactuspilot.ui.MarkdownParser
import dev.cactuspilot.ui.UiMessage
import dev.cactuspilot.ui.MdBlock
import dev.cactuspilot.update.UpdateManager
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

    @Test fun oldTurnsAreDroppedWhenOverBudgetButLatestUserTurnStays() {
        val turns = (1..39).map { ChatTurn(if (it % 2 == 1) "user" else "assistant", "message number $it ".repeat(20)) }
        val msgs = AgentLoop.buildMessages(turns, "sys", budgetTokens = 600)
        assertTrue(msgs.length() < 40)
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertEquals("user", msgs.getJSONObject(1).getString("role"))
        assertEquals("user", msgs.getJSONObject(msgs.length() - 1).getString("role"))
        assertTrue(msgs.getJSONObject(msgs.length() - 1).getString("content").contains("message number 39"))
    }

    @Test fun wholeConversationIsKeptWhenItFits() {
        val turns = listOf(
            ChatTurn("user", "Who wrote Dune?"),
            ChatTurn("assistant", "Frank Herbert."),
            ChatTurn("user", "When was it published?"),
        )
        val msgs = AgentLoop.buildMessages(turns, "sys", budgetTokens = 8000)
        assertEquals(4, msgs.length())
        assertEquals("Who wrote Dune?", msgs.getJSONObject(1).getString("content"))
    }

    @Test fun onlyTheLatestImageStaysAttached() {
        val turns = listOf(
            ChatTurn("user", "what is this", "/a.jpg"),
            ChatTurn("assistant", "a cat"),
            ChatTurn("user", "and this", "/b.jpg"),
            ChatTurn("assistant", "a dog"),
            ChatTurn("user", "which is bigger?"),
        )
        val msgs = AgentLoop.buildMessages(turns, "sys", budgetTokens = 8000)
        assertTrue(!msgs.getJSONObject(1).has("images"))
        assertEquals("/b.jpg", msgs.getJSONObject(3).getJSONArray("images").getString(0))
    }

    @Test fun webContextOnlyGoesIntoLatestUserTurn() {
        val turns = listOf(ChatTurn("user", "first"), ChatTurn("assistant", "ok"), ChatTurn("user", "latest?"))
        val msgs = AgentLoop.buildMessages(turns, "sys", 8000, webContext = "WEB-EXCERPT")
        assertTrue(!msgs.getJSONObject(1).getString("content").contains("WEB-EXCERPT"))
        assertTrue(msgs.getJSONObject(3).getString("content").contains("WEB-EXCERPT"))
        assertTrue(msgs.getJSONObject(3).getString("content").endsWith("latest?"))
    }

    @Test fun systemPromptHasDateAndToolsHintOnlyWhenNeeded() {
        val now = java.time.LocalDateTime.of(2026, 10, 22, 9, 30)
        val plain = AgentLoop.systemPrompt(false, now)
        assertTrue(plain.contains("22 October 2026"))
        assertTrue(plain.contains("09:30"))
        assertTrue(!plain.contains("call the matching tool"))
        assertTrue(AgentLoop.systemPrompt(true, now).contains("call the matching tool"))
    }

    @Test fun toolsAreOnlyOfferedForDeviceActions() {
        assertTrue(ToolRouter.wanted("Set a timer for 5 minutes"))
        assertTrue(ToolRouter.wanted("open the camera"))
        assertTrue(ToolRouter.wanted("what time is it"))
        assertTrue(!ToolRouter.wanted("Explain how photosynthesis works"))
        assertTrue(!ToolRouter.wanted("Who wrote Dune?"))
    }

    @Test fun wikipediaResponseIsParsedInRankOrder() {
        val json = """{"query":{"pages":{
            "2":{"title":"B","index":2,"extract":"second"},
            "1":{"title":"A","index":1,"extract":"first"},
            "3":{"title":"C","index":3,"extract":""}}}}"""
        val hits = WebContext.parse(json)
        assertEquals(listOf("A", "B"), hits.map { it.title })
        assertEquals("" , WebContext.parse("""{"batchcomplete":""}""").firstOrNull()?.title ?: "")
        assertTrue(WebContext.format(hits, "2026-10-22").contains("[1] A: first"))
    }

    @Test fun questionsAreTurnedIntoSearchPhrases() {
        assertEquals("capital of France", WebContext.toQuery("What is the capital of France?"))
        assertEquals("quantum computing", WebContext.toQuery("Tell me about quantum computing"))
    }

    @Test fun markdownBlocksAreParsed() {
        val md = """
            # Title
            Some **bold** text
            continues here.

            - one
            - two
            1. first

            ```kotlin
            val x = 1
            ```
            > quoted
            ---
        """.trimIndent()
        val blocks = MarkdownParser.parse(md)
        assertEquals(MdBlock.Heading(1, "Title"), blocks[0])
        assertEquals(MdBlock.Paragraph("Some **bold** text\ncontinues here."), blocks[1])
        assertEquals(MdBlock.ListItem("one", false, 0, 0), blocks[2])
        assertEquals(MdBlock.ListItem("first", true, 1, 0), blocks[4])
        assertEquals(MdBlock.Code("kotlin", "val x = 1"), blocks[5])
        assertEquals(MdBlock.Quote("quoted"), blocks[6])
        assertEquals(MdBlock.Rule, blocks[7])
    }

    @Test fun unterminatedCodeFenceWhileStreamingIsStillCode() {
        val blocks = MarkdownParser.parse("Here:\n```python\nprint(1)")
        assertEquals(MdBlock.Code("python", "print(1)"), blocks.last())
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

    private fun release(tag: String, apk: Boolean = true, draft: Boolean = false) = """
        {"tag_name":"$tag","name":"CactusPilot $tag","draft":$draft,"body":"notes",
         "assets":[${if (apk) """{"name":"CactusPilot.apk","size":10,"browser_download_url":"https://x/$tag.apk"}""" else ""}]}"""

    @Test fun picksNewestBuildAboveCurrent() {
        val json = "[" + listOf(release("build-4"), release("build-7"), release("build-5")).joinToString(",") + "]"
        val info = UpdateManager.pickLatest(json, currentBuild = 4)
        assertEquals(7, info!!.build)
        assertEquals("https://x/build-7.apk", info.apkUrl)
    }

    @Test fun noUpdateWhenCurrentIsNewestOrIgnoresDraftsAndNonApk() {
        val json = "[" + listOf(release("build-9", draft = true), release("build-8", apk = false), release("v1.0"), release("build-3")).joinToString(",") + "]"
        assertEquals(null, UpdateManager.pickLatest(json, currentBuild = 3))
    }

    @Test fun conversationTitleComesFromFirstLine() {
        assertEquals("Explain black holes", ConversationStore.titleFrom("  Explain black holes\nin detail"))
        assertEquals(ConversationStore.DEFAULT_TITLE, ConversationStore.titleFrom("   "))
        val long = ConversationStore.titleFrom("x".repeat(100))
        assertEquals(41, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test fun conversationMessagesRoundTrip() {
        val msgs = listOf(
            UiMessage(1, "user", "hello"),
            UiMessage(2, "assistant", "**hi**", stats = "10 tok/s"),
            UiMessage(3, "tool", "⚙ set_timer", kind = Kind.TOOL),
            UiMessage(4, "assistant", ""), // empty placeholders are not stored
        )
        val back = ConversationStore.decode(ConversationStore.encode(msgs))
        assertEquals(3, back.size)
        assertEquals("**hi**", back[1].text)
        assertEquals("10 tok/s", back[1].stats)
        assertEquals(Kind.TOOL, back[2].kind)
    }

    @Test fun streamResultIsParsed() {
        val s = EngineJson.parseStream("""{"success":true,"confirmed":"hello ","pending":"wor","total_time_ms":3}""")
        assertEquals("hello ", s.confirmed)
        assertEquals("wor", s.pending)
    }
}
