package sh.siava.pixelxpert.annotations

/**
 * Load order for a mod pack. Absent, it defaults to 99; packs load from
 * small to large.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
annotation class ModPackPriority(val priority: Int)
