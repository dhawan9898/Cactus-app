package dev.cactuspilot.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.lifecycle.lifecycleScope
import dev.cactuspilot.ui.CactusTheme
import kotlinx.coroutines.launch

/** Lets the user choose what a widget shows and how often it refreshes. Launched when adding a widget and from its Edit button. */
class WidgetConfigActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setResult(Activity.RESULT_CANCELED) // adding the widget is cancelled unless the user saves

        val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val current = WidgetStore.read(this, widgetId)

        setContent {
            CactusTheme {
                var prompt by remember { mutableStateOf(current.prompt) }
                var minutes by remember { mutableIntStateOf(current.refreshMinutes) }
                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing,
                    topBar = { TopAppBar(title = { Text("Widget settings") }) },
                ) { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("What should this widget show?", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Prompt") },
                            minLines = 2,
                        )
                        Text(
                            "Examples: \"Give me a fun fact\", \"Summarize today's date and one thing to do\", \"Quote of the day\".",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("Refresh", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((m, label) in WidgetStore.REFRESH_CHOICES) {
                                FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(label) })
                            }
                        }
                        Text(
                            "Android limits automatic refresh to every 15 minutes or more. The widget uses your active chat model.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = prompt.isNotBlank(),
                                onClick = {
                                    WidgetStore.savePrompt(this@WidgetConfigActivity, widgetId, prompt.trim(), minutes)
                                    WidgetScheduler.apply(this@WidgetConfigActivity, widgetId, minutes)
                                    lifecycleScope.launch {
                                        AskWidget().updateAll(this@WidgetConfigActivity)
                                        setResult(
                                            Activity.RESULT_OK,
                                            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
                                        )
                                        finish()
                                    }
                                },
                            ) { Text("Save") }
                            OutlinedButton(onClick = { finish() }) { Text("Cancel") }
                        }
                    }
                }
            }
        }
    }
}
