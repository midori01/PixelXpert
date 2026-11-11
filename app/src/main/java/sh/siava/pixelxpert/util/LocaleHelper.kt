package sh.siava.pixelxpert.util

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale
import sh.siava.pixelxpert.data.PrefStore

object LocaleHelper {

	fun getSavedLocale(context: Context): Locale? {
		try {
			val prefs = PrefStore.open(context)
			val tag = prefs.getString("appLanguage", null)
			if (!tag.isNullOrEmpty()) {
				return Locale.forLanguageTag(tag)
			}
		} catch (ignored: Throwable) {
		}

		return try {
			val localeManager = context.getSystemService(LocaleManager::class.java)
			val appLocales = localeManager?.applicationLocales
			if (appLocales != null && !appLocales.isEmpty) {
				appLocales.get(0)
			} else null
		} catch (ignored: Throwable) {
			null
		}
	}

	fun applyLocale(context: Context, languageTag: String?) {
		val localeManager = context.getSystemService(LocaleManager::class.java)
		val list = if (!languageTag.isNullOrEmpty()) {
			LocaleList.forLanguageTags(languageTag)
		} else {
			LocaleList.getEmptyLocaleList()
		}
		try {
			localeManager?.applicationLocales = list
		} catch (ignored: Throwable) {
		}

		val locale = if (!languageTag.isNullOrEmpty()) {
			Locale.forLanguageTag(languageTag)
		} else {
			Locale.getDefault()
		}
		Locale.setDefault(locale)
	}

	fun initAppLocale(context: Context) {
		val locale = getSavedLocale(context) ?: return
		val config = Configuration(context.resources.configuration)
		config.setLocale(locale)
		val list = LocaleList(locale)
		LocaleList.setDefault(list)
		config.setLocales(list)
		Locale.setDefault(locale)
		try {
			@Suppress("DEPRECATION")
			context.resources.updateConfiguration(config, context.resources.displayMetrics)
		} catch (ignored: Throwable) {
		}
		applyLocale(context, locale.toLanguageTag())
	}

	fun wrapContext(base: Context): Context {
		val locale = getSavedLocale(base) ?: return base
		val config = Configuration(base.resources.configuration)
		config.setLocale(locale)
		val list = LocaleList(locale)
		LocaleList.setDefault(list)
		config.setLocales(list)
		return base.createConfigurationContext(config)
	}

	fun overrideConfiguration(context: Context, configuration: Configuration) {
		val locale = getSavedLocale(context) ?: return
		configuration.setLocale(locale)
		val list = LocaleList(locale)
		LocaleList.setDefault(list)
		configuration.setLocales(list)
	}
}

fun Context.findActivity(): Activity? {
	var ctx = this
	while (ctx is ContextWrapper) {
		if (ctx is Activity) return ctx
		ctx = ctx.baseContext
	}
	return null
}
