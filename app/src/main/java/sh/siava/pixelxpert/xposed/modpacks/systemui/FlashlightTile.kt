package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraManager
import androidx.core.content.res.ResourcesCompat
import de.robv.android.xposed.XposedHelpers.getObjectField
import de.robv.android.xposed.XposedHelpers.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.AlertSlider
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedMethod
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Turns SystemUI's flashlight tile into a strength-level tile with a slider.
 *
 * The stock tile only knows on and off, so this pack hooks `FlashlightTile`,
 * `FlashlightTileWithLevel` and `FlashlightRepositoryImpl` to keep a persisted
 * strength in the `flashPCT` preference: the icon is painted by [LevelDrawable]
 * (filled over outline as a percentage of the torch's max level), the saved
 * level is pushed back into SystemUI's repository whenever it loads or writes
 * flashlight info, and long/secondary clicks open an [AlertSlider] instead of
 * the plain toggle so dragging writes the same preference the tile reads. With
 * `AnimateFlashlight` on, `CameraManager.turnOnTorchWithStrengthLevel` is
 * intercepted so the module's own torch animation runs and the framework call
 * is cancelled, which is why every torch path here goes through
 * [SystemUtils.setFlash] rather than the camera service.
 */
@SystemUIModPack
class FlashlightTile(context: Context) : XposedModPack(context) {

	private var leveledFlashTile = false
	private var AnimateFlashlight = false
	private var mTile: Any? = null
	private var mLevelDrawable: LevelDrawable? = null
	private val mFlashLevelListener: SystemUtils.ChangeListener = object : SystemUtils.ChangeListener {
		override fun onChanged(newVal: Int) {
			if (mTile != null) {
				mLevelDrawable!!.updateState(newVal)
				callMethod<Unit>(mTile, "refreshState")
			}
		}
	}

	override fun onPreferenceUpdated(vararg key: String?) {
		leveledFlashTile = Xprefs.getBoolean("leveledFlashTile", false)
		AnimateFlashlight = Xprefs.getBoolean("AnimateFlashlight", false)

		if (leveledFlashTile)
		{
			if (mLevelDrawable == null) {
				mLevelDrawable = LevelDrawable(mContext)
				mLevelDrawable!!.init(0, SystemUtils.getMaxFlashLevel(), R.drawable.qs_flashlight_on, R.drawable.qs_fashlight_on_outline, .1f, .1f, .3f)

				mLevelDrawable!!.updateState(SystemUtils.getFlashlightLevel(
						Xprefs.getInt("flashPCT", 50)
								/ 100f))
			}

			SystemUtils.registerFlashlightLevelListener(mFlashLevelListener)
		}
	}

	@Suppress("UNCHECKED_CAST")
	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val FlashlightTileClass = ReflectedClass.of("com.android.systemui.qs.tiles.FlashlightTile")
		val QSTileImplClass = ReflectedClass.of("com.android.systemui.qs.tileimpl.QSTileImpl")
		val DrawableIconClass = ReflectedClass.of("com.android.systemui.qs.tileimpl.QSTileImpl\$DrawableIcon")
		val FlashlightRepositoryImplClass = ReflectedClass.of("com.android.systemui.flashlight.data.repository.FlashlightRepositoryImpl")
		val FlashlightTileWithLevelClass = ReflectedClass.of("com.android.systemui.qs.tiles.FlashlightTileWithLevel")

		ReflectedClass.of(CameraManager::class.java)
			.before("turnOnTorchWithStrengthLevel")
			.run { param ->
				if (AnimateFlashlight) {
					val level = param.args[1] as Int
					SystemUtils.setFlash(true, level, !SystemUtils.isFlashOn())
					param.setResult(false)
				}
			}

		FlashlightTileWithLevelClass
			.after("handleUpdateState")
			.run { param ->
				if (leveledFlashTile) {
					if (mTile == null)
					{
						mTile = param.thisObject
					}

					val state = param.args[0]

					val isEnabled = getObjectField(state, "value") as Boolean

					if (isEnabled)
					{
						val icon = DrawableIconClass.clazz!!.constructors[0].newInstance(mLevelDrawable)
						setObjectField(state, "icon", icon)
					}
				}
			}

		FlashlightRepositoryImplClass //loading last flash pct upon sysui restart - SystemUI builtin leveled tile
			.after("loadFlashlightInfo")
			.run { param ->
				if (leveledFlashTile) {
					val getCurrentUserIdMethodName = FlashlightRepositoryImplClass.findMethods(Pattern.compile("getCurrentUserId.*", Pattern.CASE_INSENSITIVE)).iterator().next().name
					val currentUserId: Int = callMethod(param.thisObject, getCurrentUserIdMethodName)

					val defaultEnabledLevelForUser = getObjectField(param.thisObject, "defaultEnabledLevelForUser") as ConcurrentHashMap<Int, Int>

					defaultEnabledLevelForUser.put(currentUserId, SystemUtils.getFlashlightLevel(Xprefs.getInt("flashPCT", 50) / 100f))
				}
			}
		FlashlightRepositoryImplClass //saving flash level to SystemUI builtin leveled tile
			.after("setLevel")
			.run { param ->
				if (leveledFlashTile) {
					val persist = param.args[1] as Boolean
					if (persist) {
						val level = param.args[0] as Int
						Thread {
							Xprefs.edit().putInt("flashPCT", Math.round((level * 100f) / SystemUtils.getMaxFlashLevel())).apply()
						}.start()
					}
				}
			}

		FlashlightTileClass
			.before("newTileState") //constructor is optimized. this is a good substitute
			.run { param ->
				if (mTile == null)
					mTile = param.thisObject
			}

		FlashlightTileClass
			.before("handleClick")
			.run { param ->

				val state = getObjectField(param.thisObject, "mState")
				val handlesSecondary = getObjectField(state, "handlesSecondaryClick") as Boolean

				if (!handlesSecondary || !leveledFlashTile)
					return@run

				if (handleFlashLongClick())
				{
					param.setResult(null)
				}
			}

		FlashlightTileClass
			.after("handleUpdateState")
			.run { param ->
				val state = param.args[0]

				setObjectField(state, "handlesSecondaryClick", leveledFlashTile)

				if (leveledFlashTile) {
					val isEnabled = getObjectField(state, "value") as Boolean

					if (isEnabled)
					{
						val icon = DrawableIconClass.clazz!!.constructors[0].newInstance(mLevelDrawable)
						setObjectField(state, "icon", icon)
					}

					val subTitle = XPLauncher.moduleResources!!.getString(R.string.sliding_tile_subtitle)
					setObjectField(state, "secondaryLabel", subTitle)
				}
			}

		QSTileImplClass
			.before("handleSecondaryClick")
			.run { param ->
				if (leveledFlashTile && param.thisObject === mTile)
				{
					ReflectedMethod
							.ofName(FlashlightTileClass, "handleClick")
							.invokeOriginal(mTile, param.args[0])

					param.setResult(null) //otherwise it will also call click
				}
			}

		FlashlightTileClass
			.before("handleLongClick")
			.run { param ->
				if (leveledFlashTile && handleFlashLongClick())
				{
					param.setResult(null)
				}
			}

		FlashlightTileClass
			.after("newTileState")
			.run { param -> setObjectField(param.getResult(), "handlesLongClick", true) }
	}

	private fun handleFlashLongClick(): Boolean {
		showAlertSlider()

		return true
	}

	private fun showAlertSlider() {
		StatusbarGestures.collapseQSPanel()
		val flashlightSliderCallback = object : AlertSlider.SliderEventCallback {
			override fun onStartTrackingTouch(slider: Any?) {}

			override fun onStopTrackingTouch(slider: Any?) {
				val value: Float = callMethod(slider, "getValue")

				Thread {
					Xprefs.edit()
							.putInt("flashPCT",
									Math.round((value * 100f) / SystemUtils.getMaxFlashLevel())
							).apply()
				}.start()

				if (!SystemUtils.isFlashOn())
				{
					SystemUtils.setFlash(true, AnimateFlashlight)
				}
			}

			override fun onValueChange(slider: Any?, value: Float, fromUser: Boolean) {
				Thread {
					if (SystemUtils.isFlashOn()) {
						SystemUtils.setFlash(true, Math.round(value), false)
					}
				}.start()
			}

			override fun onCreate(alertSlider: AlertSlider) {
				SystemUtils.registerFlashlightLevelListener(alertSlider)
			}

			override fun onDismiss(alertSlider: AlertSlider) {
				SystemUtils.unregisterFlashlightLevelListener(alertSlider)
			}
		}

		val alertSlider = object : AlertSlider(
				mContext,
				SystemUtils.getFlashlightLevel(
						Xprefs.getInt("flashPCT", 50)
								/ 100f).toFloat(),
				1f,
				SystemUtils.getMaxFlashLevel().toFloat(),
				1f,
				flashlightSliderCallback) {
			override fun onChanged(newVal: Int) {
				setSliderCurrentValue(newVal.toFloat())
			}
		}

		alertSlider.show()
	}

	class LevelDrawable(private val mContext: Context) : Drawable() {
		private var mCurrentLevel = 0
		private var mMinLevel = 0
		private var mMaxLevel = 0
		private var mFilledResId = 0
		private var mOutlineResId = 0
		private var mPaddingTopPercent = 0f
		private var mPaddingBottomPercent = 0f
		private var mOutlineDeadZoneTopPercent = 0f // Dead zone for the outline icon

		private var mLoadedFilledDrawable: Drawable? = null
		private var mLoadedOutlineDrawable: Drawable? = null

		fun init(minLevel: Int, maxLevel: Int,
				 filledResId: Int, outlineResId: Int,
				 paddingTopPercent: Float, paddingBottomPercent: Float,
				 outlineDeadZoneTopPercent: Float) {

			this.mMinLevel = minLevel
			this.mMaxLevel = maxLevel
			this.mPaddingTopPercent = paddingTopPercent
			this.mPaddingBottomPercent = paddingBottomPercent
			this.mOutlineDeadZoneTopPercent = outlineDeadZoneTopPercent

			if (this.mLoadedFilledDrawable == null || this.mFilledResId != filledResId) {
				this.mLoadedFilledDrawable = ResourcesCompat.getDrawable(XPLauncher.moduleResources!!, filledResId, mContext.theme)
			}
			this.mFilledResId = filledResId

			if (this.mLoadedOutlineDrawable == null || this.mOutlineResId != outlineResId) {
				this.mLoadedOutlineDrawable = ResourcesCompat.getDrawable(XPLauncher.moduleResources!!, outlineResId, mContext.theme)
			}
			this.mOutlineResId = outlineResId

			invalidateSelf()
		}

		fun updateState(currentLevel: Int) {
			if (this.mCurrentLevel != currentLevel) {
				this.mCurrentLevel = currentLevel
				invalidateSelf()
			}
		}

		override fun draw(canvas: Canvas) {
			val bounds: Rect = getBounds()
			val width = bounds.width()
			val height = bounds.height()

			if (width <= 0 || height <= 0) {
				return
			}

			val overallPaddingTopPx = (height * mPaddingTopPercent).toInt()
			val overallPaddingBottomPx = (height * mPaddingBottomPercent).toInt()
			val contentActualHeight = height - overallPaddingTopPx - overallPaddingBottomPx

			var outlineDeadZonePx = (contentActualHeight * mOutlineDeadZoneTopPercent).toInt()
			if (outlineDeadZonePx < 0) outlineDeadZonePx = 0

			val effectiveProgressHeight = contentActualHeight - outlineDeadZonePx

			var levelPercentage = 0f
			if (mMaxLevel > mMinLevel) { // Avoid division by zero
				levelPercentage = (mCurrentLevel - mMinLevel).toFloat() / (mMaxLevel - mMinLevel).toFloat()
			}
			levelPercentage = Math.max(0f, Math.min(1f, levelPercentage)) // Clamp

			val filledPartOfEffectiveProgressHeight = (effectiveProgressHeight * levelPercentage).toInt()
			val outlinePartOfEffectiveProgressHeight = effectiveProgressHeight - filledPartOfEffectiveProgressHeight

			val splitYAbsolute = overallPaddingTopPx + outlineDeadZonePx + outlinePartOfEffectiveProgressHeight

			canvas.save()
			canvas.clipRect(0, splitYAbsolute, width, height - overallPaddingBottomPx)
			mLoadedFilledDrawable!!.setBounds(bounds)
			mLoadedFilledDrawable!!.draw(canvas)
			canvas.restore()

			canvas.save()
			canvas.clipRect(0, overallPaddingTopPx, width, splitYAbsolute)
			mLoadedOutlineDrawable!!.setBounds(bounds)
			mLoadedOutlineDrawable!!.draw(canvas)
			canvas.restore()
		}

		override fun setAlpha(alpha: Int) {
			mLoadedFilledDrawable!!.setAlpha(alpha)
			mLoadedOutlineDrawable!!.setAlpha(alpha)
			invalidateSelf()
		}

		override fun setColorFilter(colorFilter: ColorFilter?) {
			mLoadedFilledDrawable!!.setColorFilter(colorFilter)
			mLoadedOutlineDrawable!!.setColorFilter(colorFilter)
			invalidateSelf()
		}

		// The callback this overrides is deprecated, but SystemUI still calls it
		// and offers no replacement on this API level.
		@Suppress("OVERRIDE_DEPRECATION")
		override fun getOpacity(): Int {
			return PixelFormat.TRANSLUCENT
		}

		override fun getIntrinsicWidth(): Int {
			return mLoadedFilledDrawable!!.intrinsicWidth
		}

		override fun getIntrinsicHeight(): Int {
			return mLoadedOutlineDrawable!!.intrinsicHeight
		}
	}
}
