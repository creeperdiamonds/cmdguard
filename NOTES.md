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

## Known hazards

### The login phase is not covered

Verified 2026-08-29 against the same decompiled 1.21.11 sources and the Fabric API 0.141.6
sources pinned in `gradle.properties`.

The exposure layer covers the configuration and play phases and nothing else. Two
independent reasons, both structural:

- `net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl` is declared
  `implements ClientLoginPacketListener` — it extends **none** of this mod's mixin targets,
  so no listener-level hook reaches it.
- The login phase's packets are a different pair. `ServerboundCustomQueryAnswerPacket` is
  `record ServerboundCustomQueryAnswerPacket(int transactionId, @Nullable
  CustomQueryAnswerPayload payload) implements Packet<ServerLoginPacketListener>` — **not**
  a `ServerboundCustomPayloadPacket`, so `ExposureGuard.shouldDrop`'s `instanceof` skips it
  even though the `Connection#sendPacket` hook does see it go past. Inbound,
  `ClientboundCustomQueryPacket` is likewise not a `ClientboundCustomPayloadPacket`, so the
  `channelRead0` hook skips it too.

Why that is a disclosure and not just a gap. Vanilla always answers a login query with
`null`:

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

Deliberately not fixed here. Withholding a login answer means choosing what to put in its
place, and this mod does not fabricate; picking between "stay silent and stall the login",
"send the vanilla `null` a mod would not have sent", and "answer" is a design decision, not
a bug fix. Written down rather than papered over. Also stated in the README under "What is
not covered".

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

**Everything client-side really does reach the funnel.** Read, not assumed:

- `ClientCommonPacketListenerImpl#send(Packet<?>)` —
  `(Lnet/minecraft/network/protocol/Packet;)V` — is `{ this.connection.send(packet); }`.
- `ClientCommonPacketListenerImpl#sendDeferredPackets()` drains `deferredPackets` through
  that same `send(Packet)`.
- Fabric API 0.141.6's `ClientPlayNetworking.send(CustomPacketPayload)` (read from the
  Mojang-remapped Fabric sources jar) ends in
  `Minecraft.getInstance().getConnection().send(createC2SPacket(payload));` — so
  third-party mod traffic funnels through `Connection#sendPacket` too.

### Inbound: `net.minecraft.network.Connection#channelRead0` — the real choke point

**This is where the inbound filter lives**, and the reason is a race, not taste. Verified
2026-08-29 by the same two readings as the rest of this section.

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
grep of the decompiled sources finds exactly two callers of `subPackets()` —
`handleBundlePacket` above, whose body is a bare for-each, and `BundlerInfo.unbundlePacket`,
which is the *server's* outbound path and is unreachable here. An empty bundle therefore
runs the loop zero times and is completely inert. Nothing asserts a non-empty bundle.
Emitting it keeps the decision in one place and leaves `receivedPackets` honest.

**Fabric API 0.141.6 does touch bundles, and it helps rather than conflicts.**
`net.fabricmc.fabric.mixin.networking.BundlePacketMixin` is a `@ModifyVariable` on
`BundlePacket.<init>`'s `Iterable` argument that flattens nested bundles into a fresh
`ArrayList`. Consequences, both benign: `subPackets()` is always a re-iterable `ArrayList`,
and the `ClientboundBundlePacket` this mod constructs goes through that same flattening
(a no-op copy of an already-flat, already-filtered list). Fabric's `ConnectionMixin` still
touches no inbound handler — re-checked here: `<init>`, `sendPacket`, `validateListener`,
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
