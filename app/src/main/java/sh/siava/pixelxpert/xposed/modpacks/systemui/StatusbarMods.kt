package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.CharacterStyle
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.content.res.Resources
import android.util.DisplayMetrics
import android.util.TypedValue
import android.widget.RelativeLayout
import java.lang.reflect.Constructor
import androidx.annotation.ColorInt
import androidx.annotation.Nullable
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.getAdditionalInstanceField
import de.robv.android.xposed.XposedHelpers.getBooleanField
import de.robv.android.xposed.XposedHelpers.getObjectField
import de.robv.android.xposed.XposedHelpers.setAdditionalInstanceField
import de.robv.android.xposed.XposedHelpers.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import java.util.Arrays
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.Executor
import java.util.function.Function
import javax.security.auth.callback.Callback
import org.objenesis.ObjenesisHelper
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.NetworkTraffic
import sh.siava.pixelxpert.xposed.utils.ShyLinearLayout
import sh.siava.pixelxpert.xposed.utils.StringFormatter
import sh.siava.pixelxpert.xposed.utils.StringFormatter.FormattedStringCallback
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.batteryStyles.BatteryBarView
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectionTools.reAddView
import sh.siava.pixelxpert.xposed.utils.toolkit.ObjectTools.getStateFlowImplOf
import sh.siava.pixelxpert.xposed.utils.toolkit.ResourceTools

/**
 * Status-bar content and layout pack: the clock can be moved between left /
 * centre / right (with optional custom text before, after and AM/PM styling),
 * a network-traffic indicator and a battery bar are planted into the bar, the
 * left side is split into rows so notification icons no longer squeeze the
 * clock, and extra icon slots (app switch, VoLTE, VoWi-Fi) are driven straight
 * into SystemUI's icon controller. It also hides the privacy chip and the
 * roaming state, and mirrors the status-bar clock's colour and keyguard
 * visibility to every registered [StatusbarTextColorCallback] /
 * [ClockVisibilityCallback] - which is how [NetworkTraffic] tints itself.
 *
 * Nearly all of its mutable state lives in the [companion] as static
 * members: SystemUI's view tree hands this pack one hierarchy
 * while preference updates and hook callbacks arrive from several directions,
 * and the pack instance itself is process-scoped, so the two are equally
 * long-lived here.
 */
@SystemUIModPack
class StatusbarMods @SuppressLint("DiscouragedApi") constructor(context: Context) : XposedModPack(context) {

	companion object {
		//region Clock
		const val POSITION_LEFT = 0
		const val POSITION_CENTER = 1
		const val POSITION_RIGHT = 2
		const val POSITION_LEFT_EXTRA_LEVEL = 3

		private const val AM_PM_STYLE_SMALL = 1
		private const val AM_PM_STYLE_GONE = 2
		private var isJetpackClock = false

		@JvmStatic
		var isMovingClock = false

		private var clockPosition = POSITION_LEFT
		private var mAmPmStyle = AM_PM_STYLE_GONE
		private var mShowSeconds = false
		private var mStringFormatBefore = ""
		private var mStringFormatAfter = ""
		private var mBeforeSmall = true
		private var mAfterSmall = true
		//endregion

		//region network traffic
		private var networkOnSBEnabled = false
		private var networkTrafficPosition = POSITION_LEFT
		//endregion

		//region battery bar
		private var BBarEnabled = false
		private var BBarColorful = false
		private var BBOnlyWhileCharging = false
		private var BBOnBottom = false
		private var BBSetCentered = false
		private var BBOpacity = 100
		private var BBarHeight = 10
		private var batteryLevels: List<Float> = listOf(20f, 40f)
		private var batteryColors: IntArray = intArrayOf(Color.RED, Color.YELLOW)
		private var chargingColor = Color.WHITE
		private var fastChargingColor = Color.WHITE
		private var powerSaveColor = Color.parseColor("#FFBF00")
		private var indicateCharging = false
		private var indicateFastCharging = false
		private var indicatePowerSave = false
		private var BBarTransitColors = false
		private var BBAnimateCharging = false
		//endregion

		//region privacy chip
		private var HidePrivacyChip = false //works
		//endregion

		//region general use
		private const val PADDING_DEFAULT = -0.5f
		private val clockVisibilityCallbacks = ArrayList<ClockVisibilityCallback>()
		private var notificationAreaMultiRow = false
		private var multiRowClockBottom = false
		private var NotificationAODIconLimit = 3
		private var NotificationIconLimit = 4
		private var currentClockColor = 0
		private val mTextColorCallbacks = ArrayList<StatusbarTextColorCallback>()
		// PADDING_DEFAULT from the outset: until the hook has actually read a
		// stored pair there is nothing to enforce, which is also the state the
		// new reset icon puts things back into. Starting at 0 is only safe when
		// androidx preference persists its declared default on first
		// run; this renderer deliberately does not write a range until it is
		// slid, so 0 here would mean "force zero padding" rather than "no
		// opinion" - and the guard below only recognises PADDING_DEFAULT.
		private var SBPaddingStart = PADDING_DEFAULT
		private var SBPaddingEnd = PADDING_DEFAULT
		// The system's own paddings, snapshotted on the first pass through the
		// hook - i.e. before we have ever overwritten them - so that a reset
		// has something to put back.
		private var originalPaddingStart = -1
		private var originalPaddingEnd = -1
		//endregion

		//region vo_data
		private const val VO_LTE_SLOT = "volte"
		private const val VO_WIFI_SLOT = "vowifi"

		private var VolteIconEnabled = false //works

		private var VowifiIconEnabled = false
		//endregion

		//region combined signal icons
		private var CombineSignalIcons = false
		private var HideRoamingState = false
		const val ICON_HIDE_LIST = "icon_blacklist"
		//endregion

		//region app profile switch
		const val APP_SWITCH_SLOT = "app_switch"

		private var StatusbarAppSwitchIconEnabled = false //works
		//endregion

		@JvmStatic
		@ColorInt
		fun getCurrentClockColor(): Int {
			return currentClockColor
		}

		@JvmStatic
		fun registerTextColorCallback(callback: StatusbarTextColorCallback): Int {
			mTextColorCallbacks.add(callback)
			return currentClockColor
		}

		@JvmStatic
		fun registerClockVisibilityCallback(callback: ClockVisibilityCallback) {
			clockVisibilityCallbacks.add(callback)
		}

		@JvmStatic
		fun unRegisterClockVisibilityCallback(callback: ClockVisibilityCallback) {
			clockVisibilityCallbacks.remove(callback)
		}
	}

	//region Clock
	private val leftClockPadding: Int
	private val rightClockPadding: Int
	private var mJetpackClockView: View? = null
	private var mBeforeClockColor: Int? = null
	private var mAfterClockColor: Int? = null
	private var clockColor: Int? = null
	//endregion

	init {
		rightClockPadding = mContext.resources.getDimensionPixelSize(SystemUtils.dimenIdOf("status_bar_clock_starting_padding"))
		leftClockPadding = mContext.resources.getDimensionPixelSize(SystemUtils.dimenIdOf("status_bar_left_clock_end_padding"))
	}

	//region network traffic
	private var networkTrafficSB: NetworkTraffic? = null
	private var networkTrafficKeyguard: NetworkTraffic? = null
	private var mKeyguardStatusBarView: ViewGroup? = null
	//endregion

	//region general use
	private var mActivityStarter: Any? = null
	private var AODNIC: Any? = null
	private var SBNIC: Any? = null
	private var mStatusbarStartSide: ViewGroup? = null
	private var mCenteredIconArea: View? = null
	private var mSystemIconArea: LinearLayout? = null

	private var mClockView: TextView? = null
	private var mNotificationIconContainer: ViewGroup? = null
	internal var mNotificationContainerContainer: LinearLayout? = null
	private var mLeftVerticalSplitContainer: LinearLayout? = null
	private var mLeftExtraRowContainer: LinearLayout? = null
	private var mPhoneStatusbarView: FrameLayout? = null
	//endregion

	//region vo_data
	private val voDataExec: Executor = Executor { it.run() }

	private var mStatusBarIconController: Any? = null
	private var mCommandQueue: Any? = null
	private var isLegacyStatusBar = false

	private var StatusBarIconClass: ReflectedClass? = null
	private var StatusBarIconHolderClass: ReflectedClass? = null
	private var volteStatusbarIcon: Any? = null
	private var volteStatusbarIconHolder: Any? = null
	private var vonrStatusbarIcon: Any? = null
	private var vonrStatusbarIconHolder: Any? = null
	private var telephonyCallbackRegistered = false
	private var lastVolteAvailable = false
	private var lastIsNR = false
	private val voDataCallback = serverStateCallback()

	private var vowifiStatusbarIcon: Any? = null
	private var vowifiStatusbarIconHolder: Any? = null
	private var lastVowifiAvailable = false
	//endregion

	//region combined signal icons
	private var mWifiVisible = false
	private var mTunerService: Any? = null
	//endregion

	//region app profile switch
	private var mAppSwitchStatusbarIcon: Any? = null
	private var mAppSwitchStatusbarIconHolder: Any? = null

	private val mAppProfileSwitchReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context?, intent: Intent) {
			if (Constants.ACTION_PROFILE_SWITCH_AVAILABLE == intent.action) {
				val isAvailable = intent.getBooleanExtra("available", false)
				if (isAvailable && StatusbarAppSwitchIconEnabled) {
					if (mAppSwitchStatusbarIcon == null) {
						initSwitchIcon()
					}
					setSBIcon(APP_SWITCH_SLOT, mAppSwitchStatusbarIconHolder, mAppSwitchStatusbarIcon)
				} else {
					removeSBIconSlot(APP_SWITCH_SLOT)
				}
			}
		}
	}
	//endregion

	private fun initSwitchIcon() {
		try {
			val appSwitchIcon = Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_app_switch)

			mAppSwitchStatusbarIcon = getStatusbarIconFor(appSwitchIcon, APP_SWITCH_SLOT)

			mAppSwitchStatusbarIconHolder = getStatusbarIconHolderFor(mAppSwitchStatusbarIcon)
		} catch (ignored: Throwable) {
		}
	}

	@Suppress("SENSELESS_COMPARISON")
	override fun onPreferenceUpdated(vararg key: String?) {

		StatusbarAppSwitchIconEnabled = Xprefs.getBoolean("StatusbarAppSwitchIconEnabled", false)

		HidePrivacyChip = Xprefs.getBoolean("HidePrivacyChip", false)

		HideRoamingState = Xprefs.getBoolean("HideRoamingState", false)

		CombineSignalIcons = Xprefs.getBoolean("combinedSignalEnabled", false)
		wifiVisibleChanged()

		if (key.isNotEmpty() && key[0] == "notificationAreaMultiRow") { //WHY we check the old value? because if prefs is empty it will fill it up and count an unwanted change
			val newnotificationAreaMultiRow = Xprefs.getBoolean("notificationAreaMultiRow", false)
			if (newnotificationAreaMultiRow != notificationAreaMultiRow) {
				SystemUtils.killSelf()
			}
		}
		if (key.isNotEmpty() && key[0] == "multiRowClockBottom") {
			val newMultiRowClockBottom = Xprefs.getBoolean("multiRowClockBottom", false)
			if (newMultiRowClockBottom != multiRowClockBottom) {
				SystemUtils.killSelf()
			}
		}
		notificationAreaMultiRow = Xprefs.getBoolean("notificationAreaMultiRow", false)
		multiRowClockBottom = Xprefs.getBoolean("multiRowClockBottom", false)

		NotificationIconLimit = Xprefs.getString("NotificationIconLimit", "")?.trim()?.toIntOrNull()
			?: getIntegerResource("max_notif_static_icons", 4)

		NotificationAODIconLimit = Xprefs.getString("NotificationAODIconLimit", "")?.trim()?.toIntOrNull()
			?: getIntegerResource("max_notif_icons_on_aod", 3)

		if (AODNIC != null) {
			setObjectField(AODNIC, "maxIcons", NotificationAODIconLimit)
		}
		if (SBNIC != null) {
			setObjectField(SBNIC, "maxIcons", NotificationIconLimit)
		}

		val paddings = Xprefs.getSliderValues("statusbarPaddings", 0f)

		if (paddings.size > 1) {
			SBPaddingStart = paddings[0]
			SBPaddingEnd = 100f - paddings[1]
		} else {
			// Nothing parseable is stored: the row's reset icon clears the key
			// outright rather than writing a default back. Falling through to
			// the 0f the reader declares would leave the last applied padding
			// in force, so both handles go back to PADDING_DEFAULT - the pair
			// the hook below already treats as "apply nothing".
			SBPaddingStart = PADDING_DEFAULT
			SBPaddingEnd = PADDING_DEFAULT
		}

		//region BatteryBar Settings
		BBarEnabled = Xprefs.getBoolean("BBarEnabled", false)
		BBarColorful = Xprefs.getBoolean("BBarColorful", false)
		BBOnlyWhileCharging = Xprefs.getBoolean("BBOnlyWhileCharging", false)
		BBOnBottom = Xprefs.getBoolean("BBOnBottom", false)
		BBSetCentered = Xprefs.getBoolean("BBSetCentered", false)
		BBOpacity = Xprefs.getSliderInt("BBOpacity", 100)
		BBarHeight = Xprefs.getSliderInt("BBarHeight", 50)
		BBarTransitColors = Xprefs.getBoolean("BBarTransitColors", false)
		BBAnimateCharging = Xprefs.getBoolean("BBAnimateCharging", false)

		batteryLevels = Xprefs.getSliderValues("batteryWarningRange", 0f)

		batteryColors = intArrayOf(
			Xprefs.getInt("batteryCriticalColor", Color.RED),
			Xprefs.getInt("batteryWarningColor", Color.YELLOW),
		)

		indicateFastCharging = Xprefs.getBoolean("indicateFastCharging", false)
		indicatePowerSave = Xprefs.getBoolean("indicatePowerSave", false)
		indicateCharging = Xprefs.getBoolean("indicateCharging", true)

		chargingColor = Xprefs.getInt("batteryChargingColor", Color.GREEN)
		fastChargingColor = Xprefs.getInt("batteryFastChargingColor", Color.BLUE)
		powerSaveColor = Xprefs.getInt("batteryPowerSaveColor", Color.parseColor("#FFBF00"))

		if (BBarEnabled) {
			placeBatteryBar()
		}

		if (BatteryBarView.hasInstance()) {
			refreshBatteryBar(BatteryBarView.getInstance()!!)
		}
		//endregion BatteryBar Settings

		//region network Traffic settings
		networkOnSBEnabled = Xprefs.getBoolean("networkOnSBEnabled", false)
		val networkTrafficModeStr = Xprefs.getString("networkTrafficMode", "0")
		val networkTrafficMode = networkTrafficModeStr?.toIntOrNull() ?: 0

		val networkTrafficRXTop = Xprefs.getBoolean("networkTrafficRXTop", true)
		// The colourful toggle and its two colour rows are gone; opacity is
		// deliberately not - it is a separate row on the same screen and was
		// asked to stay.
		val networkTrafficOpacity = Xprefs.getSliderInt("networkTrafficOpacity", 100)
		val networkTrafficInterval = Xprefs.getSliderInt("networkTrafficInterval", 1)
		val networkTrafficShowIcons = Xprefs.getBoolean("networkTrafficShowIcons", true)
		val networkTrafficShowInBits = Xprefs.getBoolean("networkTrafficShowInBits", false)

		if (networkOnSBEnabled) {
			networkTrafficPosition = Xprefs.getString("networkTrafficPosition", POSITION_RIGHT.toString())?.toIntOrNull() ?: POSITION_RIGHT
			if (networkTrafficPosition == POSITION_LEFT_EXTRA_LEVEL) {
				Xprefs.edit().putString("networkTrafficPosition", POSITION_LEFT.toString()).apply()
				networkTrafficPosition = POSITION_LEFT
			}

			val thresholdText = Xprefs.getString("networkTrafficThreshold", "10")

			val networkTrafficThreshold: Int = thresholdText?.toFloatOrNull()?.let { Math.round(it) } ?: 10
			NetworkTraffic.setConstants(networkTrafficInterval, networkTrafficThreshold, networkTrafficMode, networkTrafficRXTop, networkTrafficOpacity, networkTrafficShowIcons, networkTrafficShowInBits)
		}
		if (networkOnSBEnabled) {
			if (networkTrafficSB == null) {
				networkTrafficSB = NetworkTraffic.getSBInstance(mContext)
			}
			networkTrafficSB!!.update()
			if (mKeyguardStatusBarView != null) {
				if (networkTrafficKeyguard == null) {
					networkTrafficKeyguard = NetworkTraffic.getKeyguardInstance(mContext)
				}
				networkTrafficKeyguard!!.update()
			}
		}
		placeNTSB()
		placeNTKeyguard()

		//endregion network settings

		//region clock settings
		clockPosition = Xprefs.getString("SBClockLoc", POSITION_LEFT.toString())?.toIntOrNull() ?: POSITION_LEFT
		if (clockPosition == POSITION_LEFT_EXTRA_LEVEL) {
			Xprefs.edit().putString("SBClockLoc", POSITION_LEFT.toString()).apply()
			clockPosition = POSITION_LEFT
		}

		mShowSeconds = Xprefs.getBoolean("SBCShowSeconds", false)
		mAmPmStyle = Xprefs.getString("SBCAmPmStyle", AM_PM_STYLE_GONE.toString())?.toIntOrNull() ?: AM_PM_STYLE_GONE

		mStringFormatBefore = Xprefs.getString("DateFormatBeforeSBC", "")!!
		mStringFormatAfter = Xprefs.getString("DateFormatAfterSBC", "")!!
		mBeforeSmall = Xprefs.getBoolean("BeforeSBCSmall", true)
		mAfterSmall = Xprefs.getBoolean("AfterSBCSmall", true)

		// "Make it colorful" and the three colour rows behind it no longer
		// exist, so the clock keeps the system's own colouring - which is
		// exactly what the old else branch left it with. The fields stay: the
		// span builders further down still take them, and null is the value
		// that tells them to paint nothing.
		mAfterClockColor = null
		mBeforeClockColor = null
		clockColor = null

		if ((mStringFormatBefore + mStringFormatAfter).trim().isEmpty()) {
			val SBCDayOfWeekMode = Xprefs.getString("SBCDayOfWeekMode", "0")?.toIntOrNull() ?: 0

			when (SBCDayOfWeekMode) {
				0 -> {
					mStringFormatAfter = ""
					mStringFormatBefore = ""
				}
				1 -> {
					mStringFormatBefore = "\$GEEE "
					mStringFormatAfter = ""
					mBeforeSmall = false
				}
				2 -> {
					mStringFormatBefore = "\$GEEE "
					mStringFormatAfter = ""
					mBeforeSmall = true
				}
				3 -> {
					mStringFormatBefore = ""
					mStringFormatAfter = " \$GEEE"
					mAfterSmall = false
				}
				4 -> {
					mStringFormatBefore = ""
					mStringFormatAfter = " \$GEEE"
					mAfterSmall = true
				}
			}
		}

		if (mContext != null && isJetpackClock) {
			try {
				val current = android.provider.Settings.Secure.getInt(mContext.contentResolver, "clock_seconds", 0)
				val desired = if (mShowSeconds) 1 else 0
				if (current != desired) {
					android.provider.Settings.Secure.putInt(mContext.contentResolver, "clock_seconds", desired)
				}
			} catch (t: Throwable) {
			}
		}

		try {
			placeClock()
			updateClock()
		} catch (ignored: Throwable) {
		}
		//endregion clock settings

		//region vo_data
		VolteIconEnabled = Xprefs.getBoolean("VolteIconEnabled", false)
		VowifiIconEnabled = Xprefs.getBoolean("VowifiIconEnabled", false)
		//endregion

		if (key.isNotEmpty()) {
			when (key[0]) {
				"statusbarPaddings" -> updateStatusbarHeight()
				"VolteIconEnabled", "VowifiIconEnabled" -> {
					if (VolteIconEnabled || VowifiIconEnabled) {
						initVoData()

						if (!VolteIconEnabled) removeSBIconSlot(VO_LTE_SLOT)
						if (!VowifiIconEnabled) removeSBIconSlot(VO_WIFI_SLOT)
					} else {
						removeVoDataCallback()
					}
				}
			}
		}
	}

	@SuppressLint("DiscouragedApi")
	private fun getIntegerResource(resourceName: String, defaultValue: Int): Int {
		try {
			return mContext.resources.getInteger(SystemUtils.resourceIdOf(resourceName, "integer"))
		} catch (ignored: Throwable) {
			return defaultValue
		}
	}

	private fun updateClock() {
		try {
			mClockView!!.post { //the builtin update method doesn't care about the format. Just the text sadly
				callMethod(getObjectField(mClockView, "mCalendar"), "setTimeInMillis", System.currentTimeMillis())

				mClockView!!.text = callMethod(mClockView, "getSmallTime") as CharSequence
			}
		} catch (ignored: Throwable) {
		}
	}

	@SuppressLint("DiscouragedApi")
	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		StatusBarIconClass = ReflectedClass.of("com.android.internal.statusbar.StatusBarIcon")
		StatusBarIconHolderClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.StatusBarIconHolder")
		isLegacyStatusBar = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarIconBlockListBinder").clazz != null

		onPreferenceUpdated()
		val filter = IntentFilter()
		filter.addAction(Constants.ACTION_PROFILE_SWITCH_AVAILABLE)
		mContext.registerReceiver(mAppProfileSwitchReceiver, filter, Context.RECEIVER_EXPORTED)

		//region needed classes
		val ClockClass = ReflectedClass.of("com.android.systemui.statusbar.policy.Clock")
		val PhoneStatusBarViewClass = ReflectedClass.of("com.android.systemui.statusbar.phone.PhoneStatusBarView")
		val NotificationIconContainerClass = ReflectedClass.of("com.android.systemui.statusbar.phone.NotificationIconContainer")
		val TunerServiceImplClass = ReflectedClass.of("com.android.systemui.tuner.TunerServiceImpl")

		try {
			val AbstractComposeViewClass = ReflectedClass.of("androidx.compose.ui.platform.AbstractComposeView")
			AbstractComposeViewClass.before("disposeComposition").run { param ->
				if (isMovingClock) {
					param.setResult(null)
				}
			}
		} catch (ignored: Throwable) {
		}

		val ConnectivityCallbackHandlerClass = ReflectedClass.of("com.android.systemui.statusbar.connectivity.CallbackHandler")
		val NotificationIconContainerAlwaysOnDisplayViewModelClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerAlwaysOnDisplayViewModel")
		val NotificationIconContainerStatusBarViewModelClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerStatusBarViewModel")
		val PrivacyItemClass = ReflectedClass.of("com.android.systemui.privacy.PrivacyItem")
		val PhoneStatusBarViewControllerClass = ReflectedClass.of("com.android.systemui.statusbar.phone.PhoneStatusBarViewController")
		val KeyguardStatusBarViewClass = ReflectedClass.of("com.android.systemui.statusbar.phone.KeyguardStatusBarView")
		val KeyguardStateControllerImplClass = ReflectedClass.of("com.android.systemui.statusbar.policy.KeyguardStateControllerImpl")
		val StatusBarIconControllerImplClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl").let {
			if (it.clazz != null) it else ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.StatusBarIconControllerImpl")
		}
		val CommandQueueClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.CommandQueue")
		val ShadeHeaderControllerClass = ReflectedClass.ofIfPossible("com.android.systemui.shade.ShadeHeaderController")
		val ActivityStarterImplClass = ReflectedClass.of("com.android.systemui.statusbar.phone.ActivityStarterImpl")
		//endregion

		KeyguardStatusBarViewClass
			.after("onFinishInflate")
			.run { param ->
				mKeyguardStatusBarView = param.thisObject as ViewGroup
				if (networkOnSBEnabled) {
					placeNTKeyguard()
				}
			}

		KeyguardStatusBarViewClass
			.after("onAttachedToWindow")
			.run { param ->
				mKeyguardStatusBarView = param.thisObject as ViewGroup
				if (networkOnSBEnabled) {
					placeNTKeyguard()
				}
			}

		KeyguardStateControllerImplClass
			.after("notifyKeyguardState")
			.run { param ->
				val mKeyguardUpdateMonitor = getObjectField(param.thisObject, "mKeyguardUpdateMonitor")
				val keyguardShowing = getObjectField(mKeyguardUpdateMonitor, "mKeyguardShowing") as Boolean
				for (c in clockVisibilityCallbacks) {
					try {
						c.OnVisibilityChanged(!keyguardShowing)
					} catch (ignored: Throwable) {
					}
				}
			}

		CommandQueueClass
			.afterConstruction()
			.run { param ->
				mCommandQueue = param.thisObject
				if (VolteIconEnabled || VowifiIconEnabled) {
					initVoData()
				}
			}

		StatusBarIconControllerImplClass
			.afterConstruction()
			.run { param ->
				mStatusBarIconController = param.thisObject
				if (mCommandQueue == null) {
					for (arg in param.args) {
						if (arg != null && arg.javaClass.name.endsWith("CommandQueue")) {
							mCommandQueue = arg
							break
						}
					}
				}
				if (VolteIconEnabled || VowifiIconEnabled) {
					initVoData()
				}
			}

		if (NotificationIconContainerAlwaysOnDisplayViewModelClass.clazz != null) //Viewbinder implementation of the notification icon container
		{
			NotificationIconContainerAlwaysOnDisplayViewModelClass
				.afterConstruction()
				.run { param ->
					AODNIC = param.thisObject
					setObjectField(AODNIC, "maxIcons", NotificationAODIconLimit)
				}

			NotificationIconContainerStatusBarViewModelClass
				.afterConstruction()
				.run { param ->
					SBNIC = param.thisObject
					setObjectField(SBNIC, "maxIcons", NotificationIconLimit)
				}
		}

		initSwitchIcon()

		//forcing a refresh on statusbar once the charging chip goes away to avoid layout issues
		//only needed if chip is shown on lockscreen and device is unlocked quickly afterwards


		//region combined signal icons
		TunerServiceImplClass
			.afterConstruction()
			.run { param ->
				mTunerService = param.thisObject
				ReflectedClass.of(getObjectField(param.thisObject, "mObserver").javaClass)
					.after("onChange")
					.run { param2 -> wifiVisibleChanged() }
			}

		TunerServiceImplClass
			.after("addTunable")
			.run { param ->
				if (param.args[1]!!.javaClass == Array<String>::class.java &&
					Arrays.asList(*(param.args[1] as Array<*>)).contains(ICON_HIDE_LIST)
				) {
					wifiVisibleChanged()
				} else if (ICON_HIDE_LIST == param.args[1]) {
					wifiVisibleChanged()
				}
			}

		ConnectivityCallbackHandlerClass
			.after("setWifiIndicators")
			.run { param ->
				val wifiVisible = getBooleanField(getObjectField(param.args[0], "statusIcon"), "visible")
				if (wifiVisible != mWifiVisible) {
					mWifiVisible = wifiVisible
					if (CombineSignalIcons) {
						wifiVisibleChanged()
					}
				}
			}
		//endregion

		//region privacy chip
		PrivacyItemClass //A16 //qpr2b2 has removed the constructor, but it does do a distinct thing before using them. (Sunglass + roll emoji goes here)
			.before("hashCode")
			.run { param ->
				if (HidePrivacyChip) {
					setObjectField(param.thisObject, "paused", true)
				}
			}
		//endregion

		//region SB Padding
		PhoneStatusBarViewClass
			.afterConstruction()
			.run { param -> mPhoneStatusbarView = param.thisObject as FrameLayout }

		PhoneStatusBarViewClass
			.after("updateStatusBarHeight")
			.run { param ->
				@SuppressLint("DiscouragedApi")
				val sbContentsView = (param.thisObject as View).findViewById<View>(SystemUtils.idOf("status_bar_contents"))

				// Snapshot the system's paddings on the very first pass, before
				// this hook has ever written anything of its own.
				if (originalPaddingStart < 0) {
					originalPaddingStart = sbContentsView.paddingStart
					originalPaddingEnd = sbContentsView.paddingEnd
				}

				if (SBPaddingStart == PADDING_DEFAULT && SBPaddingEnd == PADDING_DEFAULT) {
					// Both handles back on the default - the reset icon cleared
					// the key. Returning here would leave the last override
					// painted on the view forever, since nothing else rewrites
					// this padding, so the remembered system values go back on.
					sbContentsView.setPaddingRelative(
						originalPaddingStart,
						sbContentsView.paddingTop,
						originalPaddingEnd,
						sbContentsView.paddingBottom,
					)
					return@run
				}

				val screenWidth = mContext.resources.displayMetrics.widthPixels

				val paddingStart = if (SBPaddingStart == PADDING_DEFAULT)
					originalPaddingStart
				else
					Math.round(SBPaddingStart * screenWidth / 100f)

				val paddingEnd = if (SBPaddingEnd == PADDING_DEFAULT)
					originalPaddingEnd
				else
					Math.round(SBPaddingEnd * screenWidth / 100f)

				sbContentsView.setPaddingRelative(paddingStart, sbContentsView.paddingTop, paddingEnd, sbContentsView.paddingBottom)
			}
		//endregion

		//region multi row statusbar
		//bypassing the max icon limit during measurement
		NotificationIconContainerClass
			.before("onMeasure")
			.run { param -> setObjectField(param.thisObject, "mIsStaticLayout", false) }

		NotificationIconContainerClass
			.after("onMeasure")
			.run { param -> setObjectField(param.thisObject, "mIsStaticLayout", true) }

		//endregion

		//update statusbar
		PhoneStatusBarViewClass
			.after("onConfigurationChanged")
			.run {
				Timer().schedule(object : TimerTask() {
					override fun run() {
						if (BatteryBarView.hasInstance()) {
							BatteryBarView.getInstance()!!.post { refreshBatteryBar(BatteryBarView.getInstance()!!) }
						}
					}
				}, 2000)
			}

		//stealing a working activity starter
		ActivityStarterImplClass
			.afterConstruction()
			.run { param ->
				if (mActivityStarter == null) {
					mActivityStarter = param.thisObject
				}
			}

		val clickListener = ClickListener()

		if (ShadeHeaderControllerClass.clazz != null) {
			ShadeHeaderControllerClass
				.after("onInit")
				.run { param ->
					val mView = getObjectField(param.thisObject, "mView") as View

					mView.findViewById<View>(SystemUtils.idOf("clock")).setOnClickListener(clickListener)
					mView.findViewById<View>(SystemUtils.idOf("clock")).setOnLongClickListener(clickListener)

					mView.findViewById<View>(SystemUtils.idOf("date")).setOnClickListener(clickListener)
					mView.findViewById<View>(SystemUtils.idOf("date")).setOnLongClickListener(clickListener)
				}
		}

		//modding clock, adding additional objects,
		PhoneStatusBarViewControllerClass
			.after("onViewAttached")
			.run { param ->
				mClockView = mPhoneStatusbarView!!.findViewById(SystemUtils.idOf("clock"))
				updateClockColor()

				mPhoneStatusbarView!!.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
					setHeights()
				}

				mStatusbarStartSide = mPhoneStatusbarView!!.findViewById(SystemUtils.idOf("status_bar_start_side_except_heads_up"))

				mSystemIconArea = mPhoneStatusbarView!!.findViewById(SystemUtils.idOf("statusIcons"))

				createCenterIconArea()

				makeLeftSplitArea()

				if (BBarEnabled) //in case we got the config but view wasn't ready yet
				{
					placeBatteryBar()
				}

				if (VolteIconEnabled || VowifiIconEnabled) //in case we got the config but context wasn't ready yet
				{
					initVoData()
				}

				if (networkOnSBEnabled) {
					networkTrafficSB = NetworkTraffic.getSBInstance(mContext)
					placeNTSB()
					placeNTKeyguard()
				}

				if (mNotificationIconContainer!!.childCount == 0) {
					mNotificationContainerContainer!!.visibility = View.GONE
				}
				setHeights()

				placeClock()
			}

		//clock mods
		try {
			val clockInteractorClass = ReflectedClass.of("com.android.systemui.clock.domain.interactor.ClockInteractor")
			val hooks = clockInteractorClass.after("getClockTextFormatString").run { param ->
				val orig: String = param.getResult()

				// Force leading zero for 24-hour format (e.g. 00:23 instead of 0:23)
				var customFormat = orig.replace(Regex("(?<!H)H(?!H)"), "HH")

				// 1. apply am/pm (Since Compose renders as String, this will be 100% size)
				if (mAmPmStyle != AM_PM_STYLE_GONE && !customFormat.contains("a")) {
					customFormat = customFormat + " a"
				} else if (mAmPmStyle == AM_PM_STYLE_GONE && customFormat.contains("a")) {
					customFormat = customFormat.replace(" a", "").replace(" a", "").replace("a", "")
				}

				// Fix native SystemUI bug: remove trailing spaces left over from removing seconds or AM/PM
				customFormat = customFormat.trim()

				// 3. custom text before / after
				val escapeAll = Function<String?, String?> { input ->
					if (input == null || input.isEmpty()) "" else "'" + input.replace("'", "''") + "'"
				}

				if (!mStringFormatBefore.isEmpty()) {
					customFormat = escapeAll.apply(mStringFormatBefore.trim()) + " " + customFormat
				}
				if (!mStringFormatAfter.isEmpty()) {
					customFormat = customFormat + " " + escapeAll.apply(mStringFormatAfter.trim())
				}
				param.setResult(customFormat)
			}

			clockInteractorClass.after("createFormatters").run { param ->
				val formatters: Any? = param.getResult()
				if (formatters == null) return@run

				val amPmShown = getObjectField(formatters, "clockTextAmPmShown") as java.text.SimpleDateFormat
				val amPmGone = getObjectField(formatters, "clockTextAmPmGone") as java.text.SimpleDateFormat

				class PXNClockFormatter(pattern: String) : java.text.SimpleDateFormat(pattern) {
					override fun format(date: java.util.Date, toAppendTo: StringBuffer, pos: java.text.FieldPosition): StringBuffer {
						val original = super.format(date, StringBuffer(), pos)
						val formatted: CharSequence = stringFormatter.formatString(original.toString())
						toAppendTo.append(formatted)
						return toAppendTo
					}
				}

				setObjectField(formatters, "clockTextAmPmShown", PXNClockFormatter(amPmShown.toPattern()))
				setObjectField(formatters, "clockTextAmPmGone", PXNClockFormatter(amPmGone.toPattern()))
			}
			isJetpackClock = hooks.isNotEmpty()
		} catch (t: Throwable) {
			log("ClockInteractor not found, skipping Android 17 Compose hook.")
		}
		ClockClass
			.before("getSmallTime")
			.run { param ->
				setObjectField(param.thisObject, "mAmPmStyle", AM_PM_STYLE_GONE)
				setObjectField(param.thisObject, "mShowSeconds", mShowSeconds)
			}

		ClockClass
			.after("getSmallTime")
			.run { param ->
				if (param.thisObject != mClockView)
					return@run //We don't want custom format in QS header. do we?

				val result = SpannableStringBuilder()
				result.append(getFormattedString(mStringFormatBefore, mBeforeSmall, mBeforeClockColor)) //before clock
				val clockText = SpannableStringBuilder.valueOf(param.getResult<CharSequence>()) //THE clock
				if (clockColor != null) {
					clockText.setSpan(NetworkTraffic.TrafficStyle(clockColor!!), 0, clockText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
				}
				result.append(clockText)
				if (mAmPmStyle != AM_PM_STYLE_GONE) {
					result.append(getFormattedString("\$Ga", mAmPmStyle == AM_PM_STYLE_SMALL, clockColor))
				}
				result.append(getFormattedString(mStringFormatAfter, mAfterSmall, mAfterClockColor)) //after clock

				if (getAdditionalInstanceField(param.thisObject, "stringFormatCallBack") == null) {
					val callback = object : FormattedStringCallback {
						override fun onRefreshNeeded() {
							if (!mShowSeconds) //don't update again if it's going to do it every second anyway
								updateClock()
						}
					}

					stringFormatter.registerCallback(callback)
					setAdditionalInstanceField(param.thisObject, "stringFormatCallBack", callback)
				}
				param.setResult(result)
			}

		//using clock colors for network traffic and battery bar
		ClockClass
			.after("onDarkChanged")
			.run { param ->
				if (param.thisObject != mClockView)
					return@run //We don't want colors of QS header. only statusbar

				updateClockColor()
				if (BatteryBarView.hasInstance()) {
					refreshBatteryBar(BatteryBarView.getInstance()!!)
				}
			}

		//region mobile roaming
		val MobileIconsInteractorImplClass = ReflectedClass.of("com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractorImpl")

		//we must use the classes defined in the apk. using our own will fail
		val ReadonlyStateFlowClass = ReflectedClass.of("kotlinx.coroutines.flow.ReadonlyStateFlow")

		MobileIconsInteractorImplClass
			.after("getMobileConnectionInteractorForSubId")
			.run { param ->
				if (HideRoamingState) {
					setObjectField(param.getResult<Any?>(), "isRoaming", ReadonlyStateFlowClass.clazz!!.constructors[0].newInstance(getStateFlowImplOf(false)))
				}
			}
		//endregion
	}

	private fun createCenterIconArea() {
		mCenteredIconArea = LinearLayout(mContext)
		val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
		lp.gravity = Gravity.CENTER
		mCenteredIconArea!!.layoutParams = lp
		mPhoneStatusbarView!!.addView(mCenteredIconArea!!)
	}

	private fun updateClockColor() {
		if (mClockView == null) return

		currentClockColor = mClockView!!.textColors.defaultColor

		for (callback in mTextColorCallbacks) {
			callback.onTextColorChanged(currentClockColor)
		}
	}

	private fun updateStatusbarHeight() {
		try {
			callMethod(mPhoneStatusbarView, "updateStatusBarHeight")
		} catch (ignored: Throwable) {
		}
	}

	//region double row left area
	@SuppressLint("DiscouragedApi")
	private fun makeLeftSplitArea() {
		mNotificationIconContainer = mPhoneStatusbarView!!.findViewById(SystemUtils.idOf("notificationIcons"))

		mNotificationContainerContainer = LinearLayout(mContext)
		mNotificationContainerContainer!!.clipChildren = false //allowing headsup icon to go beyond

		if (mLeftVerticalSplitContainer == null) {
			mLeftVerticalSplitContainer = LinearLayout(mContext)
			mLeftVerticalSplitContainer!!.clipChildren = false //allowing headsup icon to go beyond
		} else {
			mLeftVerticalSplitContainer!!.removeAllViews()
			if (mLeftVerticalSplitContainer!!.parent != null)
				(mLeftVerticalSplitContainer!!.parent as ViewGroup).removeView(mLeftVerticalSplitContainer!!)
		}

		mLeftVerticalSplitContainer!!.orientation = LinearLayout.VERTICAL
		val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
		val margin = ResourceTools.dpToPx(mContext, 4)
		lp.topMargin = margin
		lp.bottomMargin = margin

		mLeftVerticalSplitContainer!!.layoutParams = lp
		mLeftVerticalSplitContainer!!.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
			setHeights()
		}

		val layoutTransition = LayoutTransition()
		layoutTransition.enableTransitionType(LayoutTransition.CHANGING)
		layoutTransition.setDuration(200)
		mLeftVerticalSplitContainer!!.layoutTransition = layoutTransition

		mLeftExtraRowContainer = ShyLinearLayout(mContext)
		if (!multiRowClockBottom) {
			mLeftVerticalSplitContainer!!.addView(mLeftExtraRowContainer!!, 0)
		}

		val parent = mNotificationIconContainer!!.parent as ViewGroup

		parent.addView(mLeftVerticalSplitContainer!!, parent.indexOfChild(mNotificationIconContainer!!))
		parent.removeView(mNotificationIconContainer!!)
		mLeftVerticalSplitContainer!!.addView(mNotificationContainerContainer!!)

		if (multiRowClockBottom) {
			mLeftVerticalSplitContainer!!.addView(mLeftExtraRowContainer!!)
		}

		repositionOngoingChip()

		mNotificationContainerContainer!!.addView(mNotificationIconContainer!!)

		(mNotificationIconContainer!!.layoutParams as LinearLayout.LayoutParams).weight = 100f
		mNotificationIconContainer!!.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
			override fun onChildViewAdded(parent: View, child: View) {
				mNotificationContainerContainer!!.visibility = View.VISIBLE
				setHeights()
			}

			override fun onChildViewRemoved(parent: View, child: View) {
				if (mNotificationIconContainer!!.childCount == 0) {
					mNotificationContainerContainer!!.visibility = View.GONE
					setHeights()
				}
			}
		})

		(mStatusbarStartSide!!.parent as View).layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
		mStatusbarStartSide!!.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
		mLeftVerticalSplitContainer!!.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
	}

	private fun repositionOngoingChip() {
		if (isJetpackClock) return // In Android 17, the ComposeView contains the Clock, so we can't move it!
		val ongoingChipComposeView = findComposeView(mPhoneStatusbarView!!.findViewById(SystemUtils.idOf("status_bar_start_side_except_heads_up")))
		reAddView(mNotificationContainerContainer!!, ongoingChipComposeView)
	}

	private fun findComposeView(parent: ViewGroup?): View? {
		if (parent == null) return null
		for (i in 0 until parent.childCount) {
			val child = parent.getChildAt(i)
			if (child != null && child.javaClass.name.endsWith("ComposeView"))
				return child
		}
		return null
	}

	private fun setHeights() {
		@SuppressLint("DiscouragedApi")
		val statusbarHeight = mPhoneStatusbarView!!.layoutParams.height
			- mContext.resources.getDimensionPixelSize(SystemUtils.dimenIdOf("status_bar_padding_top"))

		mNotificationContainerContainer!!.layoutParams.height = if (mLeftExtraRowContainer!!.visibility == View.VISIBLE) statusbarHeight / 2 else ViewGroup.LayoutParams.MATCH_PARENT
		mLeftExtraRowContainer!!.layoutParams.height = if (mNotificationContainerContainer!!.visibility == View.VISIBLE) statusbarHeight / 2 else ViewGroup.LayoutParams.MATCH_PARENT
		if (networkOnSBEnabled && networkTrafficSB?.layoutParams != null) {
			networkTrafficSB!!.layoutParams.height = statusbarHeight / (if (networkTrafficPosition == POSITION_LEFT && notificationAreaMultiRow) 2 else 1)
		}
	}
	//endregion

	//region battery bar related
	private fun refreshBatteryBar(instance: BatteryBarView) {
		BatteryBarView.setStaticColor(batteryLevels, batteryColors, indicateCharging, chargingColor, indicateFastCharging, fastChargingColor, indicatePowerSave, powerSaveColor, BBarTransitColors, BBAnimateCharging)
		instance.visibility = if (BBarEnabled) View.VISIBLE else View.GONE
		instance.setColorful(BBarColorful)
		instance.setOnlyWhileCharging(BBOnlyWhileCharging)
		instance.setOnTop(!BBOnBottom)
		instance.setAlphaPct(BBOpacity)
		instance.setBarHeight(Math.round(BBarHeight / 10f) + 5)
		instance.setCenterBased(BBSetCentered)
		instance.refreshLayout()
	}

	private fun placeBatteryBar() {
		try {
			val batteryBarView = BatteryBarView.getInstance(mContext)!!
			try {
				(batteryBarView.parent as ViewGroup).removeView(batteryBarView)
			} catch (ignored: Throwable) {
			}
			mPhoneStatusbarView!!.addView(batteryBarView)
			refreshBatteryBar(BatteryBarView.getInstance()!!)
		} catch (ignored: Throwable) {
		}
	}
	//endregion

	//region statusbar icon holder
	private fun getStatusbarIconFor(icon: Icon, slotName: String): Any? {
		try {
			val statusbarIcon = ObjenesisHelper.newInstance(StatusBarIconClass!!.clazz)

			setObjectField(statusbarIcon, "visible", true)

			//noinspection JavaReflectionMemberAccess
			setObjectField(statusbarIcon, "user", UserHandle::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).newInstance(0))
			setObjectField(statusbarIcon, "pkg", BuildConfig.APPLICATION_ID)
			setObjectField(statusbarIcon, "icon", icon)
			setObjectField(statusbarIcon, "iconLevel", 0)
			setObjectField(statusbarIcon, "number", 0)
			setObjectField(statusbarIcon, "contentDescription", slotName)

			try {
				@Suppress("UNCHECKED_CAST")
				val typeEnum = Class.forName("com.android.internal.statusbar.StatusBarIcon\$Type") as Class<out Enum<*>>
				setObjectField(statusbarIcon, "type", java.lang.Enum.valueOf(typeEnum as Class, "SystemIcon"))
			} catch (ignored: Throwable) {
			}

			try {
				@Suppress("UNCHECKED_CAST")
				val shapeEnum = Class.forName("com.android.internal.statusbar.StatusBarIcon\$Shape") as Class<out Enum<*>>
				setObjectField(statusbarIcon, "shape", java.lang.Enum.valueOf(shapeEnum as Class, "WRAP_CONTENT"))
			} catch (ignored: Throwable) {
			}

			return statusbarIcon
		} catch (ignored: Throwable) {
			return null
		}
	}

	private fun getStatusbarIconHolderFor(statusbarIcon: Any?): Any? {
		if (statusbarIcon == null || StatusBarIconHolderClass?.clazz == null) return null
		val holder = ObjenesisHelper.newInstance(StatusBarIconHolderClass!!.clazz)
		var iconFiled: String? = null
		val clazz = StatusBarIconHolderClass!!.clazz!!
		(clazz.declaredFields + clazz.fields).forEach { field ->
			val name = field.name.lowercase(Locale.getDefault())
			if (name.contains("icon") && !name.contains("type")) {
				iconFiled = field.name
			}
		}

		setObjectField(holder, iconFiled ?: "mIcon", statusbarIcon)

		return holder
	}

	//endregion

	//region vo_data related
	private fun initVoData() {
		try {
			if (!telephonyCallbackRegistered) {
				val volteIcon = Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_volte)
				val volteStatusbarIcon = getStatusbarIconFor(volteIcon, VO_LTE_SLOT)
				this.volteStatusbarIcon = volteStatusbarIcon
				volteStatusbarIconHolder = getStatusbarIconHolderFor(volteStatusbarIcon)

				val vonrIcon = Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_vonr)
				val vonrStatusbarIcon = getStatusbarIconFor(vonrIcon, VO_LTE_SLOT)
				this.vonrStatusbarIcon = vonrStatusbarIcon
				vonrStatusbarIconHolder = getStatusbarIconHolderFor(vonrStatusbarIcon)

				val vowifiIcon = Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_vowifi)
				val vowifiStatusbarIcon = getStatusbarIconFor(vowifiIcon, VO_WIFI_SLOT)
				this.vowifiStatusbarIcon = vowifiStatusbarIcon
				vowifiStatusbarIconHolder = getStatusbarIconHolderFor(vowifiStatusbarIcon)

				//noinspection DataFlowIssue
				SystemUtils.TelephonyManager()!!.registerTelephonyCallback(voDataExec, voDataCallback)
				telephonyCallbackRegistered = true
			} else {
				if (volteStatusbarIcon == null) {
					val volteIcon = Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_volte)
					volteStatusbarIcon = getStatusbarIconFor(volteIcon, VO_LTE_SLOT)
					volteStatusbarIconHolder = getStatusbarIconHolderFor(volteStatusbarIcon)
				}
				if (vonrStatusbarIcon == null) {
					val vonrIcon = Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_vonr)
					vonrStatusbarIcon = getStatusbarIconFor(vonrIcon, VO_LTE_SLOT)
					vonrStatusbarIconHolder = getStatusbarIconHolderFor(vonrStatusbarIcon)
				}
				if (vowifiStatusbarIcon == null) {
					val vowifiIcon = Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_vowifi)
					vowifiStatusbarIcon = getStatusbarIconFor(vowifiIcon, VO_WIFI_SLOT)
					vowifiStatusbarIconHolder = getStatusbarIconHolderFor(vowifiStatusbarIcon)
				}
			}
		} catch (ignored: Exception) {
		}

		adjustIconSlotsOrder()

		updateVoData(true)
	}

	private fun adjustIconSlotsOrder() {
		try {
			if (mStatusBarIconController == null) return

			var iconList: Any? = null
			for (f in mStatusBarIconController!!.javaClass.declaredFields) {
				if (f.type.name.endsWith("StatusBarIconList")) {
					f.isAccessible = true
					iconList = f.get(mStatusBarIconController)
					break
				}
			}

			if (iconList != null) {
				val slots = getObjectField(iconList, "mSlots") as? ArrayList<*> ?: return
				if (slots.isEmpty()) return

				val isString = slots[0] is String
				var targetIndex = -1

				if (isString) {
					@Suppress("UNCHECKED_CAST")
					val stringSlots = slots as ArrayList<String>
					targetIndex = stringSlots.indexOf("mobile")
					if (targetIndex == -1) targetIndex = stringSlots.indexOf("wifi")

					if (targetIndex != -1 && !stringSlots.contains(VO_LTE_SLOT)) {
						stringSlots.add(targetIndex, VO_WIFI_SLOT)
						stringSlots.add(targetIndex, VO_LTE_SLOT)
					}
				} else {
					for (i in slots.indices) {
						val slotObj = slots[i]
						val slotName = getObjectField(slotObj, "mName") as? String
						if ("mobile" == slotName) {
							targetIndex = i
							break
						}
					}

					if (targetIndex == -1) {
						for (i in slots.indices) {
							val slotObj = slots[i]
							val slotName = getObjectField(slotObj, "mName") as? String
							if ("wifi" == slotName) {
								targetIndex = i
								break
							}
						}
					}

					if (targetIndex != -1) {
						for (slotObj in slots) {
							val name = getObjectField(slotObj, "mName") as? String
							if (VO_LTE_SLOT == name) return
						}

						val slotClass = slots[0]!!.javaClass
						var volteSlotObj: Any?
						var vowifiSlotObj: Any?

						try {
							val constructor = slotClass.getDeclaredConstructor(String::class.java)
							constructor.isAccessible = true
							volteSlotObj = constructor.newInstance(VO_LTE_SLOT)
							vowifiSlotObj = constructor.newInstance(VO_WIFI_SLOT)
						} catch (t: Throwable) {
							try {
								val constructor = slotClass.getDeclaredConstructor(String::class.java, StatusBarIconHolderClass!!.clazz)
								constructor.isAccessible = true
								volteSlotObj = constructor.newInstance(VO_LTE_SLOT, null)
								vowifiSlotObj = constructor.newInstance(VO_WIFI_SLOT, null)
							} catch (ignored: Throwable) {
								volteSlotObj = ObjenesisHelper.newInstance(slotClass)
								setObjectField(volteSlotObj, "mName", VO_LTE_SLOT)

								vowifiSlotObj = ObjenesisHelper.newInstance(slotClass)
								setObjectField(vowifiSlotObj, "mName", VO_WIFI_SLOT)
							}
						}

						@Suppress("UNCHECKED_CAST")
						val objSlots = slots as ArrayList<Any>

						objSlots.add(targetIndex, vowifiSlotObj!!)
						objSlots.add(targetIndex, volteSlotObj!!)
					}
				}
			}
		} catch (ignored: Throwable) {
		}
	}

	private fun removeVoDataCallback() {
		try {
			//noinspection DataFlowIssue
			SystemUtils.TelephonyManager()!!.unregisterTelephonyCallback(voDataCallback)
			telephonyCallbackRegistered = false
		} catch (ignored: Exception) {
		}
		removeSBIconSlot(VO_LTE_SLOT)
		removeSBIconSlot(VO_WIFI_SLOT)
	}

	private inner class serverStateCallback : TelephonyCallback(),
		TelephonyCallback.ServiceStateListener,
		TelephonyCallback.DisplayInfoListener,
		TelephonyCallback.DataConnectionStateListener {
		override fun onServiceStateChanged(serviceState: ServiceState) {
			updateVoData(false)
		}

		override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
			updateVoData(false)
		}

		override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
			updateVoData(false)
		}
	}

	private fun updateVoData(force: Boolean) {
		val tm = SystemUtils.TelephonyManager() ?: return

		val voWifiAvailable = callMethod(tm, "isWifiCallingAvailable") as Boolean
		var volteStateAvailable = callMethod(tm, "isVolteAvailable") as Boolean
		var isNR = false

		try {
			val isImsRegistered = callMethod(tm, "isImsRegistered") as Boolean

			val dataNetworkType = callMethod(tm, "getDataNetworkType") as Int
			var voiceNetworkType = 0
			try {
				voiceNetworkType = callMethod(tm, "getVoiceNetworkType") as Int
			} catch (ignored: Throwable) {
			}

			isNR = if (voiceNetworkType != 0) {
				voiceNetworkType == 20
			} else {
				dataNetworkType == 20
			}

			if (isImsRegistered && !voWifiAvailable) {
				volteStateAvailable = true
			}
		} catch (ignored: Throwable) {
		}

		if (lastVolteAvailable != volteStateAvailable || lastIsNR != isNR || force) {
			lastVolteAvailable = volteStateAvailable
			lastIsNR = isNR
			if (volteStateAvailable && VolteIconEnabled) {
				if (isNR && vonrStatusbarIcon != null && vonrStatusbarIconHolder != null) {
					setSBIcon(VO_LTE_SLOT, vonrStatusbarIconHolder, vonrStatusbarIcon)
				} else {
					setSBIcon(VO_LTE_SLOT, volteStatusbarIconHolder, volteStatusbarIcon)
				}
			} else {
				removeSBIconSlot(VO_LTE_SLOT)
			}
		}

		if (lastVowifiAvailable != voWifiAvailable || force) {
			lastVowifiAvailable = voWifiAvailable
			if (voWifiAvailable && VowifiIconEnabled) {
				setSBIcon(VO_WIFI_SLOT, vowifiStatusbarIconHolder, vowifiStatusbarIcon)
			} else {
				removeSBIconSlot(VO_WIFI_SLOT)
			}
		}
	}

	private val mainHandler = Handler(Looper.getMainLooper())

	private fun runOnMainThread(action: () -> Unit) {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			action()
		} else {
			mainHandler.post(action)
		}
	}

	private fun setSBIcon(slot: String, holder: Any?, icon: Any?) {
		runOnMainThread {
			if (!isLegacyStatusBar && mCommandQueue != null && icon != null) {
				try {
					callMethod(mCommandQueue, "setIcon", slot, icon)
					return@runOnMainThread
				} catch (ignored: Throwable) {
				}
			}
			if (mStatusBarIconController != null && holder != null) {
				try {
					callMethod(mStatusBarIconController, "setIcon", slot, holder)
					return@runOnMainThread
				} catch (ignored: Throwable) {
				}
			}
			if (mCommandQueue != null && icon != null) {
				try {
					callMethod(mCommandQueue, "setIcon", slot, icon)
				} catch (ignored: Throwable) {
				}
			}
		}
	}

	private fun removeSBIconSlot(slot: String) {
		runOnMainThread {
			if (!isLegacyStatusBar && mCommandQueue != null) {
				try {
					callMethod(mCommandQueue, "removeIcon", slot)
					return@runOnMainThread
				} catch (ignored: Throwable) {
				}
			}
			if (mStatusBarIconController != null) {
				try {
					callMethod(mStatusBarIconController, "removeAllIconsForSlot", slot, false)
					return@runOnMainThread
				} catch (ignored: Throwable) {
				}
			}
			if (mCommandQueue != null) {
				try {
					callMethod(mCommandQueue, "removeIcon", slot)
				} catch (ignored: Throwable) {
				}
			}
		}
	}
	//endregion

	//region network traffic related
	private fun removeNetworkTrafficViews(parent: ViewGroup?) {
		if (parent == null) return
		for (i in parent.childCount - 1 downTo 0) {
			val child = parent.getChildAt(i)
			if (child is NetworkTraffic) {
				parent.removeViewAt(i)
			}
		}
	}

	private fun placeNTSB() {
		if (networkTrafficSB == null) {
			if (networkOnSBEnabled) {
				networkTrafficSB = NetworkTraffic.getSBInstance(mContext)
			} else {
				return
			}
		}
		try {
			if (networkTrafficSB!!.parent != null) {
				(networkTrafficSB!!.parent as ViewGroup).removeView(networkTrafficSB!!)
			}
		} catch (ignored: Exception) {
		}
		if (!networkOnSBEnabled) return

		try {
			val res = mContext.resources
			val dm = res.displayMetrics

			val ntSpacing = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5.5f, dm))
			val ntSpacingCenter = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5.0f, dm))

			when (networkTrafficPosition) {
				POSITION_RIGHT -> {
					if (mSystemIconArea != null && mSystemIconArea!!.parent != null) {
						val systemIcons = mSystemIconArea!!.parent as ViewGroup
						val endSideContent = systemIcons.parent as? ViewGroup
						if (endSideContent != null) {
							removeNetworkTrafficViews(endSideContent)
							var insertIndex = endSideContent.indexOfChild(systemIcons)
							if (insertIndex == -1) insertIndex = 0
							endSideContent.addView(networkTrafficSB!!, insertIndex)
							networkTrafficSB!!.setPaddingRelative(ntSpacing, 0, ntSpacing, 0)
						}
					}
				}
				POSITION_LEFT -> {
					if (notificationAreaMultiRow && mLeftExtraRowContainer != null) {
						removeNetworkTrafficViews(mLeftExtraRowContainer)
						mLeftExtraRowContainer!!.addView(networkTrafficSB!!, mLeftExtraRowContainer!!.childCount)
					} else if (mStatusbarStartSide != null) {
						removeNetworkTrafficViews(mStatusbarStartSide)
						mStatusbarStartSide!!.addView(networkTrafficSB!!, 1)
					}
					networkTrafficSB!!.setPaddingRelative(ntSpacingCenter, 0, ntSpacingCenter, 0)
				}
				POSITION_CENTER -> {
					if (mStatusbarStartSide != null) {
						removeNetworkTrafficViews(mStatusbarStartSide)
						mStatusbarStartSide!!.addView(networkTrafficSB!!)
					}
					networkTrafficSB!!.setPaddingRelative(ntSpacingCenter, 0, ntSpacingCenter, 0)
				}
			}
			if (networkTrafficSB!!.layoutParams is LinearLayout.LayoutParams) {
				val ntsbLayoutP = networkTrafficSB!!.layoutParams as LinearLayout.LayoutParams
				ntsbLayoutP.gravity = Gravity.CENTER_VERTICAL
				networkTrafficSB!!.layoutParams = ntsbLayoutP
			}
		} catch (ignored: Throwable) {
		}
	}

	private fun placeNTKeyguard() {
		if (mKeyguardStatusBarView == null) return
		if (networkTrafficKeyguard == null) {
			if (networkOnSBEnabled) {
				networkTrafficKeyguard = NetworkTraffic.getKeyguardInstance(mContext)
			} else {
				return
			}
		}
		try {
			if (networkTrafficKeyguard!!.parent != null) {
				(networkTrafficKeyguard!!.parent as ViewGroup).removeView(networkTrafficKeyguard)
			}
		} catch (ignored: Exception) {
		}
		if (!networkOnSBEnabled) return

		try {
			val res = mContext.resources
			val dm = res.displayMetrics

			val ntSpacing = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5.5f, dm))
			val ntSpacingCenter = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5.0f, dm))

			when (networkTrafficPosition) {
				POSITION_RIGHT -> {
					val statusIconArea = mKeyguardStatusBarView!!.findViewById<View>(SystemUtils.idOf("status_icon_area"))
					val systemIconsContainer = mKeyguardStatusBarView!!.findViewById<View>(SystemUtils.idOf("system_icons_container"))
					if (statusIconArea is ViewGroup) {
						removeNetworkTrafficViews(statusIconArea)
						var insertIndex = if (systemIconsContainer != null) statusIconArea.indexOfChild(systemIconsContainer) else -1
						if (insertIndex == -1) insertIndex = 0
						statusIconArea.addView(networkTrafficKeyguard, insertIndex)
						networkTrafficKeyguard!!.setPaddingRelative(ntSpacing, 0, ntSpacing, 0)

						if (networkTrafficKeyguard!!.layoutParams is LinearLayout.LayoutParams) {
							val lp = networkTrafficKeyguard!!.layoutParams as LinearLayout.LayoutParams
							lp.gravity = Gravity.CENTER_VERTICAL
							networkTrafficKeyguard!!.layoutParams = lp
						}
					}
				}
				POSITION_LEFT -> {
					removeNetworkTrafficViews(mKeyguardStatusBarView)
					val rlpLeft = RelativeLayout.LayoutParams(
						ViewGroup.LayoutParams.WRAP_CONTENT,
						ViewGroup.LayoutParams.WRAP_CONTENT
					)
					rlpLeft.addRule(RelativeLayout.ALIGN_PARENT_START)
					rlpLeft.addRule(RelativeLayout.CENTER_VERTICAL)
					networkTrafficKeyguard!!.setPaddingRelative(ntSpacingCenter, 0, ntSpacingCenter, 0)
					mKeyguardStatusBarView!!.addView(networkTrafficKeyguard, rlpLeft)
				}
				POSITION_CENTER -> {
					removeNetworkTrafficViews(mKeyguardStatusBarView)
					val rlpCenter = RelativeLayout.LayoutParams(
						ViewGroup.LayoutParams.WRAP_CONTENT,
						ViewGroup.LayoutParams.WRAP_CONTENT
					)
					rlpCenter.addRule(RelativeLayout.CENTER_IN_PARENT)
					networkTrafficKeyguard!!.setPaddingRelative(ntSpacingCenter, 0, ntSpacingCenter, 0)
					mKeyguardStatusBarView!!.addView(networkTrafficKeyguard, rlpCenter)
				}
			}
		} catch (ignored: Throwable) {
		}
	}
	//endregion

	//region icon tap related
	internal inner class ClickListener : View.OnClickListener, View.OnLongClickListener {
		override fun onClick(v: View) {
			val name = mContext.resources.getResourceName(v.id)

			if (name.endsWith("clock")) {
				callMethod(mActivityStarter, "postStartActivityDismissingKeyguard", Intent(AlarmClock.ACTION_SHOW_ALARMS), 0)
			} else if (name.endsWith("date")) {
				val builder = CalendarContract.CONTENT_URI.buildUpon()
				builder.appendPath("time")
				builder.appendPath(System.currentTimeMillis().toString())
				val todayIntent = Intent(Intent.ACTION_VIEW, builder.build())
				callMethod(mActivityStarter, "postStartActivityDismissingKeyguard", todayIntent, 0)
			}
		}

		override fun onLongClick(v: View): Boolean {
			val name = mContext.resources.getResourceName(v.id)

			if (name.endsWith("clock") || name.endsWith("date")) {
				val mIntent = Intent(Intent.ACTION_MAIN)
				mIntent.setClassName(
					"com.android.settings",
					"com.android.settings.Settings\$DateTimeSettingsActivity",
				)
				callMethod(mActivityStarter, "startActivity", mIntent, true /* dismissShade */)
				return true
			}
			return false
		}
	}
	//endregion

	//region clock and date related
	private fun placeClock() {
		var viewToMove: View? = mClockView
		if (isJetpackClock) {
			if (mJetpackClockView != null && mJetpackClockView!!.parent == null) {
				mJetpackClockView = null // View is detached or destroyed, find it again
			}
			if (mJetpackClockView == null) {
				// The clock is natively created in mStatusbarStartSide, search there.
				// If it's already moved, mJetpackClockView would not be null.
				// We don't search the right side to avoid accidentally matching the battery ComposeView.
				mJetpackClockView = findComposeView(mStatusbarStartSide)
			}
			if (mJetpackClockView != null) {
				viewToMove = mJetpackClockView
			}
		}

		val parent = viewToMove!!.parent as ViewGroup?
		var targetArea: ViewGroup? = null
		var index: Int? = null

		when (clockPosition) {
			POSITION_LEFT -> {
				if (notificationAreaMultiRow) {
					targetArea = mLeftExtraRowContainer
					index = 0
				} else {
					targetArea = mStatusbarStartSide
					index = 1
				}
				viewToMove!!.setPadding(0, 0, leftClockPadding, 0)
			}
			POSITION_CENTER -> {
				targetArea = mCenteredIconArea as ViewGroup?
				viewToMove!!.setPadding(rightClockPadding, 0, rightClockPadding, 0)
			}
			POSITION_RIGHT -> {
				viewToMove!!.setPadding(rightClockPadding, 0, 0, 0)
				targetArea = mSystemIconArea!!.parent as ViewGroup?
				// Leaving index as null appends it to the very end (right-most element)
			}
		}

		if (targetArea == null || targetArea == parent) return

		if (isJetpackClock) {
			isMovingClock = true
		}
		try {
			if (parent != null) parent.removeView(viewToMove!!)

			if (isJetpackClock && viewToMove == mJetpackClockView) {
				val lp = viewToMove!!.layoutParams
				if (lp != null) {
					lp.width = if (clockPosition == POSITION_RIGHT) {
						ViewGroup.LayoutParams.WRAP_CONTENT
					} else {
						ViewGroup.LayoutParams.MATCH_PARENT
					}
					viewToMove!!.layoutParams = lp
				}
			}

			if (index != null) {
				targetArea.addView(viewToMove!!, index)
			} else {
				targetArea.addView(viewToMove!!)
			}
		} finally {
			isMovingClock = false
		}
	}

	private val stringFormatter = StringFormatter()

	private fun getFormattedString(dateFormat: String, small: Boolean, @Nullable @ColorInt textColor: Int?): CharSequence {
		if (dateFormat.isEmpty()) return ""

		//There's some format to work on
		val formatted = SpannableStringBuilder(stringFormatter.formatString(dateFormat))

		if (small) {
			//small size requested
			val style: CharacterStyle = RelativeSizeSpan(0.7f)
			formatted.setSpan(style, 0, formatted.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
		}
		if (textColor != null) {
			formatted.setSpan(NetworkTraffic.TrafficStyle(textColor), 0, formatted.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		}

		return formatted
	}

	//endregion
	//region callbacks
	fun interface ClockVisibilityCallback : Callback {
		fun OnVisibilityChanged(isVisible: Boolean)
	}

	private fun wifiVisibleChanged() {
		try { //don't crash the system if failed
			//inspired from from TunerServiceImpl#reloadAll
			var hideListString = Settings.Secure.getString(
				getObjectField(mTunerService, "mContentResolver") as ContentResolver,
				ICON_HIDE_LIST,
			)

			if (CombineSignalIcons && mWifiVisible) {
				if (hideListString == null || hideListString.isEmpty()) {
					hideListString = "mobile"
				} else if (!hideListString.contains("mobile")) {
					hideListString = "$hideListString,mobile"
				}
			}
			@Suppress("UNCHECKED_CAST")
			val tunables = callMethod(getObjectField(mTunerService, "mTunableLookup"), "get", ICON_HIDE_LIST) as Set<Any?>

			val finalHideListString = hideListString
			mPhoneStatusbarView!!.post {
				for (tunable in tunables) {
					callMethod(tunable, "onTuningChanged", ICON_HIDE_LIST, finalHideListString)
				}
			}
		} catch (ignored: Throwable) {
		}
	}
	//endregion

	fun interface StatusbarTextColorCallback {
		fun onTextColorChanged(textColor: Int)
	}
}
