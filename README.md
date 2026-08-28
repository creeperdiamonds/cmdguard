# CmdGuard

A **client-side** Fabric mod for Minecraft **1.21.11**. It does one honest thing:
it stops commands you didn't allow from leaving your machine, and it shows you which
of your installed mods a server can talk to.

It does **not** spoof your client, hide your mod list, or lie to a server. See
[What this does and doesn't do](#what-this-does-and-doesnt-do).

## Features

- **Outbound command guard.** Anything your client handles locally (Fabric client
  commands from other mods) always stays local. Anything else is blocked unless its
  root is on your allowlist. Blocked commands show a clickable `[allow /root]` in chat.
- **Autodetect, allowlist what you choose.** "Is this a client command?" needs no
  detection heuristics — if a command reached the send path, Fabric's client dispatcher
  already declined it. You only ever curate the *outbound* allowlist.
- **Starter allowlist** covering auth (`/login`, `/register`), messaging, and common
  server commands, so you aren't locked out of servers on day one. `/cmdguard clear`
  gives you strict default-deny.
- **Clicked-command policy.** Plugin menus fire commands via a different path when you
  click them; those are allowed by default (toggle in config) so server GUIs keep working.
- **Channel audit.** `/cmdguard audit` lists every installed mod that has registered a
  networking channel — i.e. every mod a server could ask something of. This is the real,
  checkable version of "what can a server see about me."

## Commands

| Command | Effect |
|---|---|
| `/cmdguard` or `/cmdguard status` | Show state |
| `/cmdguard on` / `off` | Enable / disable the guard |
| `/cmdguard allow <root>` / `deny <root>` | Edit the outbound allowlist |
| `/cmdguard list` | Show the allowlist |
| `/cmdguard audit` | List mods reachable over server channels |
| `/cmdguard clear` | Empty the allowlist (strict mode) |

Config lives at `config/cmdguard.json`. A settings screen is available via Mod Menu.

## What this does and doesn't do

**A server cannot read your mods folder, and Fabric Loader never sends a mod list.**
What a server *can* do:

- read the client brand string (`fabric`) — a loader constant, not a mod list;
- see channel registrations announced when a mod uses custom networking;
- **ask on a custom channel, and have one of your installed mods answer** — because
  `FabricLoader.getInstance().getAllMods()` is public API and any mod can report it.

That last one is real: a mod in a modpack could report your whole load-out without you
noticing. CmdGuard's answer is **transparency, not concealment** — `/cmdguard audit`
names the mods that can be probed, so *you* decide whether to keep them or not join.

CmdGuard deliberately does **not** silently suppress such a reply while you stay
connected. Withholding an answer to keep server access is deception; removing the mod,
or not joining, is not. It also does not alter the brand string or hide anything from a
server — it can't get you flagged for something it isn't doing, because it adds no
networking channels and sends no packets of its own.

See [NOTES.md](NOTES.md) for the developer-facing hazard table.

## Building

CI builds every push (`.github/workflows/build.yml`) and uploads the jar as an artifact,
so you never have to run Gradle locally. To build yourself: `./gradlew build`, jar lands
in `build/libs/`. Requires JDK 21.

## License

MIT
