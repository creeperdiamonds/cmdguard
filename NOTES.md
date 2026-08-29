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

### Inbound: the client custom-payload handler

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
