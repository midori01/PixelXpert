package sh.siava.pixelxpert.annotations

/**
 * Meta-annotation marking an Xposed *target annotation* such as
 * [sh.siava.pixelxpert.xposed.annotations.SystemUIModPack].
 *
 * The KSP processor (`:modPackProcessor`) finds every annotation carrying
 * this, then treats every class carrying one of those as a mod pack candidate.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.ANNOTATION_CLASS)
annotation class BaseModPack(
	/**
	 * The package name to match against, exactly and case-sensitively,
	 * or `""` for all packages.
	 */
	val targetPackage: String,
)
