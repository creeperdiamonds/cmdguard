# CmdGuard

A **client-side** Fabric mod for Minecraft **1.21.11**. It does one honest thing:
it stops commands you didn't allow from leaving your machine, and it controls which
client metadata reaches a server in the first place.

It does **not** spoof your client, does not alter `minecraft:brand`, and never
advertises a channel or identifier the client does not actually have. See
[What this does and doesn't do](#what-this-does-and-doesnt-do) and
[Exposure whitelist](#exposure-whitelist).

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
- **Exposure whitelist.** Enforces what the channel audit only reports: outbound and
  inbound traffic on channels outside the default namespace set is withheld unless you
  allow it. `/cmdguard exposure` shows the readout. See
  [Exposure whitelist](#exposure-whitelist) below.

## Commands

| Command | Effect |
|---|---|
| `/cmdguard` or `/cmdguard status` | Show state |
| `/cmdguard on` / `off` | Enable / disable the guard |
| `/cmdguard allow <root>` / `deny <root>` | Edit the outbound allowlist |
| `/cmdguard list` | Show the allowlist |
| `/cmdguard audit` | List mods reachable over server channels |
| `/cmdguard clear` | Empty the allowlist (strict mode) |
| `/cmdguard exposure` | List channels observed this connection and their EXPOSED/WITHHELD state |
| `/cmdguard expose <namespace>` | Allow a namespace on the server you are connected to; add `global` before the namespace to allow it everywhere |
| `/cmdguard withhold <namespace>` | Withhold a previously-exposed namespace, in every scope it was granted in (the output names which) |
| `/cmdguard expose channel <id>` | Allow one channel, e.g. `somemod:handshake`, everywhere — for when a namespace grant is too coarse |
| `/cmdguard withhold channel <id>` | Withhold one channel everywhere, even where its namespace is exposed |

Config lives at `config/cmdguard.json`. A settings screen is available via Mod Menu,
with toggles for the guard, clicked-command policy, the exposure whitelist, and
whether inbound probes on withheld channels are blocked.

Note that `/cmdguard off` switches the exposure whitelist off along with the command
guard — it is the master switch for both. The config screen, `/cmdguard status` and
`/cmdguard exposure` all say so rather than reporting the whitelist as on.

**If you get kicked**, the readout is still there: CmdGuard logs one line per channel the
first time it withholds on it, and logs the connection's `exposed=N withheld=M` tally when
you next connect, so `logs/latest.log` has the record even though a kick leaves no chat to
read and `/cmdguard exposure` needs a live connection.

## What this does and doesn't do

**A server cannot read your mods folder, and Fabric Loader never sends a mod list.**
What a server *can* do:

- read the client brand string (`fabric`) — a loader constant, not a mod list;
- see channel registrations announced when a mod uses custom networking;
- **ask on a custom channel, and have one of your installed mods answer** — because
  `FabricLoader.getInstance().getAllMods()` is public API and any mod can report it;
- read the channel list your client advertises on join, which by itself can name a mod.

That's real: a mod in a modpack could report your whole load-out without you noticing,
and the raw channel-advertisement list can do the same even with no mod cooperating.
CmdGuard's answer is enforcement, not just a report: the [exposure whitelist](#exposure-whitelist)
withholds both, by default, for anything outside a small generic namespace set — and
`/cmdguard audit` and `/cmdguard exposure` let you see what is and isn't withheld.

CmdGuard never fabricates. It does not alter the brand string, does not claim to be
vanilla, and never advertises a channel or identifier the client does not actually have.
Declining to answer is a refusal; inventing an answer would be a lie, and this mod does
not do the second.

See [NOTES.md](NOTES.md) for the developer-facing hazard table.

## Exposure whitelist

By default, CmdGuard withholds every channel outside the `fabric`, `minecraft`, and `c`
namespaces, in both directions — outbound (what your client advertises or sends) and
inbound (what a server sends you on that channel) — throughout the configuration and play
phases of a connection. **The login phase is not covered**; see
[What is not covered](#what-is-not-covered) below. This includes the identifier lists
carried inside three of Fabric API's own payloads: the accepted-attachments,
recipe-serializer, and custom-ingredient sync messages, each of which otherwise names
every third-party mod that registered one. Everything outside those three namespaces is
withheld until you explicitly allow it.

**What is *not* hidden.** `minecraft:brand` still reads `fabric`. Fabric API's own
channels are still advertised and still function. A server can still tell you are
modded, and roughly which Fabric API version you're on. This reduces mod-inventory
disclosure; it does not make you look unmodded, and CmdGuard never claims otherwise.

**Withholding has a cost.** A channel you withhold is one the server will not sync to
you, so withholding degrades the mod that owns it — it doesn't ask, so it doesn't get an
answer, and it can't send you data over a channel you never advertised. A server that
*requires* a client mod you are withholding will kick you for not having it. The fix is
`/cmdguard expose <namespace>` followed by a reconnect — grants are frozen for the
lifetime of an open connection, so `/cmdguard expose` takes effect on your *next*
connection, not the one you're on.

**Grants are per connection address**, keyed on the address the connection actually
carries, plus `/cmdguard expose global <namespace>` to allow a namespace everywhere
instead of just that one connection. This covers more than ordinary multiplayer:

- **Singleplayer is not filtered at all.** A local world's connection runs between your
  client and the integrated server inside the same process. There is no remote party, so
  there is nothing to withhold from — and filtering it is not free: it would strip
  third-party identifiers out of `minecraft:register`, `c:register`, accepted-attachments,
  recipe-serializer and custom-ingredient messages your client sends to *your own* server,
  breaking other mods' networking, attachment sync and custom recipes to buy no privacy
  whatsoever. So exposure filtering is off for singleplayer, and `/cmdguard exposure` says
  so. The connection still gets its own reserved `"singleplayer"` key so per-world grants
  stay separate from any real server's. This applies while hosting with "Open to LAN" too;
  *joining* someone else's LAN game is an ordinary server connection and is filtered
  normally.
- **Realms, quick-play, and joining a LAN game from the "LAN Games" list are all fully
  covered.** Each of these carries a real, correctly-addressed server identity into the
  connection — a Realms join, a quick-play join, and a LAN join all end up passing a
  genuine `ServerData` through to the same code path an ordinary "Direct Connect" join
  uses, keyed on that real address exactly like any other server. A LAN join is keyed
  on the LAN host's actual discovered address, not a special shared word — so if that
  address changes between sessions (a common case for a dynamically-assigned local IP),
  the grant from a previous session may not carry over, the same as it wouldn't for any
  server whose address changed.
- **The one case that deliberately does not get a per-connection grant is a server
  transfer** (the vanilla feature that hands you from one server to another
  mid-session): it falls back to global grants only, rather than carrying the origin
  server's grants over to the destination. Stricter than it has to be, on purpose — the
  destination is a different server and gets no grant it wasn't given directly.
  A transferred connection is therefore the only case where `/cmdguard expose <namespace>`
  (without `global`) refuses, telling you to use `/cmdguard expose global <namespace>`
  instead — that message is how you'd notice it in practice. (There is also a brief window
  early in a connection, before the per-connection key is established, that runs on global
  grants only; you cannot type a command during it, so you will never meet it.)

## What is not covered

**The login phase.** Everything above applies to the configuration and play phases of a
connection. It does not apply to the login phase, which runs before either and uses a
different pair of packets — `ClientboundCustomQueryPacket` and
`ServerboundCustomQueryAnswerPacket`, neither of which is the custom-payload packet
CmdGuard filters — handled by a listener that shares no base class with the ones CmdGuard
hooks.

That matters because of how Fabric API answers a login query: a vanilla client always
replies `null`, while a client with a mod that registered a login-query handler for that
channel replies with a payload. **Answering at all, rather than what the answer says, is
already the disclosure** — so a server that sends a login query on a mod's channel can
learn whether you have that mod, and CmdGuard does not currently stop it.

This is a design change rather than a fix — withholding a login answer means deciding what
to send in its place, and CmdGuard does not fabricate — so it is written down here rather
than papered over. See [NOTES.md](NOTES.md).

## Building

CI builds every push (`.github/workflows/build.yml`) and uploads the jar as an artifact,
so you never have to run Gradle locally. To build yourself: `./gradlew build`, jar lands
in `build/libs/`. Requires JDK 21.

**If CmdGuard refuses to start after a Fabric API update, that is deliberate.**
`fabric.mod.json` pins an exact `fabric-api` version rather than a range, because this mod
reads the internals of Fabric API's own client-to-server payloads — the accepted-attachments,
recipe-serializer and custom-ingredient messages — to strip third-party identifiers out of
them. Those are implementation details with no compatibility promise: a routine Fabric API
update can change a payload's shape, and a CmdGuard that silently stopped filtering one
would be worse than a CmdGuard that refuses to load. Failing to start is the fail-closed
behaviour. The fix is a CmdGuard release that has been checked against the new Fabric API,
not a looser pin.

## License

MIT
