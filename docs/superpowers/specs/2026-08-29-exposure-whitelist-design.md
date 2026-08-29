# CmdGuard: client metadata exposure whitelist

Date: 2026-08-29
Status: implemented, plus the login-phase extension recorded below (never run in a game)
Target: Minecraft 1.21.11, Fabric Loader 0.19.3, Fabric API 0.141.6+1.21.11, Mojang mappings

## Problem

CmdGuard currently guards outbound commands and reports which installed mods a server
can reach over networking channels. It reports; it does not intervene.

A server cannot read your mods folder, and Fabric Loader sends no mod list on its own.
What it can do is ask on a custom channel, and a mod you installed may answer. It can
also simply read the channel list your client advertises on join. Both name your mods.
This design adds the enforcement half: an exposure whitelist that decides what client
metadata leaves the machine.

## Hard boundary

These rules are the point of the feature, not implementation detail. Every decision
below is subordinate to them.

- Fabric API channels in the default exposure set are exposed.
- Other mod channels are withheld by default.
- Explicitly allowed channels are exposed.
- Unknown or ambiguous channels are withheld.
- `minecraft:brand` is untouched and truthful. The client does not claim to be vanilla.
- Never fabricate. No channel is ever advertised that the client did not register; no
  identifier is ever sent that the client does not actually have.
- Behavioural fingerprinting (timing, capability) is out of scope. Tab-completion *text*
  no longer is: it is guarded by the command allowlist — see "The tab-completion
  suggestion guard" below.

Withholding is silence. Fabrication is a lie. This feature does the first and never the
second.

## Verified findings

Read from source at tag `0.141.6+1.21.11` and from the shipped artifact
`fabric-api-0.141.6+1.21.11.jar` (43 nested module jars), not from memory or articles.

Fabric API ships exactly four client-to-server payload types. Three of them enumerate
mod-owned identifiers:

| Channel | Direction | Client sends | Discloses other mods |
| --- | --- | --- | --- |
| `fabric:registry/sync` | configuration S2C | nothing; carries the server's registry map to the client | No |
| `fabric:registry/sync/complete` | configuration C2S | `StreamCodec.unit` - zero bytes | No |
| `fabric:accepted_attachments_v1` | configuration C2S | `Set<Identifier>` of every client-registered attachment type | **Yes** |
| `fabric:recipe_sync/supported_serializers` | configuration C2S | `Set<Identifier>` of synchronized recipe serializers | **Yes** |
| `fabric:custom_ingredient_sync` | configuration C2S | `int` plus `Set<Identifier>` of custom ingredient serializers | **Yes** |
| `c:register` | both phases | `int`, phase `String`, `Set<Identifier>` of channels | **Yes** - a second channel list |

Sources: `FabricRegistryInit`, `SyncCompletePayload`, `packet/RegistrySyncPayload`,
`AcceptedAttachmentsPayloadC2S`, `SupportedRecipeSerializersPayloadC2S`,
`CustomIngredientPayloadC2S`, `CommonRegisterPayload`.

Two consequences:

1. Whitelisting Fabric API at *channel* granularity would disclose a mod inventory
   through the whitelisted channels. The filter must reach inside payloads.
2. There are two registration channels, not one. `minecraft:register` is a
   NUL-separated ASCII list; `c:register` is a structured payload. Both need filtering.

`CustomIngredientSync` guards its configuration task with
`ServerConfigurationNetworking.canSend(handler, PACKET_ID)`. A protocol-respecting
server never starts a task for a channel the client did not advertise, so withholding
does not stall the join - the exchange simply does not happen.

## The rule

One rule, applied identically to channel identifiers and to identifiers carried inside
payloads:

> A namespace is exposed or it is not. Exposed by default: `fabric`, `minecraft`, `c`.
> Everything else is withheld in both directions until the user allows it.

Fabric API's channels are generic - every Fabric client has them, so advertising them
distinguishes nobody. The identifier sets they carry are not generic, and those are
filtered to exposed namespaces. Default behaviour is therefore: Fabric API works
normally, and no third-party mod is named anywhere on the wire.

Namespace is never treated as proof of ownership. The rule decides; it does not confer
trust on a mod because of where it registered. A third-party mod registering under
`minecraft:` still shows up by name in the reviewable ledger below, where it can be
withheld explicitly.

### Why the exposed set cannot be pinned at first run

An earlier draft resolved the live channel set once at startup and persisted it as the
authoritative list of channel ids. That cannot be built. `ClientPlayNetworking`
`.getGlobalReceivers()` enumerates only channels the client *receives* on, and
`PayloadTypeRegistry` (verified: `register`, `registerLarge`, and four static accessors,
no iteration) offers no way to list registered types at all. Every outbound-only channel
is therefore invisible at startup - including all three leaky Fabric payloads and
`fabric:registry/sync/complete`, which are precisely the ones worth knowing about.

So the decision mechanism is the namespace rule, evaluated per packet, plus the user's
explicit overrides. The persisted list becomes a **ledger**: channels observed from
startup receivers, plus any channel seen on the wire in either direction, recorded with
its last decision and shown by `/cmdguard exposure`. It grows as it learns.

The ledger being incomplete is safe. Decisions are default-deny, so a channel absent from
the ledger is withheld, not exposed - the ledger informs the user, it does not gate the
filter.

## Architecture

### Interception point

`net.minecraft.network.Connection#send(Packet<?>)`, guarded on `PacketFlow.SERVERBOUND`.

Not `ClientCommonPacketListenerImpl#send`. That is the path Fabric API's networking
uses, but a mod holding `Minecraft.getInstance().getConnection().getConnection()` can
build a `ServerboundCustomPayloadPacket` and hand it straight to the `Connection`,
bypassing the listener entirely - and a mod written to answer server probes is exactly
the kind of mod likely to do its own networking. Hooking `Connection` also covers the
configuration phase for free, which matters because every Fabric API C2S payload above
is configuration-phase.

The `PacketFlow.SERVERBOUND` guard keeps the integrated server's own traffic in
singleplayer out of the filter.

Inbound is filtered on the same policy, and at the same choke point:
`Connection#channelRead0`, the terminal `"packet_handler"` in the netty pipeline. A mod
that never receives the probe cannot answer it. A probing server will send on your
channels whether or not you advertised them.

An earlier draft of this spec named `ClientPacketListener#handleCustomPayload` here, and
that is where the filter first lived. It was moved for two reasons, both recorded in full
in `NOTES.md`:

- **A race.** Fabric API's own `ClientCommonPacketListenerImplMixin` injects at `HEAD` of
  the same method with the same descriptor and cancels for exactly the payloads this
  filter exists to block. Neither mixin declares a priority, and `@Inject(order = …)` does
  not sort across mods; if Fabric's callback ran first the filter was a silent no-op.
  `channelRead0` runs strictly before any `PacketListener` sees a message, so there is
  nothing left to race.
- **Bundles.** In the play protocol a `ClientboundBundlePacket` is one pipeline message
  carrying many packets, and `ClientPacketListener#handleBundlePacket` dispatches its
  sub-packets to the listener directly, without a second trip through `channelRead0`. The
  inbound hook therefore matches `ClientboundBundlePacket` as well and re-emits it with
  withheld custom-payload sub-packets removed — removal only, never a dropped
  non-payload sub-packet, and never a cancelled bundle.

### The login phase

An earlier draft of this spec scoped the login phase out, and the README and `NOTES.md`
said so. That is no longer true: it is covered, at the same `Connection#channelRead0`
`@ModifyVariable` the bundle filter uses. The investigation is
`.superpowers/sdd/login-phase-spike.md`; `NOTES.md`, "The login phase", carries the
verification.

The login phase uses a different pair of packets — `ClientboundCustomQueryPacket` inbound,
`ServerboundCustomQueryAnswerPacket` outbound — so neither `instanceof` above matches it.
It matters because Fabric's `ClientLoginNetworking` lets a mod answer a query where vanilla
answers `null`, and **answering at all is the disclosure**: one login query on a mod's
channel tells the server whether the client has it.

Outbound filtering is impossible: `CustomQueryAnswerPayload` declares only
`write(FriendlyByteBuf)` — no `id()` — and the packet's wire format is a transaction-id
varint plus `writeNullable`, so no channel id ever leaves and none can be recovered at
`sendPacket`. **The decision must be made inbound.**

Cancelling inbound is not the answer either, and this was refuted rather than assumed:
nothing in vanilla does per-transaction accounting, so an unanswered query is not refused,
it stalls until `Connection`'s `ReadTimeoutHandler(30)` fires `disconnect.timeout`. A hang
is a behaviour no vanilla client exhibits, so it discloses more than an answer does.

**So the query is substituted, not dropped.** A `ClientboundCustomQueryPacket` on a
withheld channel is replaced with
`new ClientboundCustomQueryPacket(txid, new DiscardedQueryPayload(id))` — both constructors
public and `javap`-verified. Fabric's `ClientHandshakePacketListenerImplMixin` only
intercepts a `PacketByteBufLoginQueryRequestPayload`, so it skips the substituted packet and
vanilla's unconditional `new ServerboundCustomQueryAnswerPacket(txid, null)` stands. Nothing
is cancelled, no packet this mod constructs goes on the wire, and the transaction id is
preserved.

This stays on the withholding side of the hard boundary. A `null` answer is the protocol's
own encoding of "there is no payload" (the component is `@Nullable`, written with
`writeNullable`); it carries zero identifiers; it is vanilla's unconditional behaviour
rather than a pose; Fabric's own API emits a byte-identical packet when a handler declines;
and it is the same act as omitting a channel from `minecraft:register`, one phase earlier.

**Per-server grants cannot apply, structurally.** `beginConnection` runs from
`ClientCommonPacketListenerImpl`'s constructor, first reached at `handleLoginFinished` —
after the login queries. So the login filter necessarily uses the globals-only snapshot,
and the remedy for a login it breaks is `/cmdguard expose global <namespace>` plus a
reconnect, **not** the per-server form. A user handed the per-server command watches it
fail and concludes the mod is broken, so the documentation and the log line both name the
global form explicitly.

**The failure mode is worse here than elsewhere.** A server whose handshake genuinely needs
a real answer refuses the join, and it surfaces as the *server's* disconnect screen with
nothing pointing at CmdGuard. Every substitution therefore logs at **WARN**, naming the
channel and the exact remedy command; `latest.log` is the only surface that survives. The
ledger is deliberately not written from this path, since `beginConnection` resets it after
the login phase and an entry would be wiped before anyone could read it. The behaviour has
its own toggle, `filterLogin`, default on, so it can be switched off without giving up the
rest of the layer.

### Outcomes per outbound custom payload

| Payload | Action |
| --- | --- |
| `minecraft:brand` | Pass through untouched, always. Hardcoded, not policy-driven. |
| `c:version` | Pass through. Protocol version negotiation; carries no mod data. |
| `minecraft:register`, `minecraft:unregister`, `c:register` | Rewrite. Channel list filtered to exposed channels. Entries removed, never added. |
| Leaky Fabric payloads carrying `Set<Identifier>` | Rewrite. Identifier set filtered to exposed namespaces. |
| Anything else | Pass or drop per policy. A dropped payload is silence; nothing is sent in its place. |

`minecraft:unregister` is filtered for the same reason as `register`, which is easy to
miss: unregistering a channel names it. A client that never advertised `somemod:handshake`
and then unregisters it has disclosed `somemod` just as surely as advertising it would.

### Rewriting a payload

A byte-level rewrap is not reachable, and an earlier draft of this spec was wrong to
propose one. Fabric's `CustomPayloadPacketCodecMixin` wraps `findCodec(identifier)`:
encoding resolves the codec **by channel id** and then casts the payload to that codec's
type. A generic `(Identifier, byte[])` payload sent under `minecraft:register` would be
handed `RegistrationPayload`'s codec and fail on the cast, and under a fresh id it would
have no registered client-to-server codec at all. The idea is dropped.

Instead the guard matches the outgoing payload against the five rewritable types and
constructs a filtered instance of the same record. All five are public records with
public canonical constructors, reachable on the compile classpath:

| Channel | Record and constructor |
| --- | --- |
| `minecraft:register`, `minecraft:unregister` | `RegistrationPayload(Type<RegistrationPayload> id, List<Identifier> channels)` |
| `c:register` | `CommonRegisterPayload(int version, String phase, Set<Identifier> channels)` |
| `fabric:accepted_attachments_v1` | `AcceptedAttachmentsPayloadC2S(Set<Identifier> acceptedAttachments)` |
| `fabric:recipe_sync/supported_serializers` | `SupportedRecipeSerializersPayloadC2S(Set<Identifier> synchronizedSerializers)` |
| `fabric:custom_ingredient_sync` | `CustomIngredientPayloadC2S(int protocolVersion, Set<Identifier> registeredSerializers)` |

Non-identifier fields are copied through unchanged. A payload matching none of these types
is never rewritten - only passed or dropped - so an unfamiliar payload cannot be corrupted.

This trades a byte parser for a compile-time dependency on five Fabric `impl` classes,
which are explicitly unstable API. That is the better trade here. The versions are pinned
in `gradle.properties`, so a Fabric API bump that moves or reshapes these records is a
**compile error**, not a jar that silently stops filtering - the same fail-closed property
the mixin config already relies on. `fabric.mod.json` additionally declares a strict
`fabric-api` dependency, so a user running a different Fabric API version gets a clean
refusal to start rather than a crash partway through a handshake.

### Components

Pure, no Minecraft imports, unit-testable without a client:

- `ExposurePolicy` - immutable. `decide(channelId, serverKey)` returns `EXPOSE` or
  `WITHHOLD` from the exposed-namespace set, the per-server grants, and the channel-level
  refinements. Any throw is caught and treated as `WITHHOLD`.
- `IdentifierFilter` - `retainExposed(Collection<String>, ExposurePolicy)` returns the
  subset whose entries the policy exposes, order preserved, never adding an entry absent
  from the input. Every rewrite is expressed as one call to this.
- `ChannelLedger` - the record of channels observed and their last decision. Persisted,
  read by `/cmdguard exposure`, and never consulted by the filter.
- `LoginQueryFilter` - the login-phase decision as a pure function of a channel id, the two
  switches and a policy, plus the namespace the remedy command must name. Withholds on a
  null or malformed id, a null policy and any throw. Kept separate from `ExposureGuard` so
  the one decision that can cost a player their join is unit-testable without a client.

Minecraft-facing, kept thin:

- `PayloadRewriter` - matches an outgoing payload against the five rewritable record types
  and returns a filtered instance of the same record, or the original unchanged when it
  matches none. The only file that imports Fabric `impl` types, so the unstable surface
  sits in one place.
- `ExposureGuard` - the facade the mixins call: `shouldDrop`/`allowInbound` return booleans,
  `rewriteOrSame`/`filterBundle` return the packet unchanged or a rewritten/rebuilt one --
  never null, there is nothing here to cancel a packet by returning null. Holds the
  connection's policy snapshot and feeds the ledger.
- `ConnectionMixin` - both directions, outbound and inbound, the latter with three handlers
  on one method because one pipeline message is not one packet of one type: a bare
  clientbound payload is cancelled, a bundle is rebuilt without its withheld sub-payloads
  (see `NOTES.md`, "Inbound: packet bundles"), and a login query has its payload substituted
  (see "The login phase" above).
  `ClientPacketListenerMixin` is unrelated to exposure: it holds the outbound
  `sendCommand`/`sendUnattendedCommand` guard from the command-blocking feature.

### Per-server policy

Global policy plus a `Map<serverAddress, Set<String>>` of additional exposures, keyed on
`ServerData#ip` lowercased, with `singleplayer` as the one reserved key.

An earlier draft of this spec also reserved `lan`. That turned out to be wrong, and the
implementation is right: a LAN join carries the real discovered LAN address through the
listener cookie, so it is keyed like any other server and gets its own grants. Only a
connection with no `ServerData` at all falls back to `singleplayer`.

The `singleplayer` key therefore stays distinct from every real server's, which is what
this reservation is for. It does **not** mean per-world grants take effect: singleplayer
is exempt from filtering entirely (`ExposureGuard.snapshotFor` sets `active` false for
this key, because a local world's connection has no remote party to disclose anything to),
so a grant stored under `singleplayer` is recorded and never applied. Keeping the key
separate is still worth doing — it stops a local-world grant from ever being read as a
real server's.

Exposures are granted **by namespace**, matching the rule: `/cmdguard expose <namespace>`
applies to the current server, `--global` applies everywhere. A mod typically registers
several channels, so channel-by-channel granting would be whack-a-mole. Channel-level
refinement exists for the case where one channel of an exposed mod should stay withheld:
`/cmdguard expose channel <id>` and `/cmdguard withhold channel <id>`, the latter taking
precedence over a namespace grant.

### Policy lifecycle

`ExposurePolicy` is immutable. Editing config builds a new instance and swaps it
atomically.

A connection captures its policy snapshot when it opens and uses that snapshot for its
lifetime. `Connection#send` runs on the netty event loop rather than the client thread,
so the policy must be safe to read from another thread, and a config edit made
mid-session must not change behaviour halfway through a handshake - the configuration
phase would otherwise be filtered under one policy and the play phase under another.
Changes take effect on the next connection, and `/cmdguard expose` says so.

## The invariant

**The advertisement must match the enforcement.** Any channel omitted from
`minecraft:register` or `c:register` must also be dropped in both directions.

Omit-and-then-answer would make the advertisement false. Omit-and-refuse makes it true.
This is what keeps the feature on the withholding side of the boundary, and it gets a
test.

## Failure behaviour

Fail closed, and never fail silently.

- `cmdguard.mixins.json` sets `"required": true` and `defaultRequire: 1`, so a mixin that
  fails to find its target refuses to load rather than silently doing nothing.

  **Correction to an earlier draft of this spec.** That draft claimed a mapping change
  "breaks the build". It does not, and this was tested: pointing an `@Inject` at a
  nonexistent method still produces `BUILD SUCCESSFUL`. Mixin application happens at
  launch, not at compile time, so a wrong target is a **launch-time crash**, not a build
  failure. The failure is still loud and still fails closed - the mod does not load, so it
  cannot pretend to guard - but a green build, in CI or locally, is not evidence that any
  mixin target is correct. Only running the game proves that, which makes the manual
  acceptance run the sole check on every mapped signature this mod depends on.
- A throw inside the policy withholds. In the login phase that means substituting rather
  than dropping, since dropping there is a stall, not a withhold.
- A withheld login answer is logged at **WARN**, not INFO, and names the remedy command.
  Unlike every other withhold, the consequence can be that the player cannot join at all,
  and the disconnect they see comes from the server with no mention of CmdGuard on it.
- Join-time chat line reports the counts: exposed, withheld.
- `/cmdguard exposure` lists every channel in the ledger as EXPOSED or WITHHELD with the
  withheld-payload count, and survives a disconnect so it can be read after a kick.
  `fabric:registry/sync/complete` is listed as always exposed, annotated: required to
  finish joining, carries no data.

## Config migration

`cmdguard.json` already exists on disk for current users and has none of the new fields.
Gson leaves absent fields null, so every new field needs the same null guard the loader
already applies to `allowlist`. First-run seeding must populate only what is missing and
must never overwrite an existing file's settings. Getting this wrong silently resets a
user's command allowlist, which is the one piece of state they have curated by hand.

## Known costs

An identifier you withhold is one the server will not sync to you. Withheld attachment
types, recipe serializers and custom ingredients degrade the mods that own them. That is
the trade being bought, and it is coherent: you declined to participate rather than
misreported.

**You will be kicked from servers that require a client mod you are withholding.** A
server enforcing a required-mod check sees a client without that channel and refuses the
connection, which is the correct outcome for both sides: it asked, and you declined to
answer, so it declines to host you. The remedy is `/cmdguard expose <namespace>` for that
server followed by a reconnect. This is the most common way a user will meet the feature,
and a kick leaves no chat to write to - so the withheld set for a connection must survive
its disconnect, be logged, and be readable afterwards via `/cmdguard exposure` so the
user can see what was withheld rather than guess.

**The login phase is the sharp edge of that same cost.** A server whose handshake needs a
real login answer - a proxy's forwarding handshake is the obvious case - refuses the
connection outright, and the user never reaches a state where `/cmdguard exposure` can be
run. The remedy is `/cmdguard expose global <namespace>` (global, not per-server: see "The
login phase") plus a reconnect, and the WARN line in `latest.log` is the only place that
remedy is discoverable from.

## Documentation changes

`ChannelAudit`'s class javadoc currently argues the opposite position - that suppressing
a reply while staying connected deceives the operator. Shipping this feature alongside
that text would make the comment a lie. It is rewritten to state the disclosure-control
stance: CmdGuard withholds non-whitelisted disclosure and never fabricates, and the
audit is the readout of that enforcement rather than a report in place of it.

## What this does not do

Your `minecraft:brand` still reads `fabric`. Fabric API's channels are still advertised.
A server can still tell you are modded and roughly on what Fabric API version. This
reduces mod-inventory disclosure; it does not make you look unmodded, and the README
will say so in those words.

## Testing

`build.gradle` has no test setup. Add JUnit 5 and a CI test step.

Covered: `ExposurePolicy` decisions, including the unknown-namespace default, the
malformed-identifier default, and precedence between namespace grants and channel-level
refinements; `IdentifierFilter`; a test asserting `minecraft:brand` is never withheld;
a test asserting the filter never emits an entry absent from its input (the
no-fabrication boundary, enforced by code rather than intent); a test asserting the
advertisement/enforcement invariant - every channel the filter would strip from a
registration is one the policy also withholds in both directions; `LoginQueryFilter`,
including that the login decision agrees channel-for-channel with the rest of the layer
(the same invariant carried into the login phase), that both switches are checked before
anything can fail, and that a null id, a malformed id and a null policy each withhold; and
that an `exposure` block written before `filterLogin` existed normalises to login filtering
**on** rather than off - the migration a primitive `boolean` would have got backwards.

Not covered: mixins and Minecraft-facing code. There is no Minecraft client on the build
machine. This is a real gap, stated rather than papered over.

### Manual acceptance, on a machine with a client

Unit tests cannot show that the interception point is correct. The following is the
minimum that must be run once by hand before this is called done:

1. Join a vanilla server. Connects normally; `minecraft:brand` still reads `fabric`.
2. Join a Fabric server running mods, with third-party client mods installed. Connection
   completes - no hang in the configuration phase, which is what a wrongly withheld
   `fabric:registry/sync/complete` would cause.
3. `/cmdguard exposure` lists withheld channels and a non-zero withheld count.
4. `/cmdguard expose <namespace>` for one withheld mod, reconnect, confirm the count drops
   and that namespace now reads EXPOSED.
5. Confirm the command guard and `/cmdguard audit` still behave as before.
6. The login-phase substitution needs its own rig, because vanilla never sends a login
   query at all - a repo-wide grep of the decompiled sources finds zero
   `new ClientboundCustomQueryPacket`. The spike's "What in-game acceptance would look
   like" section describes it: a small server-side Fabric mod that sends one login query on
   a known channel and logs whether an answer arrived and whether its payload was null,
   then four client runs - vanilla (answer, null), a mod with a login handler and no
   CmdGuard (answer, non-null), the same plus CmdGuard withholding (answer, null,
   byte-identical to the first), and the same plus CmdGuard exposing (identical to the
   second). The assertion is on the *server's* log; "the client connected fine" does not
   distinguish a null answer from a real one.

A packet capture of the configuration phase, if the tester can take one, is the only
direct evidence that no withheld identifier reached the wire.

## The tab-completion suggestion guard

Listed here as out of scope until 2026-08-29, on the reasoning that it was a separate
feature with a separate mechanism. Half of that was wrong, and it is now implemented.

**The leak.** `ClientSuggestionProvider#customSuggestion` sends
`new ServerboundCommandSuggestionPacket(i, commandContext.getInput())` — the partial
command, truncated at the cursor — the moment tab completion runs. The command guard
blocks `/somemod:debug` on the way to `sendCommand`; without this, the same text left the
client one keystroke earlier as a completion request.

**Not a separate mechanism.** The packet goes out through `ClientPacketListener#send`,
which is a one-line delegation to `Connection#send` and hence to `Connection#sendPacket`
— the outbound choke point this design already hooks for the exposure layer. So the guard
is one more `@Inject` on the existing `ConnectionMixin`, not a new mixin and not a new
interception point. Verified against the decompiled 1.21.11 sources and, for the
accessors (`getId()` / `getCommand()`; it is a class, not a record), `javap` over the
mapped merged jar. See `NOTES.md`, "Outbound: tab-completion requests".

**It is a separate *policy* surface, though, and stays separate.** This is the command
guard's rule, not the exposure whitelist's: `SuggestionFilter` reads `GuardConfig.allowlist`
and nothing else. A suggestion request is judged by exactly the rule that governs the
command it would become — one list, so the two can never disagree. The exposure policy is
not consulted, and the toggle lives on `GuardConfig` (`guardSuggestions`, a primitive
`boolean` with a `true` initializer, like `enabled` and `allowClickedCommands`) rather than
in `ExposureSettings`, because putting it there would have gated a command-guard rule behind
`exposure.enabled`.

**The partial-root decision.** A partial command may not have a complete root yet: `/ms`
has root `ms`, which is not on the allowlist even though the user is heading for `/msg`.
It is withheld. The alternative — a prefix rule — would send every prefix of an allowlisted
root to the server, i.e. leak the keystrokes the guard exists to protect. The cost is that a
command *name* cannot be completed against the server, only its arguments; the README states
this in those words rather than leaving a user to discover it. The cost is smaller than it
reads: command names are completed locally from the client's copy of the command tree, and
only an *argument* node that asks the server produces this packet at all. The rule is still
enforced strictly, because the command tree is server-supplied and a server could put an
asks-the-server argument node directly under the root.

**Edges, decided rather than defaulted.**

- *Empty input* (`""`, or `"/"` alone) — withheld. `OutboundGuard#shouldBlock` lets an
  empty root through because an empty command sends nothing; here the text is on the wire
  either way, so it is judged, and an empty root is on no allowlist.
- *No leading slash* — judged as a command anyway. Plain chat suggestions never reach this
  packet (`CommandSuggestions#updateCommandInfo` serves them from a local player-name list),
  so slashless text here comes from `AbstractCommandBlockEditScreen`, where it *is* a
  command. Vanilla's `handleCustomCommandSuggestions` strips an optional `/` for the same
  reason; `CommandRoot.of` matches it.
- *A root the client handles locally* — **not** exempt, unlike on the typed-command path.
  A client command never reaches the network when run, but its completion request does, and
  `NOTES.md`'s leak vector #3 is exactly a client mod hanging `ASK_SERVER` on its own
  argument. Exempting client roots would wave through the case this is best placed to catch.

**Failure behaviour.** Fail closed, like everything else here: an exception while deciding
cancels the send. The `instanceof` sits outside the `try`, so a fail-closed cancel can only
ever drop a suggestion request, never an unrelated packet. Cancelling is safe — the
`CompletableFuture` `customSuggestion` returned is only ever read behind an `isDone()` check
in `CommandSuggestions`, so an uncompleted one leaves the popup empty and is cancelled by the
next keystroke. No chat message is emitted (a request per keystroke would bury the chat, and
this can run on the netty event loop); one INFO line per withheld root goes to `latest.log`.

**Testing.** `SuggestionFilter` and `CommandRoot` are Minecraft-free and unit-tested:
allowlisted root, non-allowlisted root, partial root, empty, slashless text, both switches
off, a null allowlist and an allowlist that throws (both fail closed), strict mode, and the
invariant that the suggestion decision agrees with the command decision on the same
allowlist. The mixin binding itself is launch-time and remains untested, as elsewhere.

## Out of scope

Behavioural fingerprinting: timing, capability probing, and what the client's *behaviour*
reveals as opposed to what it says.
