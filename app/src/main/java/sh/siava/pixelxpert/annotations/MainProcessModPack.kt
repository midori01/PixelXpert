package sh.siava.pixelxpert.annotations

/**
 * Marks a mod pack that also targets the app's main process.
 *
 * By default every pack is assumed to be main-process-only; this annotation
 * only matters alongside [ChildProcessModPack], where it says "this pack runs
 * in both".
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
annotation class MainProcessModPack
