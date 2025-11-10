plugins {
    id("java")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly(project(":"))
    implementation("net.dv8tion:JDA:5.0.0-beta.20")
}

tasks.jar {
    archiveBaseName.set("DiscordSRV-MultiBridge")
    from("src/main/resources") {
        include("plugin.yml", "config.yml")
    }
}
