package dev.cactuspilot.ai

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import dev.cactuspilot.widget.WidgetScheduler
import dev.cactuspilot.widget.WidgetStore
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** A device action the LLM can request via function calling. */
interface Tool {
    val name: String
    val description: String
    val parameters: JSONObject
    /** Ask the user before running (for side effects the OS does not confirm itself). */
    val confirm: Boolean get() = false
    suspend fun execute(ctx: Context, args: JSONObject): String
}

object ToolRegistry {
    val tools: List<Tool> = listOf(
        SetAlarm, SetTimer, OpenApp, WebSearch, OpenUrl, ComposeSms, Dial, Flashlight,
        GetDeviceStatus, ConfigureWidget,
    )

    fun find(name: String): Tool? = tools.firstOrNull { it.name == name }

    fun toolsJson(): JSONArray {
        val arr = JSONArray()
        for (t in tools) {
            arr.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", t.name)
                            .put("description", t.description)
                            .put("parameters", t.parameters),
                    ),
            )
        }
        return arr
    }
}

/**
 * Small models answer ordinary questions worse when ten tool definitions are stuffed into every prompt,
 * so tools are only offered when the message looks like a request for a device action.
 */
object ToolRouter {
    private val ACTION = Regex(
        "\\b(alarm|timer|remind(er)?|wake me|call|dial|phone|sms|whatsapp|send (a |an )?(text|message)|open|launch|" +
            "flashlight|torch|battery|what time|what's the time|what day|today's date|widget|google|browse|website|url|link)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun wanted(text: String): Boolean = ACTION.containsMatchIn(text)
}

// ---- schema + argument helpers -------------------------------------------------------------

internal fun schema(required: List<String> = emptyList(), vararg props: Pair<String, JSONObject>): JSONObject {
    val p = JSONObject()
    for ((k, v) in props) p.put(k, v)
    return JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(required))
}

internal fun strProp(desc: String) = JSONObject().put("type", "string").put("description", desc)
internal fun intProp(desc: String) = JSONObject().put("type", "integer").put("description", desc)
internal fun boolProp(desc: String) = JSONObject().put("type", "boolean").put("description", desc)

/** Small models sometimes emit numbers as strings; accept both. */
internal fun JSONObject.intArg(name: String): Int? = when (val v = opt(name)) {
    is Number -> v.toInt()
    is String -> v.trim().toIntOrNull()
    else -> null
}

internal fun JSONObject.boolArg(name: String): Boolean? = when (val v = opt(name)) {
    is Boolean -> v
    is String -> when (v.trim().lowercase()) {
        "true", "on", "yes", "1" -> true
        "false", "off", "no", "0" -> false
        else -> null
    }
    else -> null
}

internal fun JSONObject.strArg(name: String): String? =
    opt(name)?.takeIf { it != JSONObject.NULL }?.toString()?.trim()?.takeIf { it.isNotEmpty() }

private fun Context.launch(intent: Intent) {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun need(cond: Boolean, msg: String) {
    if (!cond) throw IllegalArgumentException(msg)
}

// ---- tools ---------------------------------------------------------------------------------

object SetAlarm : Tool {
    override val name = "set_alarm"
    override val description = "Set an alarm at a time of day (24-hour clock)."
    override val parameters = schema(
        listOf("hour", "minute"),
        "hour" to intProp("Hour 0-23"),
        "minute" to intProp("Minute 0-59"),
        "label" to strProp("Optional alarm label"),
    )
    override val confirm = true
    override suspend fun execute(ctx: Context, args: JSONObject): String {
        val h = args.intArg("hour"); val m = args.intArg("minute")
        need(h != null && h in 0..23, "hour must be 0-23")
        need(m != null && m in 0..59, "minute must be 0-59")
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h!!)
            .putExtra(AlarmClock.EXTRA_MINUTES, m!!)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args.strArg("label")?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        ctx.launch(i)
        return "Alarm set for %02d:%02d".format(h, m)
    }
}

object SetTimer : Tool {
    override val name = "set_timer"
    override val description = "Start a countdown timer."
    override val parameters = schema(
        listOf("seconds"),
        "seconds" to intProp("Duration in seconds"),
        "label" to strProp("Optional timer label"),
    )
    override suspend fun execute(ctx: Context, args: JSONObject): String {
        val s = args.intArg("seconds")
        need(s != null && s in 1..86_400, "seconds must be 1-86400")
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s!!)
        args.strArg("label")?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        ctx.launch(i)
        return "Timer started for $s seconds"
    }
}

object OpenApp : Tool {
    override val name = "open_app"
    override val description = "Open an installed app by its name."
    override val parameters = schema(listOf("name"), "name" to strProp("App name, e.g. Camera"))
    override suspend fun execute(ctx: Context, args: JSONObject): String {
        val wanted = args.strArg("name")
        need(wanted != null, "name is required")
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(launcher, 0)
            .map { it to it.loadLabel(pm).toString() }
            .sortedBy { it.second.length }
            .firstOrNull { it.second.contains(wanted!!, ignoreCase = true) }
            ?: return "No installed app matches \"$wanted\""
        val intent = pm.getLaunchIntentForPackage(match.first.activityInfo.packageName)
            ?: return "Cannot launch ${match.second}"
        ctx.launch(intent)
        return "Opened ${match.second}"
    }
}

object WebSearch : Tool {
    override val name = "web_search"
    override val description = "Search the web in the user's browser."
    override val parameters = schema(listOf("query"), "query" to strProp("Search query"))
    override suspend fun execute(ctx: Context, args: JSONObject): String {
        val q = args.strArg("query")
        need(q != null, "query is required")
        ctx.launch(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", q))
        return "Searching for \"$q\""
    }
}

object OpenUrl : Tool {
    override val name = "open_url"
    override val description = "Open a web page (http or https)."
    override val parameters = schema(listOf("url"), "url" to strProp("Full URL starting with https://"))
    override val confirm = true
    override suspend fun execute(ctx: Context, args: JSONObject): String {
        val u = args.strArg("url")
        need(u != null && (u!!.startsWith("https://") || u.startsWith("http://")), "url must start with http(s)://")
        ctx.launch(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        return "Opened $u"
    }
}

object ComposeSms : Tool {
    override val name = "compose_sms"
    override val description = "Open the SMS composer with a prefilled message. The user presses send."
    override val parameters = schema(
        listOf("number", "message"),
        "number" to strProp("Phone number"),
        "message" to strProp("Message text"),
    )
    override suspend fun execute(ctx: Context, args: JSONObject): String {
        val n = args.strArg("number"); val m = args.strArg("message")
        need(n != null && n!!.any { it.isDigit() }, "a valid number is required")
        need(m != null, "message is required")
        ctx.launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(n)}")).putExtra("sms_body", m))
        return "SMS composer opened for $n (not sent)"
    }
}

object Dial : Tool {
    override val name = "dial"
    override val description = "Open the phone dialer with a number. Does not place the call."
    override val parameters = schema(listOf("number"), "number" to strProp("Phone number"))
    override suspend fun execute(ctx: Context, args: JSONObject): String {
        val n = args.strArg("number")
        need(n != null && n!!.any { it.isDigit() }, "a valid number is required")
        ctx.launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(n)}")))
        return "Dialer opened for $n"
    }
}

object Flashlight : Tool {
    override val name = "flashlight"
    override val description = "Turn the flashlight on or off."
    override val parameters = schema(listOf("on"), "on" to boolProp("true to turn on, false to turn off"))
    override suspend fun execute(ctx: Context, args: JSONObject): String {
        val on = args.boolArg("on")
        need(on != null, "on must be true or false")
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "This device has no flashlight"
        cm.setTorchMode(id, on!!)
        return if (on) "Flashlight on" else "Flashlight off"
    }
}

object GetDeviceStatus : Tool {
    override val name = "get_device_status"
    override val description = "Get the current local date/time and battery level."
    override val parameters = schema()
    override suspend fun execute(ctx: Context, args: JSONObject): String {
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0) level * 100 / scale else -1
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, d MMM yyyy HH:mm"))
        return "Time: $now. Battery: ${if (pct >= 0) "$pct%" else "unknown"}${if (charging) " (charging)" else ""}."
    }
}

object ConfigureWidget : Tool {
    override val name = "configure_widget"
    override val description = "Set the prompt shown by the home-screen AI widget and how often it refreshes."
    override val parameters = schema(
        listOf("prompt"),
        "prompt" to strProp("What the widget should answer, e.g. 'Give me a fun fact'"),
        "refresh_minutes" to intProp("Refresh interval in minutes, at least 15. Omit for manual refresh only."),
    )
    override val confirm = true
    override suspend fun execute(ctx: Context, args: JSONObject): String {
        val p = args.strArg("prompt")
        need(p != null, "prompt is required")
        val minutes = args.intArg("refresh_minutes") ?: 0
        val widgetId = WidgetStore.lastConfigured(ctx)
        if (widgetId == 0) return "No CactusPilot widget found. Add one to the home screen first, then ask again."
        WidgetStore.savePrompt(ctx, widgetId, p!!, minutes)
        WidgetScheduler.apply(ctx, widgetId, minutes)
        return "Widget prompt updated" + if (minutes > 0) ", refreshing every ${minutes.coerceAtLeast(15)} min" else ""
    }
}
