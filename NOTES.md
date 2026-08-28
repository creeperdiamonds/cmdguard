# Developer notes — how a command leaks, and where it can't

All verified 2026-08-28 against Mojang's published 1.21.11 client mappings and Fabric
API branch `1.21.11`. This project uses **official Mojang mappings**.

## The six leak vectors (for anyone extending this mod)

| # | Slip-up | Why it's easy to miss | Guarded here? |
|---|---|---|---|
| 1 | `CommandRegistrationCallback` instead of `ClientCommandRegistrationCallback` | one word apart; both compile | reference command uses the client one |
| 2 | `connection.sendCommand(...)` as a "quick fix" | looks like the obvious way to run a command | that IS the hooked path — it's guarded |
| 3 | `SuggestionProviders.ASK_SERVER` on a client arg | tab-completion sends a packet; command itself looks fine | avoid on client commands; none used here |
| 4 | player feedback via a server-routed message | safe path is `Minecraft#gui.getChat().addMessage` | `OutboundGuard.say` / `sendFeedback` only |
| 5 | a generic root literal | shadows the server command AND falls through when this mod isn't loaded | root is the mod id `cmdguard` |
| 6 | registering a custom networking channel | announced via `minecraft:register` | this mod registers none |

## Facts worth not re-litigating

- `ClientCommandInternals.executeCommand` returns `true` (command NOT sent) whenever the
  active client dispatcher resolves it. Only an *unknown root* falls through to the
  server. So a client command leaks only if its owning mod isn't loaded.
- Fabric's own TODO — `// Check for server commands before executing` — means the client
  dispatcher wins unconditionally. A generic root silently eats the server's command.
- `Hooks.insertBranding` returns `vanilla ? "fabric" : brand + ",fabric"`. The server
  sees `fabric`, never a mod list.
- `fabric-loader` has no handshake / modlist / networking / packet source. It sends no
  mod list. **But** `FabricLoader#getAllMods` is public and any installed mod can report
  the list over a channel — that's the genuine exposure this mod surfaces via
  `ClientPlayNetworking.getGlobalReceivers()`.

## Verified 1.21.11 hook points (Mojang mappings)

- `ClientPacketListener#sendCommand(String)`
- `ClientPacketListener#sendUnattendedCommand(String, Screen)` — clicked/menu commands
- `net.minecraft.resources.Identifier` — **renamed from `ResourceLocation`** in 1.21.11
- `ClientPlayNetworking.getGlobalReceivers() / getSendable() / canSend(Identifier)`
- `C2SPlayChannelEvents.Register.onChannelRegister(handler, sender, client, List<Identifier>)`
