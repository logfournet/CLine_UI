plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "ai.cline"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2025.2.4")
        // Terminal integration: the Cline TUI is hosted in a terminal widget.
        bundledPlugin("org.jetbrains.plugins.terminal")
    }

    // Gson is bundled with the IDE at runtime; it is only needed on the
    // compile classpath (the plugin reads Cline's session JSON files).
    compileOnly("com.google.code.gson:gson:2.11.0")

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
        }

        changeNotes = """
            <h2>1.0.0</h2>
            <ul>
                <li>Initial release: hosts the Cline CLI TUI in a docked
                    <b>Cline</b> tool window (no editor tab), so the code stays
                    visible next to the agent.</li>
                <li>Session discovery + turn tracking from Cline's own session
                    store (<code>~/.cline/data/sessions</code>): status bar
                    spinner, completion notification.</li>
                <li>Native diff review with Accept / Reject for the files Cline
                    changed, plus Local History turn labels.</li>
                <li>Send files / selections as <code>@path#Lx-y</code> context,
                    clickable file links, drag &amp; drop, code actions.</li>
                <li>Per-project settings: binary path, start mode (Plan/Act),
                    auto-approve, model / provider, extra args, scrollback.</li>
            </ul>
        """.trimIndent()
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    withType<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask> {
        systemProperty("ide.no.platform.update", "true")
    }

    named("buildSearchableOptions") {
        enabled = false
    }

    named("prepareJarSearchableOptions") {
        enabled = false
    }
}
