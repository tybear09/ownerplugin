# OwnerGuard

A Paper/Spigot plugin that makes the server owner (or any player you protect) **impossible to deop or ban** with normal commands. The only way to do it is a special command that works **only from the real server console**.

## How it works

- `/deop`, `/ban`, `/ban-ip` (and friends) aimed at a protected player are blocked for **everyone**: ops, command blocks, RCON, and even the console.
- Catches namespaced forms (`/minecraft:deop`) and `/execute ... run deop ...`.
- Blocks selector abuse like `/deop @a`.
- Blocks IP bans matching a protected player's current IP.
- `/happy` is the only way through, and it only runs from the actual console. Players (even ops), command blocks, RCON and `/execute` are all rejected and logged. The command is also hidden from player tab-completion.
- Backup self-heal: if another plugin gets around the blocks, protected players are re-opped and pardoned automatically.

## The /happy command (console only)

```
/happy <player> [deop|ban|both|restore] [reason]
```

| Action | What it does |
|---|---|
| `deop` | Removes op |
| `ban` | Bans the player (optional reason) |
| `both` (default) | Deops and bans |
| `restore` | Turns protection and self-heal back on for that player |

Using `/happy` pauses self-heal for that player (saved in `plugins/OwnerGuard/suspended.yml`, so it survives restarts) so the plugin doesn't undo what you just did. Run `/happy <player> restore` when you want them protected again.

`/ownerguard reload` and `/ownerguard list` are also console-only.

## Build

Requires JDK 17+ and Maven.

```
mvn clean package
```

The jar is created at `target/OwnerGuard-1.0.0.jar`. Drop it in your server's `plugins/` folder.

## Setup

1. Start the server once so `plugins/OwnerGuard/config.yml` is generated.
2. Put your name (or UUID) under `protected-players`.
3. Run `/ownerguard reload` in the console (or restart).

## Important notes

- "Console" means the real server console (the terminal/panel console). If your host's panel lets other people type there, they can use `/happy`, so keep panel access locked down.
- Anyone who can edit files on the server (config, plugins folder) or install other plugins can get around any plugin. Keep real admin access to a minimum.
- Other plugins that run bans internally (without a command) are not intercepted, which is what the self-heal task is for.
- In offline-mode servers, protect by name and make sure your name is secured with an auth plugin, since anyone could join with your name otherwise.

## License

MIT
