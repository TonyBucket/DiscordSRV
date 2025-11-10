# DiscordSRV-MultiBridge

DiscordSRV-MultiBridge is a standalone Spigot addon for [DiscordSRV](https://github.com/DiscordSRV/DiscordSRV) that allows a single Minecraft server to connect to multiple Discord bots and guilds at the same time. The original DiscordSRV bot continues to operate normally while the addon spins up extra JDA clients for any additional tokens you configure.

## Features
- Leave the main DiscordSRV bot untouched while spinning up extra JDA clients for more bots
- Map multiple Discord guilds and channels to in-game chat tags
- Async, rate-limited message routing to avoid blocking the Bukkit main thread
- Optional low-memory mode to disable expensive Discord features

## Building
Run the Gradle build from the repository root:

```bash
./gradlew :addon-multibridge:build
```

The compiled addon will be located at `addon-multibridge/build/libs/DiscordSRV-MultiBridge.jar`.

## Installation
1. Ensure the original DiscordSRV plugin is installed on your server.
2. Copy `addon-multibridge/build/libs/DiscordSRV-MultiBridge.jar` into your server's `plugins/` folder.
3. Start the server once to generate the configuration at `plugins/DiscordSRV-MultiBridge/config.yml`.
4. Edit the configuration to define additional bots, guilds, and channel mappings.

## Configuration
Refer to `src/main/resources/config.yml` for an example configuration. Each bot definition specifies a Discord bot token and the guild/channels it should mirror. Channels can be tagged so you can control which in-game messages are broadcast where.

## Usage Notes
- All Discord I/O runs asynchronously on a fixed-size executor service.
- Each bot is protected by a configurable token-bucket rate limiter.
- Low memory mode minimizes Discord presence updates for lightweight environments.

Enjoy bridging more communities with DiscordSRV!
