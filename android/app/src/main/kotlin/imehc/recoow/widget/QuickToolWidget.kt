package imehc.recoow.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import imehc.recoow.MainActivity
import imehc.recoow.R
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.preferences.*
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** One independently configured launcher shortcut per widget; no periodic data polling. */
class QuickToolWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = updateAsync(context, ids)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = updateAsync(context, intArrayOf(id))
    override fun onDeleted(context: Context, ids: IntArray) {
        val edit = settings(context).edit(); ids.forEach { edit.remove(it.toString()) }; edit.commit()
    }
    override fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) {
        val prefs = settings(context)
        val values = oldIds.map { prefs.getString(it.toString(), null) }
        val edit = prefs.edit()
        oldIds.forEach { edit.remove(it.toString()) }
        newIds.forEachIndexed { index, id -> values.getOrNull(index)?.let { edit.putString(id.toString(), it) } }
        check(edit.commit()) // AppWidgetProvider dispatches onUpdate immediately after onRestored.
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in setOf(Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCALE_CHANGED))
            updateAsync(context, ids(context))
    }
    private fun updateAsync(context: Context, ids: IntArray) {
        if (ids.isEmpty()) return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { withTimeout(8_000) { render(context, ids) } }
            catch (_: Exception) { renderWithPreferences(context, ids, AppPreferences()) }
            finally { result.finish() }
        }
    }
    companion object {
        val tools = listOf(ToolRoute.LOCATION, ToolRoute.DECISION, ToolRoute.ITEMS, ToolRoute.REMINDERS, ToolRoute.BILLS, ToolRoute.DIARY, ToolRoute.ANNIVERSARIES)
        private val symbols = listOf("⌖", "🎲", "📦", "✓", "¥", "📖", "📅")
        fun settings(context: Context) = context.getSharedPreferences("quick-tool-widgets", Context.MODE_PRIVATE)
        fun ids(context: Context) = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, QuickToolWidget::class.java))
        fun selected(context: Context, id: Int) = settings(context).getString(id.toString(), null)?.let { value -> tools.firstOrNull { it.id == value } }
        suspend fun render(context: Context, ids: IntArray = ids(context)) {
            if (ids.isEmpty()) return
            val preferences = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).preferences().preferences.first()
            renderWithPreferences(context, ids, preferences)
        }
        fun renderWithPreferences(context: Context, ids: IntArray, preferences: AppPreferences) {
            val config = Configuration(context.resources.configuration)
            if (preferences.language != AppLanguage.SYSTEM) config.setLocale(Locale.forLanguageTag(preferences.language.tag))
            val systemDark = config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val dark = preferences.appearance == Appearance.DARK || preferences.appearance == Appearance.SYSTEM && systemDark
            config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            val localized = context.createConfigurationContext(config)
            val names = localized.resources.getStringArray(imehc.recoow.core.designsystem.R.array.record_tool_names)
            val manager = AppWidgetManager.getInstance(context)
            ids.forEach { id ->
                val tool = selected(context, id)
                val title = tool?.let { names[ToolRoute.entries.indexOf(it)] } ?: localized.getString(R.string.widget_choose)
                val views = RemoteViews(context.packageName, R.layout.quick_tool_widget)
                views.setTextViewText(R.id.widget_symbol, tool?.let { symbols[tools.indexOf(it)] } ?: "+")
                views.setTextViewText(R.id.widget_title, title)
                views.setTextViewText(R.id.widget_brand, localized.getString(R.string.app_name))
                views.setInt(R.id.widget_root, "setBackgroundResource", if (dark) R.drawable.widget_background_dark else R.drawable.widget_background)
                views.setBoolean(R.id.widget_root, "setClipToOutline", true)
                for (view in listOf(R.id.widget_symbol, R.id.widget_title, R.id.widget_brand)) views.setTextColor(view, localized.getColor(R.color.widget_on_surface))
                views.setContentDescription(R.id.widget_root, title)
                val intent = if (tool == null) Intent(context, QuickToolWidgetConfiguration::class.java)
                    .setData(Uri.parse("recoow-widget://configure/$id")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                else Intent(Intent.ACTION_VIEW, Uri.parse("recoow://tool/${tool.id}"), context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                manager.updateAppWidget(id, views)
            }
        }
    }
}
@EntryPoint @InstallIn(SingletonComponent::class)
interface WidgetEntryPoint { fun preferences(): PreferencesRepository }
