val taskGroup = "pixelxpert"

tasks.register("buildCanary") {
	group = taskGroup
	description = "Builds a Canary APK with count-based versioning"

	finalizedBy("incrementCanaryVersion")
		.finalizedBy("assembleRelease")
			.finalizedBy("copyApk")
}

tasks.register("buildStable") {
	group = taskGroup
	description = "Builds a Stable APK with Git Tag versioning"

	finalizedBy("incrementStableVersion")
		.finalizedBy("assembleRelease")
			.finalizedBy("copyApk")
}

tasks.register<IncrementVersionTask>("incrementCanaryVersion") {
	group = taskGroup
	description = "Increments the canary version and saves it to the related files"

	versionFile.set(rootProject.file("version.properties"))

	stable.set(false)

	filesToUpdate.from(
		rootProject.file("latestCanary.json"),
		rootProject.file("latestVersion.json")
	)
	targetVersionName.set(getVersionName())
}

tasks.register<IncrementVersionTask>("incrementStableVersion") {
	group = taskGroup
	description = "Increments the stable version and saves it to the related files"

	versionFile.set(rootProject.file("version.properties"))
	stable.set(true)
	filesToUpdate.from(
		rootProject.file("latestStable.json"),
		rootProject.file("latestVersion.json")
	)
	targetVersionName.set(getVersionName())
}

tasks.register<CopyApkTask>("copyApk") {
	group = taskGroup
	description = "Copies the release APK to the output directory"

	mustRunAfter("incrementCanaryVersion")
	mustRunAfter("incrementStableVersion")
	mustRunAfter("assembleRelease")

	apkDir.set(layout.buildDirectory.dir("outputs/apk/release"))
	outputDir.set(rootProject.layout.projectDirectory.dir("output"))
}