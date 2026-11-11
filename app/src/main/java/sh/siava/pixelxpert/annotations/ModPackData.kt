package sh.siava.pixelxpert.annotations

/**
 * One entry in the generated mod pack table.
 *
 * The shape is
 * fixed because `:modPackProcessor` emits constructor calls against it.
 *
 * @property clazz the mod pack class, constructed with a `Context` argument
 * @property targetPackage package the pack applies to, `""` for every package
 * @property targetsMainProcess whether the pack runs in the main process
 * @property targetsChildProcess whether the pack runs in a child process
 * @property childProcessName substring the child process name must contain
 */
class ModPackData(
	@JvmField val clazz: Class<*>,
	@JvmField val targetPackage: String,
	@JvmField val targetsMainProcess: Boolean,
	@JvmField val targetsChildProcess: Boolean,
	@JvmField val childProcessName: String,
)
