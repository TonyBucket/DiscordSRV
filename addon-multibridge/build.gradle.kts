import org.gradle.api.file.DuplicatesStrategy

plugins {
    id("java")
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
    compileOnly("net.dv8tion:JDA:4.4.1_DiscordSRV.fix-7")
}

tasks.jar {
    archiveBaseName.set("DiscordSRV-MultiBridge")
    archiveVersion.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from("src/main/resources") {
        include("plugin.yml", "config.yml")
    }
}
