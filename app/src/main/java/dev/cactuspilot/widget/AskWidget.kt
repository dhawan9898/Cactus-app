package dev.cactuspilot.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.actionStartActivity
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

class AskWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = WidgetStore.read(context)
        provideContent { WidgetContent(snap) }
    }
}

@Composable
private fun WidgetContent(snap: WidgetSnapshot) {
    val body = snap.answer.ifBlank { "Tap Refresh to ask: ${snap.prompt}" }
    val stamp = if (snap.updatedAt > 0) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(snap.updatedAt))
    } else {
        ""
    }
    GlanceTheme {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Text(
                text = "🌵 CactusPilot  $stamp",
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
                Button(text = "Refresh", onClick = actionRunCallback<RefreshCallback>())
                Spacer(GlanceModifier.width(8.dp))
                Button(text = "Ask", onClick = actionStartActivity<MainActivity>())
            }
        }
    }
}

class RefreshCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetScheduler.refreshNow(context)
    }
}

class AskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AskWidget()
}
