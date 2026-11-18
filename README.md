# cshop

A chest-shop plugin for Spigot/Purpur 26.3. It provides player-owned shops,
unlimited administrator shops, Japanese/English menus and signs, configurable
shop displays, and Vault economy integration.

## Requirements

- Java 25
- Spigot or Purpur 26.3
- [Vault](https://github.com/MilkBowl/Vault)
- A Vault-compatible economy provider

## Build

```powershell
.\gradlew.bat build
```

The plugin JAR is written to `build/libs/`.

## Install

1. Install Vault and an economy provider on the server.
2. Copy the built JAR into the server's `plugins/` directory.
3. Start or restart the server.
4. Edit `plugins/cshop/config.yml` when needed, then restart the server.

## Commands

| Command | Permission | Description |
| --- | --- | --- |
| `/chestshop <buy-price> <sell-price>` | `chestshop.create` | Create a player shop while holding the product and looking at a chest, barrel, or shulker box. |
| `/admincshop <buy-price> <sell-price>` | `chestshop.admin` | Create an unlimited administrator shop. |

`chestshop.use` is granted to all players by default. `chestshop.admin` is OP-only by default.

## Configuration

```yaml
currency-symbol: '$'
max-shops-per-player: 30 # -1 makes the player-shop limit unlimited
disabled-worlds: []
hologram-display-distance: 24 # 0 disables the display
```

Shop displays and their item preview are created only when a player is within
the configured distance. The item preview is protected from pickup, merging,
hopper collection, damage, despawning, portals, and fishing.

## License

This project is licensed under the [GNU General Public License v3.0 or later](LICENSE).

