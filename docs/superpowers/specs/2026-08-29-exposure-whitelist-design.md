# CmdGuard: client metadata exposure whitelist

Date: 2026-08-29
Status: design approved, implementation not started
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
- Behavioural fingerprinting (timing, capability, tab-completion text) is out of scope.

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

Inbound is filtered on the same policy (`ClientPacketListener#handleCustomPayload`): a
mod that never receives the probe cannot answer it. A probing server will send on your
channels whether or not you advertised them.

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

This is the hardest part of the implementation and the earlier draft skipped it.

At `Connection#send` the payload is already a typed Fabric record, not bytes, and
depending on Fabric's internal record types is what `RawPayload` exists to avoid. The
route: serialize the outgoing payload through its own codec into a `FriendlyByteBuf`,
apply the filter at byte level, and re-wrap the result as a `RawPayload` carrying the
same channel id. Fabric's types are never named or imported.

Byte-level filtering needs the wire layout preceding the identifier collection. All five
rewritable channels, read from source:

| Channel | Prefix before the collection |
| --- | --- |
| `minecraft:register`, `minecraft:unregister` | none - NUL-separated ASCII, whole body |
| `c:register` | varint version, UTF phase |
| `fabric:accepted_attachments_v1` | none |
| `fabric:recipe_sync/supported_serializers` | none |
| `fabric:custom_ingredient_sync` | varint protocolVersion |

Collections are a varint count followed by that many identifiers. A channel with no entry
in this table is never rewritten - only passed or dropped - so an unrecognised wire format
cannot corrupt a packet.

### Components

Pure, no Minecraft imports, unit-testable without a client:

- `ExposurePolicy` - immutable. `decide(channelId, serverKey)` returns `EXPOSE` or
  `WITHHOLD` from the exposed-namespace set, the per-server grants, and the channel-level
  refinements. Any throw is caught and treated as `WITHHOLD`.
- `RegistrationFilter` - filters `minecraft:register` and `minecraft:unregister` bodies
  (NUL-separated ASCII). Bytes in, bytes out.
- `IdentifierSetFilter` - filters a varint-counted identifier collection behind a known
  prefix, per the wire-layout table above. Covers `c:register` and the three leaky Fabric
  payloads; the prefix is copied through untouched.
- `ChannelLedger` - the record of channels observed and their last decision. Persisted,
  read by `/cmdguard exposure`, and never consulted by the filter.

Minecraft-facing, kept thin:

- `RawPayload` - a `(Identifier, byte[])` `CustomPacketPayload` so rewritten payloads go
  out as plain bytes, with no dependency on Fabric's internal payload types, which would
  break on every Fabric API bump.
- `ExposureGuard` - the facade the mixins call. Returns the packet, a rewritten packet,
  or null to cancel. Holds the connection's policy snapshot and feeds the ledger.
- `ConnectionMixin`, plus inbound handling on the existing `ClientPacketListenerMixin`.

### Per-server policy

Global policy plus a `Map<serverAddress, Set<String>>` of additional exposures, keyed on
`ServerData#ip` lowercased, with `singleplayer` and `lan` as reserved keys.

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

- `cmdguard.mixins.json` already sets `"required": true` and `defaultRequire: 1`. A
  mapping change breaks the build rather than shipping a jar whose guard silently does
  nothing. Mapped signatures cannot be verified on the build machine - there is no local
  Loom cache - so this is the mechanism that catches a wrong guess.
- A throw inside the policy withholds.
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

Covered: `ExposurePolicy` decisions including the unknown-namespace default;
`RegistrationFilter` round-trips for both codecs; `IdentifierSetFilter`; a test
asserting `minecraft:brand` is never withheld or modified; a test asserting no filter
ever emits a channel or identifier absent from its input (the no-fabrication boundary,
enforced by code rather than intent); a test asserting the advertisement/enforcement
invariant.

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

A packet capture of the configuration phase, if the tester can take one, is the only
direct evidence that no withheld identifier reached the wire.

## Out of scope

The tab-completion suggestion guard (`ServerboundCommandSuggestionPacket` sends partial
command text before you press enter). Separate feature, separate mechanism.
