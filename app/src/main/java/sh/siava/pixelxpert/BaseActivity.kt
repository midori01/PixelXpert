package sh.siava.pixelxpert

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import sh.siava.pixelxpert.util.LocaleHelper

open class BaseActivity : ComponentActivity() {

	override fun attachBaseContext(newBase: Context) {
		val wrapped = LocaleHelper.wrapContext(newBase)
		super.attachBaseContext(wrapped)
		try {
			val config = Configuration(wrapped.resources.configuration)
			LocaleHelper.overrideConfiguration(this, config)
			applyOverrideConfiguration(config)
		} catch (ignored: Throwable) {
		}
	}
}
