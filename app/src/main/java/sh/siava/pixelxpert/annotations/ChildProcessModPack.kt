package sh.siava.pixelxpert.annotations

/**
 * Marks a mod pack that targets a child process rather than the main one.
 *
 * When this is present the pack only also runs in the main process if
 * [MainProcessModPack] is present alongside it.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
annotation class ChildProcessModPack(
	/** A unique part of the process name, matched with `String.contains()`. */
	val processNameContains: String,
)
