# Developer notes — how a command leaks, and where it can't

All verified 2026-08-28 against Mojang's published 1.21.11 client mappings and Fabric
API branch `1.21.11`. This project uses **official Mojang mappings**.

## The six leak vectors (for anyone extending this mod)

| # | Slip-up | Why it's easy to miss | Guarded here? |
|---|---|---|---|
| 1 | `CommandRegistrationCallback` instead of `ClientCommandRegistrationCallback` | one word apart; both compile | reference command uses the client one |
| 2 | `connection.sendCommand(...)` as a "quick fix" | looks like the obvious way to run a command | that IS the hooked path — it's guarded |
| 3 | `SuggestionProviders.ASK_SERVER` on a client arg | tab-completion sends a packet; command itself looks fine | avoid on client commands; none used here — and the suggestion guard now filters the packet itself, without exempting client roots |
| 4 | player feedback via a server-routed message | safe path is `Minecraft#gui.getChat().addMessage` | `OutboundGuard.say` / `sendFeedback` only |
| 5 | a generic root literal | shadows the server command AND falls through when this mod isn't loaded | root is the mod id `cmdguard` |
| 6 | registering a custom networking channel | announced via `minecraft:register` | this mod registers none |

## Known hazards

### The login phase — now covered, and the one hazard that can cost a join

Verified 2026-08-29 against the same decompiled 1.21.11 sources and the Fabric API 0.141.6
sources pinned in `gradle.properties`. The investigation is
`.superpowers/sdd/login-phase-spike.md`; this is the summary and the residual hazard.

**Why the login phase needed separate work.** It uses a different pair of packets from the
configuration and play phases. `ClientboundCustomQueryPacket` is not a
`ClientboundCustomPayloadPacket`, and `ServerboundCustomQueryAnswerPacket` is
`record ServerboundCustomQueryAnswerPacket(int transactionId, @Nullable
CustomQueryAnswerPayload payload) implements Packet<ServerLoginPacketListener>` — not a
`ServerboundCustomPayloadPacket`. So both `instanceof` tests in `ExposureGuard` skipped
them even though both hooks saw the packets go past.
`net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl` is declared
`implements ClientLoginPacketListener` and extends none of this mod's mixin targets, so no
listener-level hook reaches it either.

**Why that was a disclosure and not just a gap.** Vanilla always answers a login query
with `null`, unconditionally, without ever looking at the channel:

```java
// ClientHandshakePacketListenerImpl
public void handleCustomQuery(ClientboundCustomQueryPacket clientboundCustomQueryPacket) {
    this.updateStatus.accept(Component.translatable("connect.negotiating"));
    this.connection.send(new ServerboundCustomQueryAnswerPacket(clientboundCustomQueryPacket.transactionId(), null));
}
```

Fabric API's `ClientHandshakePacketListenerImplMixin` cancels that and lets
`ClientLoginNetworkAddon.handlePacket` answer instead whenever a mod registered a handler
for the queried channel; with no handler it returns false and the vanilla `null` stands.
So **answering at all — not the contents of the answer — is the disclosure**, and a server
can probe for a specific mod by sending one login query.

**Outbound filtering is impossible here, verified.** `javap` on the two payload
interfaces, side by side, is the whole finding:

```
net.minecraft.network.protocol.login.custom.CustomQueryPayload            // the QUERY side
    public abstract net.minecraft.resources.Identifier id();
    public abstract void write(net.minecraft.network.FriendlyByteBuf);

net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload      // the ANSWER side
    public abstract void write(net.minecraft.network.FriendlyByteBuf);
    // ^ that is the entire interface. There is no id().
```

`ServerboundCustomQueryAnswerPacket#write` is `writeVarInt(transactionId)` then
`writeNullable(payload, …)`. No identifier is ever written, so at the
`Connection#sendPacket` hook the answer is an opaque `(int, maybe-bytes)` pair and no
channel decision can be made on it. **The decision must be made inbound.**

**And it must produce an answer rather than suppress one.** Cancelling the inbound query
does not withhold, it stalls. There is no per-transaction accounting anywhere in vanilla —
no map, set or counter keyed by transaction id on either side — so an unanswered query is
not refused; a querying server is blocked on that transaction and sends nothing while it
waits, and after 30 s of receiving nothing the client's own `ReadTimeoutHandler(30)`
(`Connection.java:438`) fires `disconnect.timeout`. A hang is a behaviour no vanilla client
exhibits, so it would disclose strictly more than the answer it was meant to avoid.

**What is implemented.** At the same `Connection#channelRead0` `@ModifyVariable(HEAD,
argsOnly, ordinal = 0)` the bundle filter already uses, a `ClientboundCustomQueryPacket`
whose channel is withheld is replaced with
`new ClientboundCustomQueryPacket(txid, new DiscardedQueryPayload(id))`. Fabric's mixin
tests `packet.payload() instanceof PacketByteBufLoginQueryRequestPayload`, which now fails,
so its addon is never consulted and vanilla's unconditional `null` answer stands. Nothing
is cancelled, no packet CmdGuard constructs goes on the wire, and the transaction id is
preserved. Both constructors are public — `javap`:

```
public net.minecraft.network.protocol.login.ClientboundCustomQueryPacket(int, CustomQueryPayload)
    descriptor: (ILnet/minecraft/network/protocol/login/custom/CustomQueryPayload;)V
public net.minecraft.network.protocol.login.custom.DiscardedQueryPayload(net.minecraft.resources.Identifier)
    descriptor: (Lnet/minecraft/resources/Identifier;)V
```

**How the channel id is read, and what that was verified against.** Through the vanilla
interface method `CustomQueryPayload#id() ()Lnet/minecraft/resources/Identifier;` — no
Fabric `impl` import is needed for it. Two readings agree:

- `javap -p -s` on the mapped merged jar shows `CustomQueryPayload` declaring `id()`, and
  `DiscardedQueryPayload` (vanilla's own decode of an unrecognised query, produced by
  `ClientboundCustomQueryPacket.readUnknownPayload`) implementing it as a record component.
- The Fabric API 0.141.6 `fabric-networking-api-v1` 5.1.6 sources show
  `ClientboundCustomQueryPacketMixin` injecting at `HEAD` of `readPayload` with
  **no condition at all** — `cir.setReturnValue(new PacketByteBufLoginQueryRequestPayload(id,
  PayloadHelper.read(buf, MAX_PAYLOAD_SIZE)))` — and that type is declared
  `public record PacketByteBufLoginQueryRequestPayload(Identifier id, FriendlyByteBuf data)
  implements CustomQueryPayload`.

So with Fabric API installed the payload is *always* a
`PacketByteBufLoginQueryRequestPayload`, and without it a `DiscardedQueryPayload`; both
carry the id and both reach it through the same interface method. The read is correct
either way, and the code depends on the vanilla interface rather than on Fabric's
replacement staying unconditional.

**Why the null answer is a refusal and not a lie.** The record component is declared
`@Nullable` and written with `writeNullable`, so `null` is the protocol's own encoding of
"there is no payload" rather than an invented stand-in. It carries zero identifiers, since
`CustomQueryAnswerPayload` has no `id()`. It is vanilla's unconditional behaviour, not a
pose. Fabric's own API treats `null` as the decline value — `ClientLoginNetworkAddon` emits
`result == null ? null : new PacketByteBufLoginQueryResponse(result)`, i.e. a registered
handler that completes with `null` produces a byte-identical packet. And it is the same act
this mod already performs when it omits a channel from `minecraft:register`, moved one
phase earlier.

**Hazard 1: per-server grants cannot apply, and the wrong remedy makes the mod look
broken.** `ExposureGuard.beginConnection` runs from `ClientCommonPacketListenerImpl`'s
constructor, which is first reached at `handleLoginFinished` — *after* every login query.
So no per-connection snapshot exists while login queries are arriving and
`ConnectionMixin#cmdguard$snapshot()` necessarily returns `globalsOnlySnapshot()`. The
remedy for a login broken by this filter is `/cmdguard expose global <namespace>` plus a
reconnect. **The per-server form cannot help**, and a user told to run it will watch it
fail and conclude the mod is broken. Do not "fix" this by trying to key the login filter to
a server; the key does not exist yet.

**Hazard 2: the failure mode is worse here than anywhere else, so it is logged at WARN.**
Elsewhere a withheld channel means a feature quietly does not work. Here, a server whose
handshake genuinely needs a real answer refuses the connection — and it surfaces as the
*server's* disconnect screen with nothing on it pointing at CmdGuard. There is no chat to
write to and `/cmdguard exposure` is unreachable from a disconnect screen, so `latest.log`
is the only place the cause can appear. Every substitution logs at WARN, naming the channel
and the exact remedy command. That is not polish; it is the difference between a
diagnosable failure and a mystery. The ledger is deliberately *not* written from the login
path: `beginConnection` resets it at `handleLoginFinished`, so a login entry would be wiped
before anyone could read it and would be counted against the *previous* connection's tally
on the way out.

**Hazard 3: none of this has been run in Minecraft.** There is no client on this machine,
mixin application is launch-time, and a green build proves nothing about any mixin target.
The spike's "What in-game acceptance would look like" section describes the four-run matrix
against a rig server that actually sends a login query; until at least its runs (a)-(c)
have been done, the correct claim is that this builds and matches the decompiled sources —
nothing stronger.

### `"required": true` does **not** make the build catch a wrong mixin target

Checked directly, 2026-08-29, by pointing an `@Inject` at `channelReadZZZ` — a method that
does not exist on `Connection` — and running `./gradlew compileJava`: **BUILD SUCCESSFUL**.
The mixin annotation processor validates the mixin's *target class* and the shape of the
handler method, not that the named target method resolves.

`"required": true` with `defaultRequire: 1` still does its job — it makes a failed
injection a hard crash at **launch** instead of a silently disabled guard — but that is a
runtime check, not a build-time one. The design doc's "a mapping change breaks the build
rather than shipping a jar whose guard silently does nothing" overstates it: it breaks the
*game launch*. A green build is evidence of compilation and nothing more; every descriptor
in this file was verified with `javap` against the mapped jar for exactly this reason.

### Unguarded outbound path: `ServerboundSetCommandBlockPacket`

**This is a gap in the command guard, not in the tab-completion guard, and it predates
both.** Nobody had noticed it until the tab-completion review asked why command-block
completions were being withheld for no protection gain. Recorded here rather than fixed:
covering it is a separate decision, with its own trade-offs, and is deliberately *not*
implemented.

**What it carries.** Verified 2026-08-29 with `javap -p -c` over the Mojang-mapped 1.21.11
merged jar — the same reading as everything else in this file:

```
net.minecraft.network.protocol.game.ServerboundSetCommandBlockPacket
    public class ... implements Packet<ServerGamePacketListener>
    private final net.minecraft.core.BlockPos pos;
    private final java.lang.String command;      // FriendlyByteBuf.readUtf()
    private final boolean trackOutput;
    private final boolean conditional;
    private final boolean automatic;
    private final CommandBlockEntity$Mode mode;

    public ServerboundSetCommandBlockPacket(BlockPos, String, CommandBlockEntity$Mode, boolean, boolean, boolean)
```

The `String` is the **whole command text**, not a prefix. `CommandBlockEditScreen`
`#populateAndSendPacket` reads it straight off the edit box and sends it:

```
0: minecraft.getConnection()                       -> ClientPacketListener
7: new ServerboundSetCommandBlockPacket
12:   autoCommandBlock.getBlockPos()
19:   commandEdit.getValue()                       // the full typed command
26:   mode, isTrackOutput(), conditional, autoexec
50: ClientPacketListener.send(Packet)
```

and `AbstractCommandBlockEditScreen#onDone` is `{ populateAndSendPacket(); ... }` — i.e.
pressing **Done** sends it. `ServerboundSetCommandMinecartPacket` is the same hole in the
minecart form: `private final int entity; private final java.lang.String command; private
final boolean trackOutput;`, sent by `MinecartCommandBlockEditScreen#populateAndSendPacket`
through the same `ClientPacketListener.send`.

**Why it is not covered.** Nothing in `src` mentions either packet. `ConnectionMixin`'s
outbound handler tests `packet instanceof ServerboundCommandSuggestionPacket` and nothing
else, and `ClientPacketListenerMixin` hooks only `sendCommand` / `sendUnattendedCommand` —
neither of which a command-block save goes through. Both packets do pass through
`Connection#sendPacket`, so the choke point is already the right one; only the type test is
missing.

**The consequence for the tab-completion guard.** `AbstractCommandBlockEditScreen` is the
only surface that produces slashless suggestion text, and the guard judges it — correctly
and deliberately, since vanilla's own `handleCustomCommandSuggestions` treats that text as a
command. But every vanilla command-block root (`setblock`, `execute`, `give`, `summon`) is
off `GuardConfig.STARTER_ALLOWLIST`, so completions silently stop working in command blocks,
**and withholding them protects nothing** — pressing Done ships the full text anyway. The
parity argument that justifies the whole feature ("a suggestion request is judged by the
same rule as the command it would become") does not hold on that one surface while this gap
stands. The README says so plainly in its tab-completion section.

**Not fixed by a screen-type exemption, and that was a choice.** Keying behaviour off a
screen class is fragile — it can be reached from a subclass or another mod's screen, and it
splits one rule into two — and one consistent rule is easier to reason about than a carve-out
whose justification lives in a different file. The honest fix is to guard the packet; the
honest interim is to say so.

**If it is ever covered**, note the shape is not the same as the command guard's: the text
arrives already complete and already committed by the user, there is no `clicked`
distinction, and cancelling the packet leaves the command block holding its *old* command
with the edit screen closed — a silent no-op the player has no reason to expect. That needs
a chat message (`OutboundGuard#reportBlocked` is the precedent) and it needs deciding what
"blocked" should mean for a block the player is standing in front of. Hence: a separate
decision.

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
- `ServerboundCommandSuggestionPacket(int id, String command)` — tab completion; reaches
  `Connection#sendPacket` like everything else, accessors are `getId()` / `getCommand()`
- `net.minecraft.resources.Identifier` — **renamed from `ResourceLocation`** in 1.21.11
- `ClientPlayNetworking.getGlobalReceivers() / getSendable() / canSend(Identifier)`
- `C2SPlayChannelEvents.Register.onChannelRegister(handler, sender, client, List<Identifier>)`

## Verified mappings, 1.21.11

Verified 2026-08-29 for Tasks 6-10. Two independent readings, which agree line for line:

1. `./gradlew genSources` (Vineflower, `BUILD SUCCESSFUL in 40m 3s`, 6622 classes) →
   `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-2ae02fda0f/1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2/…-sources.jar`.
   All Java quoted below is copied from that jar, unedited.
2. `javap -p -s -c` over the Mojang-mapped merged jar
   (`minecraft-merged-1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2.jar`), which is
   where every *descriptor* below comes from — the bytecode is what the compiler and the
   mixin remapper actually link against.

Mappings are `loom.officialMojangMappings()`. Everything here was read, not recalled.

### Outbound: `net.minecraft.network.Connection`

Three public `send` overloads:

```
public void send(Packet<?>)                                 (Lnet/minecraft/network/protocol/Packet;)V
public void send(Packet<?>, ChannelFutureListener)          (Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V
public void send(Packet<?>, ChannelFutureListener, boolean) (Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V
```

**Yes — every overload funnels into one private method.** From `Connection.java`:

```java
public void send(Packet<?> packet) {
    this.send(packet, null);
}

public void send(Packet<?> packet, @Nullable ChannelFutureListener channelFutureListener) {
    this.send(packet, channelFutureListener, true);
}

public void send(Packet<?> packet, @Nullable ChannelFutureListener channelFutureListener, boolean bl) {
    if (this.isConnected()) {
        this.flushQueue();
        this.sendPacket(packet, channelFutureListener, bl);
    } else {
        this.pendingActions.add(connection -> connection.sendPacket(packet, channelFutureListener, bl));
    }
}
```

**The funnel — the Task 7 mixin target:**

```
private void sendPacket(Packet<?> packet, @Nullable ChannelFutureListener channelFutureListener, boolean bl)
    descriptor: (Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V
```

```java
private void sendPacket(Packet<?> packet, @Nullable ChannelFutureListener channelFutureListener, boolean bl) {
    this.sentPackets++;
    if (this.channel.eventLoop().inEventLoop()) {
        this.doSendPacket(packet, channelFutureListener, bl);
    } else {
        this.channel.eventLoop().execute(() -> this.doSendPacket(packet, channelFutureListener, bl));
    }
}
```

`sendPacket` is private, so its callers are exactly the three in this class:

1. `send(Packet, ChannelFutureListener, boolean)`, connected branch.
2. the deferred `Consumer<Connection>` in that same method's disconnected branch, drained
   later from `pendingActions`.
3. `initiateServerboundConnection` — **which never touches a public `send`**:

```java
this.runOnceConnected(connection -> {
    this.setupInboundProtocol(protocolInfo2, clientboundPacketListener);
    connection.sendPacket(new ClientIntentionPacket(SharedConstants.getCurrentVersion().protocolVersion(), string, i, clientIntent), null, true);
    this.setupOutboundProtocol(protocolInfo);
});
```

Caller (3) is the reason to hook `sendPacket` and not a `send` overload: vanilla already
has an outbound path that bypasses all three public overloads. Hooking `send` would leave
it unfiltered. (It only carries the handshake, but the principle is what matters — the
funnel is provably complete and `send` provably is not.)

Downstream of the funnel:

```
private void doSendPacket(Packet<?>, ChannelFutureListener, boolean)
    descriptor: (Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V
```

`doSendPacket` holds the only `Channel.write` / `Channel.writeAndFlush` calls in the class
that carry a `Packet`; the other two `writeAndFlush` sites are in `setupInboundProtocol` /
`setupOutboundProtocol` and write protocol-switch markers. So `doSendPacket` is also a
complete choke point, but it sits *after* the deferred-queue split and may run on the netty
event loop — `sendPacket` is the better place for a filter that cancels or swaps.

**Direction accessor:**

```
public net.minecraft.network.protocol.PacketFlow getSending()
    descriptor: ()Lnet/minecraft/network/protocol/PacketFlow;
```

```java
public PacketFlow getReceiving() { return this.receiving; }
public PacketFlow getSending()   { return this.receiving.getOpposite(); }
```

`net.minecraft.network.protocol.PacketFlow` is `enum { SERVERBOUND, CLIENTBOUND }` with
`public PacketFlow getOpposite()`. On a client connection `getSending()` is `SERVERBOUND`.
The mixin must test it, because `Connection` is shared by both sides.

**In-process-channel accessor, the `ConnectionMixin.java:117-118` `@Shadow` for the
singleplayer login exemption:**

```
public boolean isMemoryConnection()
    descriptor: ()Z
```

```java
public boolean isMemoryConnection() {
    return this.channel instanceof LocalChannel || this.channel instanceof LocalServerChannel;
}
```

Verified 2026-08-29 the same two ways as the rest of this section. The decompiled source
above is copied unedited from the `genSources` sources jar; `javap -p -s -c` over the
mapped merged jar shows the same two-branch `instanceof` test against the `channel` field
(`Lio/netty/channel/Channel;`) — `LocalChannel` then `LocalServerChannel`, `ior`-ed via
`ifne`/`ifeq` into `iconst_1`/`iconst_0` — so the bytecode and the source agree line for
line with no third branch and nothing else read. This is what
`cmdguard$forceVanillaLoginAnswer`'s singleplayer exemption rests on entirely, so it is
recorded here rather than left to stand on its own Javadoc's word.

**Everything client-side really does reach the funnel.** Read, not assumed:

- `ClientCommonPacketListenerImpl#send(Packet<?>)` —
  `(Lnet/minecraft/network/protocol/Packet;)V` — is `{ this.connection.send(packet); }`.
- `ClientCommonPacketListenerImpl#sendDeferredPackets()` drains `deferredPackets` through
  that same `send(Packet)`.
- Fabric API 0.141.6's `ClientPlayNetworking.send(CustomPacketPayload)` (read from the
  Mojang-remapped Fabric sources jar) ends in
  `Minecraft.getInstance().getConnection().send(createC2SPacket(payload));` — so
  third-party mod traffic funnels through `Connection#sendPacket` too.

### Outbound: tab-completion requests — the command guard's second half

Verified 2026-08-29 the same two ways as everything else in this section: the decompiled
1.21.11 sources from the `genSources` jar, and `javap` over the Mojang-mapped merged jar
for every descriptor.

**The leak.** `ClientPacketListenerMixin` guards `sendCommand` and
`sendUnattendedCommand`, so `/somemod:debug` never leaves the client. Pressing Tab sends
the same text earlier. From `net.minecraft.client.multiplayer.ClientSuggestionProvider`:

```java
@Override
public CompletableFuture<Suggestions> customSuggestion(CommandContext<?> commandContext) {
    if (this.pendingSuggestionsFuture != null) {
        this.pendingSuggestionsFuture.cancel(false);
    }

    this.pendingSuggestionsFuture = new CompletableFuture<>();
    int i = ++this.pendingSuggestionsId;
    this.connection.send(new ServerboundCommandSuggestionPacket(i, commandContext.getInput()));
    return this.pendingSuggestionsFuture;
}
```

**The packet** (`javap -p -s`, mapped merged jar). Note it is a plain class, **not** a
record — the accessors are `getId()` / `getCommand()`, not `id()` / `command()`:

```
net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket
    public class ... implements Packet<ServerGamePacketListener>
    private final int id;
    private final java.lang.String command;

    public ServerboundCommandSuggestionPacket(int, java.lang.String)
        descriptor: (ILjava/lang/String;)V
    public int getId()
        descriptor: ()I
    public java.lang.String getCommand()
        descriptor: ()Ljava/lang/String;
```

**It reaches the existing outbound choke point, so no new mixin was needed.** The field
`ClientSuggestionProvider.connection` is a `ClientPacketListener`, and
`ClientCommonPacketListenerImpl#send(Packet<?>)` is a one-line
`{ this.connection.send(packet); }` into `Connection#send(Packet)`, which is
`send(packet, null)` → `send(packet, null, true)` → `sendPacket(...)` — the private funnel
this file already documents above. The guard is therefore one more `@Inject` at `HEAD` of
`sendPacket`, alongside the exposure layer's.

**What the string actually contains.** Brigadier decides this, not Minecraft.
`CommandDispatcher#getCompletionSuggestions(ParseResults, int cursor)` — read from the
bytecode of `brigadier-1.3.10.jar` — does:

```
30: aload_1  ParseResults.getReader()          // the StringReader the caller passed in
34:          ImmutableStringReader.getString() // the WHOLE line, slash included
43: iconst_0
44: iload_2  cursor
45:          String.substring(0, cursor)       // -> the string handed to build(...)
122:         CommandContextBuilder.build(<that substring>)
```

So `commandContext.getInput()` is **the typed line truncated at the cursor**, and it keeps
whatever leading `/` the edit box had. Which it has depends on the screen:

- `ChatScreen` builds `new CommandSuggestions(..., commandsOnly = false, ...)`, and
  `CommandSuggestions#updateCommandInfo` only takes the dispatcher branch when the text
  starts with `/`. So chat text carries the slash.
- `AbstractCommandBlockEditScreen` builds it with `commandsOnly = true`, and its box has no
  slash. Its completion requests ride this same packet with no leading `/`.
- **Plain chat suggestions never produce this packet at all.** The non-command branch of
  `updateCommandInfo` is `SharedSuggestionProvider.suggest(getCustomTabSugggestions(), ...)`
  — a local list of player names, no packet. So "no leading slash" here means "command
  block", not "chat".

Vanilla's server half agrees: `ServerGamePacketListenerImpl#handleCustomCommandSuggestions`
opens with `new StringReader(packet.getCommand())` and skips a `'/'` only `if
(stringReader.canRead() && stringReader.peek() == '/')`. `CommandRoot.of` matches that.

**Command *names* are completed locally.** Worth recording, because it sizes the usability
cost of the conservative policy. `updateCommandInfo` parses against
`ClientPacketListener#getCommands()` — the tree the server already sent in
`ClientboundCommandsPacket` — and brigadier's `getCompletionSuggestions` iterates
`nodeBeforeCursor.parent.getChildren()` calling `listSuggestions` on each. A root-level
child is a `LiteralCommandNode`, whose `listSuggestions` matches literals in memory and
sends nothing. Only an **argument** node whose suggestion provider asks the server (vanilla's
`SuggestionProviders.ASK_SERVER`, which routes to `SharedSuggestionProvider#customSuggestion`)
produces a `ServerboundCommandSuggestionPacket`. So `/ms<Tab>` normally sends nothing, and
withholding it costs nothing. The policy is still enforced conservatively because the command
tree is server-supplied: a server that wanted the client's keystrokes could hang an
`ASK_SERVER` argument node directly under the root and receive every character typed after
the slash.

**Cancelling the packet is safe.** `customSuggestion` has already stored its
`CompletableFuture` in `CommandSuggestions.pendingSuggestions`. Every read of that field is
guarded: the render path tests `this.pendingSuggestions != null && this.pendingSuggestions
.isDone()` before `join()`, and the only other `join()` (in `updateUsageInfo`) runs solely
from the `thenRun` callback that a completion triggers. A future that never completes leaves
the popup empty and nothing else; the next keystroke's `customSuggestion` cancels it. No
`join()` on an incomplete future exists, so there is no hang and no exception.

**Leak vector #3, closed.** The table at the top of this file has warned since day one that
`SuggestionProviders.ASK_SERVER` on a *client* command's argument sends a packet while the
command itself looks local. That is why the suggestion guard deliberately does **not** exempt
a client-dispatcher root the way `OutboundGuard#shouldBlock` does: for a typed command, a
client root never reaches the network; for a completion request it does.

### Inbound: `net.minecraft.network.Connection#channelRead0` — the real choke point

**This is where the inbound filter lives**, and the reason is a race, not taste. Verified
2026-08-29 by the same two readings as the rest of this section.

Three handlers hang off this one method, because one pipeline message is not one packet of
one type: `cmdguard$dropWithheldInbound` cancels a bare `ClientboundCustomPayloadPacket`,
`cmdguard$filterBundledPayloads` rebuilds a `ClientboundBundlePacket` without its withheld
sub-payloads, and `cmdguard$forceVanillaLoginAnswer` substitutes the payload of a
login-phase `ClientboundCustomQueryPacket` (see "The login phase" under Known hazards).
All three attach at `HEAD` and their relative order is undefined, which is fine because
they match mutually disjoint packet types and each returns its argument untouched when it
does not match.

```
protected void channelRead0(ChannelHandlerContext, Packet<?>)
    descriptor: (Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V
```

`javap` also shows the `SimpleChannelInboundHandler` bridge —
`protected void channelRead0(ChannelHandlerContext, Object)`,
`(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Object;)V` — so, exactly as with the
two `handleCustomPayload` overloads, the mixin must key on the full descriptor or it can
bind the bridge instead of the real method.

```java
public class Connection extends SimpleChannelInboundHandler<Packet<?>> {

    protected void channelRead0(ChannelHandlerContext channelHandlerContext, Packet<?> packet) {
        if (this.channel.isOpen()) {
            PacketListener packetListener = this.packetListener;
            if (packetListener == null) {
                throw new IllegalStateException("Received a packet before the packet listener was initialized");
            }

            if (packetListener.shouldHandleMessage(packet)) {
                try {
                    genericsFtw(packet, packetListener);
                } catch (RunningOnDifferentThreadException var5) {
                } catch (RejectedExecutionException rejectedExecutionException) {
                    this.disconnect(Component.translatable("multiplayer.disconnect.server_shutdown"));
                } catch (ClassCastException classCastException) {
                    LOGGER.error("Received {} that couldn't be processed", packet.getClass(), classCastException);
                    this.disconnect(Component.translatable("multiplayer.disconnect.invalid_packet"));
                }

                this.receivedPackets++;
            }
        }
    }
```

**Why this is complete.** `Connection` installs *itself* as the terminal inbound netty
handler:

```java
public void configurePacketHandler(ChannelPipeline channelPipeline) {
    channelPipeline.addLast("hackfix", new ChannelOutboundHandlerAdapter() { ... })
        .addLast("packet_handler", this);
}
```

Every inbound **pipeline message**, in every protocol phase — login, configuration, play —
reaches `channelRead0`, and no pipeline message reaches a `PacketListener` without passing
through it first (`genericsFtw` is the only dispatch, and it is one line below the
injection point). One `Connection` object survives the whole session, so one hook covers
all phases.

**"Pipeline message", not "packet" — the distinction is load-bearing, and an earlier
version of this paragraph got it wrong.** It used to read "every decoded *packet* … and
nothing reaches a `PacketListener` without passing through it first", which is false in the
play phase and is exactly what hid the bundle gap below for a whole review cycle. In the
play protocol one pipeline message can be a `ClientboundBundlePacket` carrying an arbitrary
number of real packets, and those sub-packets are dispatched to the listener later, by
`ClientPacketListener#handleBundlePacket`, *without* going through `channelRead0` again. So:

- one hook at `channelRead0` sees every inbound message — that part is true and is why the
  outbound/inbound choke-point argument stands;
- a hook at `channelRead0` that only tests `instanceof SomePacketType` sees only the
  outermost packet of each message, and must unwrap a bundle itself.

See "Inbound: packet bundles" below for the verification and for what the filter does
about it.

**Why not the packet listener.** The filter used to inject at `HEAD` of
`ClientCommonPacketListenerImpl#handleCustomPayload(ClientboundCustomPayloadPacket)`.
Fabric API 0.141.6's
`net.fabricmc.fabric.mixin.networking.client.ClientCommonPacketListenerImplMixin` injects
at `@At("HEAD")` of **the same method with the same descriptor** and cancels whenever
`ClientPlayNetworkAddon` / `ClientConfigurationNetworkAddon`.`handle(payload)` returns
`true` — i.e. whenever a client mod has a registered receiver for the channel, which is
*precisely* the set of payloads the inbound filter exists to block. Neither mixin declared
a priority, both default to 1000, and `@Inject(order = ...)` does not sort across mods. If
Fabric's callback ran first the filter was a silent, total no-op. A priority number is a
bet; `channelRead0` removes the race. Same argument as hooking `sendPacket` rather than a
public `send` overload.

Fabric API mixes into `Connection` as well (`net.fabricmc.fabric.mixin.networking.ConnectionMixin`)
— `<init>`, `sendPacket`, `validateListener`, `channelInactive`, `handleDisconnection`,
`setupInboundProtocol`, `setupOutboundProtocol` — but touches **no** inbound packet-handling
method, so there is nothing here to race with.

**Cancelling here is safe.** A cancelled `channelRead0` simply never hands the packet to
the listener, which is what vanilla itself does two lines later for any payload whose
channel has no receiver — it arrives as a `DiscardedPayload` and is dropped. The only other
consequence is that `receivedPackets` (a debug counter) is not incremented for the dropped
packet. The injection runs on the netty event loop, ahead of `DiscardedPayload`'s early-out
and ahead of `PacketUtils.ensureRunningOnSameThread`, so it must do no client-world work —
read the frozen per-connection snapshot and the payload's `Identifier`, and drop.

`Connection` is shared by client and server, so the flow must be checked:
`getReceiving() == PacketFlow.CLIENTBOUND` is the client side.

### Inbound: packet bundles — a real gap in the play phase, now closed

Verified 2026-08-29 by the same two readings as the rest of this section. A reviewer raised
this from knowledge of 1.21.x rather than from this jar; every step below was then checked
against the decompiled 1.21.11 sources and `javap` output, because an unverified pipeline
assumption is what put the gap there in the first place. **The claim is correct.** One
detail of the reported mechanism was not: there is no `BundlerInfo.EMPTY` in 1.21.11.
`ProtocolInfo#bundlerInfo()` is declared `@Nullable BundlerInfo bundlerInfo()` and the
non-play protocols simply never set one. Same conclusion, different mechanism.

**1. Only the play clientbound protocol bundles.** `ProtocolInfoBuilder` sets
`bundlerInfo` in exactly one place, `withBundlePacket`, and a repo-wide grep of the
decompiled sources finds exactly one caller of that method:

```java
// GameProtocols.java:128
public static final SimpleUnboundProtocol<ClientGamePacketListener, RegistryFriendlyByteBuf> CLIENTBOUND_TEMPLATE = ProtocolInfoBuilder.clientboundProtocol(
    ConnectionProtocol.PLAY,
    protocolInfoBuilder -> protocolInfoBuilder.withBundlePacket(
            GamePacketTypes.CLIENTBOUND_BUNDLE, ClientboundBundlePacket::new, new ClientboundBundleDelimiterPacket()
        )
```

So configuration, login, status, handshake and the *serverbound* play protocol all have
`bundlerInfo() == null`. They are unaffected, as reported.

**2. The bundler sits between `decoder` and `packet_handler`.** From `Connection.java`:

```java
public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocolInfo, T packetListener) {
    ...
    BundlerInfo bundlerInfo = protocolInfo.bundlerInfo();
    if (bundlerInfo != null) {
        PacketBundlePacker packetBundlePacker = new PacketBundlePacker(bundlerInfo);
        inboundConfigurationTask = inboundConfigurationTask.andThen(
            channelHandlerContext -> channelHandlerContext.pipeline().addAfter("decoder", "bundler", packetBundlePacker)
        );
    }
```

`configurePacketHandler` adds this `Connection` itself with `.addLast("packet_handler", this)`,
so `bundler` is strictly upstream of `packet_handler`.

**3. `PacketBundlePacker` swallows everything between the delimiters.** Its `decode` adds
nothing to the outbound list while a `Bundler` is active; `BundlerInfo.createForPacket`'s
`Bundler.addPacket` accumulates into an `ArrayList` and only returns a packet — the
assembled `ClientboundBundlePacket` — when it sees the second delimiter. The delimiter
packets themselves never reach `channelRead0`. The only restrictions on what may be inside
are `verifyNonTerminalPacket` and a 4096 cap.

**4. A custom payload can be inside one.** `CommonPacketTypes.CLIENTBOUND_CUSTOM_PAYLOAD`
is registered in that same play clientbound template (`GameProtocols.java:156`, via
`ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC`), and `Packet#isTerminal()` is
`default boolean isTerminal() { return false; }` with no override on
`ClientboundCustomPayloadPacket`, so `verifyNonTerminalPacket` lets it through. Type-wise
it fits too: the bundle holds `Packet<? super ClientGamePacketListener>` and
`ClientboundCustomPayloadPacket implements Packet<ClientCommonPacketListener>`, which
`ClientGamePacketListener` extends. Nothing prevents a server from putting its probe in a
bundle.

**5. Sub-packets bypass `channelRead0` entirely.**

```java
// ClientPacketListener.java:2465
public void handleBundlePacket(ClientboundBundlePacket clientboundBundlePacket) {
    PacketUtils.ensureRunningOnSameThread(clientboundBundlePacket, this, this.minecraft.packetProcessor());

    for (Packet<? super ClientGamePacketListener> packet : clientboundBundlePacket.subPackets()) {
        packet.handle(this);
    }
}
```

`ClientboundCustomPayloadPacket#handle` is `clientCommonPacketListener.handleCustomPayload(this)`
— i.e. straight into `ClientCommonPacketListenerImpl#handleCustomPayload` and therefore
into Fabric API's addon dispatch, having never met the inbound filter. **Confirmed bypass.**

**The fix**: `ConnectionMixin#cmdguard$filterBundledPayloads`, a `@ModifyVariable` on the
same `channelRead0` descriptor, replacing the bundle with one rebuilt from a *subset* of
its own sub-packets (`ExposureGuard#filterBundle`). Removal only; no sub-packet is ever
added or altered; a sub-packet that is not a `ClientboundCustomPayloadPacket` is never
dropped, because bundles are how vanilla batches entity spawn traffic. The whole bundle is
never cancelled, for the same reason. Descriptors used:

```
net.minecraft.network.protocol.game.ClientboundBundlePacket
    public class ClientboundBundlePacket extends BundlePacket<ClientGamePacketListener>

    public ClientboundBundlePacket(Iterable<Packet<? super ClientGamePacketListener>>)
        descriptor: (Ljava/lang/Iterable;)V
    public void handle(ClientGamePacketListener)
        descriptor: (Lnet/minecraft/network/protocol/game/ClientGamePacketListener;)V

net.minecraft.network.protocol.BundlePacket                    // the accessor is inherited
    public final Iterable<Packet<? super T>> subPackets()
        descriptor: ()Ljava/lang/Iterable;
    protected BundlePacket(Iterable<Packet<? super T>>)
        descriptor: (Ljava/lang/Iterable;)V

net.minecraft.network.protocol.game.ClientboundBundleDelimiterPacket
    public ClientboundBundleDelimiterPacket()
        descriptor: ()V

net.minecraft.network.ProtocolInfo
    @Nullable BundlerInfo bundlerInfo()                        // interface method
```

**An emptied bundle is emitted, not cancelled, and that is safe.** Read, not assumed: a
grep of the decompiled *base game* sources finds two callers of `subPackets()` —
`handleBundlePacket` above, whose body is a bare for-each, and `BundlerInfo.unbundlePacket`,
which is the *server's* outbound path and is unreachable here. Fabric API adds a third at
runtime (see below), and this mod hard-depends on Fabric API, so the base-game count alone
is not the full picture — the conclusion below still holds, but on all three, not two.
An empty bundle runs each of them zero times and is completely inert. Nothing asserts a
non-empty bundle. Emitting it keeps the decision in one place and leaves `receivedPackets`
honest.

**Fabric API 0.141.6 does touch bundles, and it helps rather than conflicts.**
`net.fabricmc.fabric.mixin.networking.BundlePacketMixin` is a `@ModifyVariable` on
`BundlePacket.<init>`'s `Iterable` argument that flattens nested bundles into a fresh
`ArrayList` — read against the decompiled source, its private `iterateBundle` helper calls
`subPackets()` on every nested `BundlePacket` it finds, recursively, to inline them. That
is the third caller the paragraph above accounts for: not reachable from the base game
alone, but reachable in the actual running mod, since Fabric API is a hard dependency.
Consequences, both benign: `subPackets()` is always a re-iterable `ArrayList`,
and the `ClientboundBundlePacket` this mod constructs goes through that same flattening
(a no-op copy of an already-flat, already-filtered list, since it holds no nested bundles).
Fabric's `ConnectionMixin` still touches no inbound handler — re-checked here: `<init>`,
`sendPacket`, `validateListener`,
`channelInactive`, `handleDisconnection`, `setupInboundProtocol`, `setupOutboundProtocol`.

**Not verified in game.** Nothing in this project has ever run in Minecraft. This is a
build plus a reading of the decompiled sources and the mapped bytecode; and per the
`"required": true` note above, a green build does not even prove the mixin targets resolve.

### Inbound: the client custom-payload handler (no longer hooked — kept for reference)

```
net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl
    public void handleCustomPayload(ClientboundCustomPayloadPacket)
        descriptor: (Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V
```

Declared on **`ClientCommonPacketListenerImpl`** (line 159), not on `ClientPacketListener`.
`ClientPacketListener` and `ClientConfigurationPacketListenerImpl` both extend it and
neither overrides this overload, so one mixin on the base class covers the play *and*
configuration phases.

```java
public void handleCustomPayload(ClientboundCustomPayloadPacket clientboundCustomPayloadPacket) {
    CustomPacketPayload customPacketPayload = clientboundCustomPayloadPacket.payload();
    if (!(customPacketPayload instanceof DiscardedPayload)) {
        PacketUtils.ensureRunningOnSameThread(clientboundCustomPayloadPacket, this, this.minecraft.packetProcessor());
        if (customPacketPayload instanceof BrandPayload brandPayload) {
            this.serverBrand = brandPayload.brand();
            this.telemetryManager.onServerBrandReceived(brandPayload.brand());
        } else {
            this.handleCustomPayload(customPacketPayload);
        }
    }
}
```

Note it returns early for `DiscardedPayload` *before* `ensureRunningOnSameThread`, so a
`@Inject(at = @At("HEAD"), cancellable = true)` here still runs off-thread. Do no
client-world work in that hook — just drop.

The second, *different* method it delegates to:

```
ClientCommonPacketListenerImpl
    protected abstract void handleCustomPayload(CustomPacketPayload)
        descriptor: (Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V

ClientPacketListener  (the play-phase implementation)
    public void handleCustomPayload(CustomPacketPayload)
        descriptor: (Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V
```

```java
// ClientPacketListener
public void handleCustomPayload(CustomPacketPayload customPacketPayload) {
    this.handleUnknownCustomPayload(customPacketPayload);
}

private void handleUnknownCustomPayload(CustomPacketPayload customPacketPayload) {
    LOGGER.warn("Unknown custom packet payload: {}", customPacketPayload.type().id());
}
```

The two overloads share the name `handleCustomPayload` and differ only in parameter type.
A mixin must key on the full descriptor or it will bind the wrong one.

### Payload accessors

```
net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
    public record ServerboundCustomPayloadPacket(CustomPacketPayload payload) implements Packet<ServerCommonPacketListener>

    public CustomPacketPayload payload()
        descriptor: ()Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;
    public ServerboundCustomPayloadPacket(CustomPacketPayload)
        descriptor: (Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V
    private static final int MAX_PAYLOAD_SIZE = 32767;

net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
    public record ClientboundCustomPayloadPacket(CustomPacketPayload payload) implements Packet<ClientCommonPacketListener>

    public CustomPacketPayload payload()
        descriptor: ()Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;

net.minecraft.network.protocol.common.custom.CustomPacketPayload            // interface
    CustomPacketPayload.Type<? extends CustomPacketPayload> type()
        descriptor: ()Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$Type;

net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type
    record Type<T extends CustomPacketPayload>(Identifier id)
    public net.minecraft.resources.Identifier id()
        descriptor: ()Lnet/minecraft/resources/Identifier;
```

`CustomPacketPayload.java` imports `net.minecraft.resources.Identifier` — **`Identifier`
confirmed**, and it is the literal descriptor of `CustomPacketPayload$Type#id()`. The
channel id of a payload packet is `packet.payload().type().id()`.

Watch out: `ServerboundCustomPayloadPacket#type()` is *not* the payload's type. It is
`Packet#type()`, returns `net.minecraft.network.protocol.PacketType` —
`()Lnet/minecraft/network/protocol/PacketType;` — and its body is
`return CommonPacketTypes.SERVERBOUND_CUSTOM_PAYLOAD;`. The payload's type only ever comes
from `payload().type()`.
