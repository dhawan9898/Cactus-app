package dev.cactuspilot.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.cactuspilot.ui.MainActivity
import java.text.DateFormat
import java.util.Date

/** Key name matches AppWidgetManager.EXTRA_APPWIDGET_ID so the same extra works for both widget buttons and the system configure flow. */
internal val WIDGET_ID_KEY = ActionParameters.Key<Int>(AppWidgetManager.EXTRA_APPWIDGET_ID)

class AskWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val snap = WidgetStore.read(context, widgetId)
        provideContent { WidgetContent(snap, widgetId) }
    }
}

@Composable
private fun WidgetContent(snap: WidgetSnapshot, widgetId: Int) {
    val body = snap.answer.ifBlank { "Tap Refresh to ask: ${snap.prompt}" }
    val stamp = if (snap.updatedAt > 0) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(snap.updatedAt))
    } else {
        ""
    }
    val params = actionParametersOf(WIDGET_ID_KEY to widgetId)
    GlanceTheme {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Text(
                text = "🌵 ${snap.prompt.take(28)}  $stamp",
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(GlanceModifier.height(6.dp))
            Text(
                text = body,
                maxLines = 5,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
                modifier = GlanceModifier.fillMaxWidth(),
            )
            Spacer(GlanceModifier.height(8.dp))
            Row {
                Button(text = "Refresh", onClick = actionRunCallback<RefreshCallback>(params))
                Spacer(GlanceModifier.width(8.dp))
                Button(text = "Edit", onClick = actionStartActivity<WidgetConfigActivity>(params))
            }
        }
    }
}

class RefreshCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetScheduler.refreshNow(context, parameters[WIDGET_ID_KEY] ?: 0)
    }
}

class AskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AskWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach {
            WidgetScheduler.cancel(context, it)
            WidgetStore.remove(context, it)
        }
    }
}
