import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.aware.SplitModeAware

plugins {
	id("java")
	alias(libs.plugins.kotlin)
	alias(libs.plugins.intelliJPlatform)
	alias(libs.plugins.changelog)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
	jvmToolchain(25)
}

repositories {
	mavenCentral()

	intellijPlatform {
		defaultRepositories()
	}
}

dependencies {
	// The platform test framework exposes JUnit 3-style `BasePlatformTestCase`, whose runner still lives in JUnit 4.
	testImplementation(libs.junit)

	intellijPlatform {
		create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
		bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })
		plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })
		testFramework(TestFrameworkType.Platform)
	}
}

intellijPlatform {
	buildSearchableOptions = false
	instrumentCode = true

	pluginConfiguration {
		name = providers.gradleProperty("pluginName")
		version = providers.gradleProperty("pluginVersion")

		// The plugin description is the README section between the two markers; removing them fails the build.
		description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
			val start = "<!-- Plugin description -->"
			val end = "<!-- Plugin description end -->"

			with(it.lines()) {
				if (!containsAll(listOf(start, end))) {
					throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
				}
				subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
			}
		}

		val changelog = project.changelog // local variable for configuration cache compatibility
		changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
			with(changelog) {
				renderItem(
					(getOrNull(pluginVersion) ?: getUnreleased())
						.withHeader(false)
						.withEmptySections(false),
					Changelog.OutputType.HTML,
				)
			}
		}

		ideaVersion {
			sinceBuild = providers.gradleProperty("pluginSinceBuild")
			untilBuild = providers.gradleProperty("pluginUntilBuild")
		}
	}

	signing {
		certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
		privateKey = providers.environmentVariable("PRIVATE_KEY")
		password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
	}

	publishing {
		token = providers.environmentVariable("PUBLISH_TOKEN")
		// A pre-release label (`2.1.7-alpha.3`) publishes to the matching Marketplace channel, everything else to `default`.
		channels =
			providers.gradleProperty("pluginVersion")
				.map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
	}

	pluginVerification {
		ides {
			recommended()
		}
	}
}

changelog {
	groups.empty()
	repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

tasks {
	wrapper {
		gradleVersion = providers.gradleProperty("gradleVersion").get()
	}

	publishPlugin {
		dependsOn(patchChangelog)
	}

	// The plugin is backend-only for now; switch to BOTH once a frontend module exists.
	runIdeSplitMode {
		pluginInstallationTarget = SplitModeAware.PluginInstallationTarget.BACKEND
	}
}
