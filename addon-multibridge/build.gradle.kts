import org.gradle.api.file.DuplicatesStrategy

plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://nexus.scarsz.me/content/groups/public/")
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly(project(":"))
    implementation("net.dv8tion:JDA:4.4.1_DiscordSRV.fix-7")
}

tasks {
    jar {
        enabled = false
    }

    shadowJar {
        archiveBaseName.set("DiscordSRV-MultiBridge")
        archiveClassifier.set("")
        archiveVersion.set("")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        from("src/main/resources") {
            include("plugin.yml", "config.yml")
        }

        val relocationPrefix = "com.wrs.multibridge.libs"
        relocate("net.dv8tion.jda", "$relocationPrefix.jda")
        relocate("club.minnced.discord.webhook", "$relocationPrefix.discord.webhook")
        relocate("com.neovisionaries.ws.client", "$relocationPrefix.neovisionaries.ws")
        relocate("com.iwebpp.crypto", "$relocationPrefix.iwebpp.crypto")
        relocate("okhttp3", "$relocationPrefix.okhttp3")
        relocate("okio", "$relocationPrefix.okio")
    }

    assemble {
        dependsOn(shadowJar)
    }

    build {
        dependsOn(shadowJar)
    }
}
