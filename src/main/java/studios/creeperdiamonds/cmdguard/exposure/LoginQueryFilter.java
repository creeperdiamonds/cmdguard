package studios.creeperdiamonds.cmdguard.exposure;

/**
 * The whole login-phase decision, as one pure function over a channel id and a policy.
 *
 * <p>Deliberately free of Minecraft types so it can be unit-tested on a machine with no game
 * client -- which is every machine this project has ever been built on. The Minecraft-facing
 * half (reading the channel id off the packet, building the substitute packet, logging) lives
 * in {@code ExposureGuard.forceVanillaLoginAnswer}; everything that decides lives here.
 *
 * <p><b>What "withhold" means in the login phase is not what it means elsewhere.</b> In the
 * configuration and play phases a withheld payload is simply dropped. Here it is not: the
 * inbound {@code ClientboundCustomQueryPacket} is passed through with its payload replaced by
 * a {@code DiscardedQueryPayload}, so vanilla's own unconditional
 * {@code new ServerboundCustomQueryAnswerPacket(transactionId, null)} is what goes on the
 * wire, and Fabric API's {@code ClientHandshakePacketListenerImplMixin} -- which only
 * intercepts a {@code PacketByteBufLoginQueryRequestPayload} -- never reaches a mod's
 * handler. Dropping the query instead was investigated and refuted: nothing in vanilla does
 * per-transaction accounting, so silence is not accounted for, it simply stalls until
 * {@code Connection}'s {@code ReadTimeoutHandler(30)} fires and the player gets
 * {@code disconnect.timeout}. A hang is a behaviour no vanilla client exhibits, so it
 * discloses strictly more than the answer it was meant to avoid. See
 * {@code .superpowers/sdd/login-phase-spike.md}.
 */
public final class LoginQueryFilter {

    private LoginQueryFilter() {
    }

    /**
     * True when a login query on {@code channelId} must be answered by vanilla rather than by
     * whatever mod registered a handler for it.
     *
     * <p>Fails closed on everything it cannot place: a null or unparseable channel id is
     * withheld (via {@link ExposurePolicy#isExposed}, which returns false for both), a null
     * policy is withheld, and any {@code RuntimeException} out of the policy is withheld. The
     * two switches are checked first and are the only way to get a {@code false} out of this
     * method without a policy decision, so turning the feature off is never confused with
     * failing to decide.
     *
     * @param active      whether the exposure layer is filtering at all on this connection
     *                    ({@code ExposureGuard.Snapshot#active}).
     * @param filterLogin whether login-phase filtering specifically is on
     *                    ({@code ExposureSettings#filterLogin}).
     * @param policy      the connection's frozen policy. During the login phase this is
     *                    always the globals-only policy -- see
     *                    {@code ExposureGuard#forceVanillaLoginAnswer} for why per-server
     *                    grants cannot apply here.
     */
    public static boolean withholds(String channelId,
                                    boolean active,
                                    boolean filterLogin,
                                    ExposurePolicy policy) {
        if (!active || !filterLogin) {
            return false;
        }
        if (policy == null) {
            return true;
        }
        try {
            return !policy.isExposed(channelId);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * The namespace half of {@code channelId}, for the remedy command in the warning log, or
     * the whole id when it has no usable namespace.
     *
     * <p>The remedy is worth getting exactly right: a user handed the wrong command concludes
     * the mod is broken rather than that they typed the wrong thing, and a broken login is the
     * failure this feature can cause that a user is least equipped to diagnose.
     */
    public static String remedyNamespace(String channelId) {
        if (channelId == null) {
            return "<namespace>";
        }
        String namespace = ExposurePolicy.namespaceOf(channelId);
        return namespace == null ? channelId : namespace;
    }
}
