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

The namespace rule only *seeds* the policy. At first run the live channel set is
resolved, filtered by the rule, and persisted as an explicit list of channel ids the
user can review and edit. Namespace is never treated as permanent proof of ownership: a
third-party mod registering under `minecraft:` appears in that reviewable list rather
than passing on a heuristic.

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
| `minecraft:register`, `minecraft:unregister`, `c:register` | Rewrite. Channel list filtered to exposed channels. Entries removed, never added. |
| Leaky Fabric payloads carrying `Set<Identifier>` | Rewrite. Identifier set filtered to exposed namespaces. |
| Anything else | Pass or drop per policy. A dropped payload is silence; nothing is sent in its place. |

### Components

Pure, no Minecraft imports, unit-testable without a client:

- `ExposurePolicy` - `decide(channelId, serverKey)` returns `EXPOSE` or `WITHHOLD`. Holds the
  pinned channel list and the user's additions. Any throw is caught and treated as
  `WITHHOLD`.
- `RegistrationFilter` - filters `minecraft:register` bodies (NUL-separated ASCII) and
  `c:register` bodies (varint version, UTF phase, identifier collection). Bytes in,
  bytes out.
- `IdentifierSetFilter` - filters the `Set<Identifier>` in the three leaky Fabric
  payloads down to exposed namespaces.

Minecraft-facing, kept thin:

- `RawPayload` - a `(Identifier, byte[])` `CustomPacketPayload` so rewritten payloads go
  out as plain bytes, with no dependency on Fabric's internal payload types, which would
  break on every Fabric API bump.
- `ExposureGuard` - the facade the mixins call. Returns the packet, a rewritten packet,
  or null to cancel. Owns the session counters.
- `ConnectionMixin`, plus inbound handling on the existing `ClientPacketListenerMixin`.

### Per-server policy

Global policy plus a `Map<serverAddress, Set<channel>>` of additional exposures, keyed
on `ServerData#ip` lowercased, with `singleplayer` and `lan` as reserved keys.
`/cmdguard expose <channel>` applies to the current server; `--global` applies
everywhere.

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
- `/cmdguard exposure` lists every registered channel as EXPOSED or WITHHELD with the
  session's withheld-payload count. `fabric:registry/sync/complete` is listed as always
  exposed, annotated: required to finish joining, carries no data.

## Known costs

An identifier you withhold is one the server will not sync to you. Withheld attachment
types, recipe serializers and custom ingredients degrade the mods that own them. That is
the trade being bought, and it is coherent: you declined to participate rather than
misreported.

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

## Out of scope

The tab-completion suggestion guard (`ServerboundCommandSuggestionPacket` sends partial
command text before you press enter). Separate feature, separate mechanism.
