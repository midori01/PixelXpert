import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class CopyApkTask : DefaultTask() {
	@get:InputDirectory
	abstract val apkDir: DirectoryProperty

	@get:OutputDirectory
	abstract val outputDir: DirectoryProperty

	@TaskAction
	fun copy() {
		val apkDirFile = apkDir.get().asFile
		val outputDirFile = outputDir.get().asFile.apply { mkdirs() }
		val apkFile = apkDirFile.walkTopDown().firstOrNull { it.extension == "apk" }
		if (apkFile != null) {
			val target = File(outputDirFile, "PixelXpert.apk")
			apkFile.copyTo(target, overwrite = true)
			logger.lifecycle("copyApk: Copied ${apkFile.name} to ${target.absolutePath}")
		} else {
			logger.warn("copyApk: No APK found in ${apkDirFile.absolutePath}")
		}
	}
}
