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
- **Tab-completion guard.** Pressing Tab sends the partial command to the server *before*
  you press enter, so a guard that only checked what you send on enter would be leaking the
  same text one step earlier. Completion requests are judged by the same allowlist. This has
  a real cost — see [Tab completion](#tab-completion). On by default.
- **Channel audit.** `/cmdguard audit` lists every installed mod that has registered a
  networking channel — i.e. every mod a server could ask something of. This is the real,
  checkable version of "what can a server see about me."
- **Exposure whitelist.** Enforces what the channel audit only reports: outbound and
  inbound traffic on channels outside the default namespace set is withheld unless you
  allow it. `/cmdguard exposure` shows the readout. See
  [Exposure whitelist](#exposure-whitelist) below.
- **Login-query withholding.** A server can probe for a specific mod with a single login
  query, before you have joined anything, and a mod that answers gives itself away.
  CmdGuard lets vanilla's own empty reply stand instead. See
  [The login phase](#the-login-phase).

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
with toggles for the guard, clicked-command policy, tab completion, the exposure whitelist,
whether inbound probes on withheld channels are blocked, and login queries.

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
inbound (what a server sends you on that channel) — throughout all three phases of a
connection: login, configuration and play. This includes the identifier lists
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
  so. The connection still gets its own reserved `"singleplayer"` key, kept separate from
  any real server's, but since filtering is off entirely here that key does nothing —
  any `expose`/`withhold` recorded against it is stored and never applied to anything. This
  applies while hosting with "Open to LAN" too; *joining* someone else's LAN game is an
  ordinary server connection and is filtered normally.
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

## The login phase

The login phase runs before configuration and play, and it is the one place a server can
probe you before you have joined anything. It works differently enough to be worth its own
section.

A server can send a *login query* on any channel. A vanilla client always replies `null`,
without even looking at the channel. A client with a mod that registered a handler for
that channel replies with a real payload. **Answering at all, rather than what the answer
says, is the disclosure** — one login query tells the server whether you have that mod.

CmdGuard withholds the answer for a channel you have not exposed, and it does so by
letting *vanilla* answer instead of the mod: the query is passed through with its payload
replaced by the "unrecognised channel" payload vanilla itself produces, so the reply that
goes on the wire is vanilla's own empty one, with the transaction id intact. CmdGuard
sends no packet of its own and cancels nothing. The empty reply is a refusal, not a lie:
it is the protocol's own "there is no payload" value, it names no channel, it is what
every vanilla client sends, and it is byte-for-byte what Fabric's own API sends when a
mod's handler declines.

Dropping the query outright would not be silence — nothing on either side keeps track of
an unanswered query, so the server just waits, and after 30 seconds you get a "Timed out"
disconnect. A client that hangs stands out more than one that answers, so CmdGuard does
not do that.

**Two things to know if a join breaks.**

- **The remedy is the *global* form of the command.** The login phase happens before the
  connection has a server identity to hang a grant on, so per-server grants cannot apply
  to it — only global ones. If a server's handshake genuinely needs a mod's real answer,
  run **`/cmdguard expose global <namespace>`** and reconnect. `/cmdguard expose
  <namespace>` (without `global`) will not help here, however right it looks.
- **Look in `latest.log`.** A server that refuses the join over a withheld login answer
  shows you *its* disconnect screen, which says nothing about CmdGuard. There is no chat
  left to write to and `/cmdguard exposure` needs a connection, so every withheld login
  answer is written to `latest.log` as a `WARN` naming the channel and the exact command
  to run. That line is the only place the cause appears.

You can switch this off on its own — "Login queries" in the Mod Menu settings screen —
without disabling the rest of the exposure whitelist. It is on by default.

## Tab completion

**Pressing Tab sends what you have typed so far to the server.** That is vanilla
behaviour, not something a mod added: the client asks the server to complete an argument
by putting the partial command on the wire. So blocking `/somemod:debug` on enter while
happily sending `somemod:debug` as a completion request would not be much of a guard.

CmdGuard judges a completion request by **the same allowlist as the command it would
become**. There is no second list and no second policy to keep in sync.

**What that costs you, stated plainly rather than left to be discovered:**

- **A command *name* cannot be completed against the server — only its arguments.** A
  partial command does not yet have a complete root. `/ms` has the root `ms`, which is not
  on your allowlist even though you are on your way to `/msg`, so CmdGuard withholds it.
  The alternative — allowing anything that is a prefix of an allowlisted root — would send
  `/m`, `/ms` and every other prefix to the server, which is exactly the keystroke leak
  this is meant to stop. The strict reading is the correct trade for this mod.
- **In practice you will rarely notice**, because the client completes command *names*
  from its own copy of the command tree without asking the server at all. A request only
  goes out when an *argument* asks the server — for example a player-name argument. So
  `/ms<Tab>` normally sends nothing either way; what changes is that `/somemod:debug <Tab>`
  no longer asks the server to complete its arguments. The rule is still enforced strictly,
  because the command tree comes from the server and a server that wanted your keystrokes
  could ask to be consulted right at the root.
- **Completions for allowlisted commands keep working.** `/msg <Tab>` still completes
  player names, because `msg` is on the allowlist.
- **A blocked request is silent.** Tab simply shows nothing — there is no chat message,
  because a request goes out on every keystroke and a message per keystroke would bury
  your chat. The first time a given root is withheld, one line naming it and the
  `/cmdguard allow <root>` remedy goes to `logs/latest.log`.
- **Client commands from other mods are not exempt here**, unlike on the typed-command
  path. A client mod's command never reaches the network when you run it — but if that mod
  asks the server to complete one of its arguments (a real and easy mistake), the request
  does, and it names the mod's command. That is one of the leak vectors CmdGuard exists to
  catch, so it is not waved through.

Switch it off on its own with the "Tab completion" toggle in the Mod Menu settings screen;
`/cmdguard status` reports its state. `/cmdguard off` turns it off along with everything
else.

## What is not covered

**Behavioural fingerprinting.** CmdGuard controls what your client *says*, not how it
behaves. Timing, capabilities, and which features work are out of scope, and a server that
studies them can still draw conclusions. See [NOTES.md](NOTES.md) for the developer-facing
detail.

**None of this has been tested in a running game.** There is no Minecraft client on the
machine this is developed on. The interception points are verified against the decompiled
1.21.11 sources and the mapped jar, and the decision logic is unit-tested, but Fabric
mixins are applied at launch — so a successful build is evidence of compilation and
nothing more. This is stated rather than papered over.

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
