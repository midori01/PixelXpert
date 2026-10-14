package sh.siava.pixelxpert.util

import android.content.Context
import android.content.Intent
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.regex.Pattern
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.PxnApp

object AppUtils {

	@JvmStatic
	fun restart(what: String) {
		when (what.lowercase(Locale.getDefault())) {
			"systemui" -> Shell.cmd("killall com.android.systemui").exec()
			"system" -> Shell.cmd("am start -a android.intent.action.REBOOT").exec()
			"zygote", "android" -> {
				Shell.cmd("kill $(pidof zygote)").submit()
				Shell.cmd("kill $(pidof zygote64)").submit()
			}
			else -> Shell.cmd("killall $what").exec()
		}
	}

	/**
	 * Relaunches the app's own launcher activity, optionally carrying
	 * [reason] so the target screen can say why it is starting, and then
	 * kills this process. The kill is unconditional: it runs even when
	 * no launch intent was found.
	 */
	@JvmStatic
	fun restartSelf(reason: String?) {
		val intent = PxnApp.get().baseContext.packageManager
			.getLaunchIntentForPackage(PxnApp.get().baseContext.packageName)

		if (intent != null) {
			if (reason != null) {
				intent.putExtra(Constants.LAUNCH_REASON_EXTRA, reason)
			}
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
			PxnApp.get().startActivity(intent)
		}

		android.os.Process.killProcess(android.os.Process.myPid())
		System.exit(0)
	}

	/**
	 * Reaches the KernelSU manager's root activity: first a broadcast, in
	 * case the manager is already running and can show the prompt itself,
	 * then a launch intent for the case where it is not.
	 */
	@JvmStatic
	fun runKSURootActivity(context: Context, launchApp: Boolean) {
		try {
			val broadcastIntent = Intent(Constants.PX_ROOT_EXTRA)
			if (launchApp) {
				broadcastIntent.putExtra("launchApp", 1)
			}

			broadcastIntent.setPackage(Constants.KSU_PACKAGE)
			context.sendBroadcast(broadcastIntent)

			val launchIntent = context.packageManager.getLaunchIntentForPackage(Constants.KSU_PACKAGE)
			//noinspection DataFlowIssue
			launchIntent!!.putExtra(Constants.PX_ROOT_EXTRA, 1)
			launchIntent.flags = Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
			if (launchApp) {
				launchIntent.putExtra("launchApp", 1)
			}

			context.startActivity(launchIntent)
		} catch (ignored: Throwable) {
		}
	}

	@JvmStatic
	fun isLikelyPixelBuild(): Boolean {
		try {
			val process = Runtime.getRuntime().exec("getprop ro.build.id")
			process.waitFor()
			val buffer = ByteArray(process.inputStream.available())
			//noinspection ResultOfMethodCallIgnored
			process.inputStream.read(buffer)
			val result = String(buffer, StandardCharsets.US_ASCII).replace("\n", "")
			return Pattern.matches("^[TUABCDZ][A-Z]([A-Z0-9]){2}\\.[0-9]{6}\\.[0-9]{3}(\\.[A-Z0-9]{2})?$", result) //Pixel standard build number of A13/14 + new weird build numbers of 'A,B,...' prefix
		} catch (ignored: Throwable) {
			return false
		}
	}

	@JvmStatic
	fun installDoubleZip(DoubleZipped: String): Boolean {
		return ModuleInstaller.install(DoubleZipped)
	}
}
