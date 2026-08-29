package studios.creeperdiamonds.cmdguard;

import java.util.Set;
import java.util.function.Predicate;

/**
 * The whole typed-command decision, as one pure function over a string and the command
 * policy. The counterpart to {@link SuggestionFilter}, and the same split as {@code
 * LoginQueryFilter} / {@code ExposureGuard}.
 *
 * <p><b>Why this was extracted.</b> The rule used to live inline in
 * {@code OutboundGuard#shouldBlock}, which reads {@code GuardConfig.get()} (a call that
 * throws outside a launched game) and Fabric's {@code ClientCommandManager}, so nothing could
 * exercise it on this machine. That left {@code SuggestionFilterTest}'s agreement test with
 * no real thing to compare against: it computed its expectation as
 * {@code !ALLOWLIST.contains(root)} -- a hand-copy of the rule under test, which agrees with
 * whatever either side does and so could not detect the divergence it was named for. With the
 * rule here, that test compares one guard against the other guard's actual code.
 *
 * <p>Behaviour is unchanged from the inline version, deliberately, down to the order of the
 * checks. In particular there is <b>no</b> null-allowlist guard and no {@code catch} here:
 * {@link SuggestionFilter} has both because a completion request is traffic that must be
 * judged, whereas a typed command that cannot be judged does not silently go out -- the
 * exception propagates out of {@code ClientPacketListenerMixin} rather than being swallowed
 * into an allow. Adding fail-closed handling to this path would be an improvement, but it is
 * a change to the command guard's behaviour and not one to smuggle in under a refactor.
 */
public final class CommandFilter {

    private CommandFilter() {
    }

    /**
     * True when a typed or clicked command must not leave the client.
     *
     * @param command              the command text as {@code ClientPacketListener#sendCommand}
     *                             receives it -- no leading {@code '/'} in vanilla, though
     *                             {@link CommandRoot} tolerates one either way.
     * @param clicked              true for {@code sendUnattendedCommand}, i.e. a command fired
     *                             by clicking something the server rendered.
     * @param guardEnabled         the guard's master switch ({@code GuardConfig#enabled}).
     * @param allowClickedCommands {@code GuardConfig#allowClickedCommands}.
     * @param allowlist            the command allowlist, already lowercased by
     *                             {@code GuardConfig#allow}.
     * @param clientOwnsRoot       whether Fabric's client dispatcher owns a given root. A
     *                             {@link Predicate} rather than a {@code boolean} so that it
     *                             stays lazy: {@code OutboundGuard#isClientCommand} touches
     *                             {@code ClientCommandManager}, and evaluating it eagerly
     *                             would reach for the dispatcher even when the guard is off
     *                             or the command has no root.
     */
    public static boolean blocks(String command,
                                 boolean clicked,
                                 boolean guardEnabled,
                                 boolean allowClickedCommands,
                                 Set<String> allowlist,
                                 Predicate<String> clientOwnsRoot) {
        if (!guardEnabled) {
            return false;
        }

        String root = CommandRoot.of(command);
        // An empty command runs nothing and sends nothing. SuggestionFilter deliberately
        // diverges here, because a completion request goes on the wire whether or not it
        // parses; see its class Javadoc.
        if (root.isEmpty()) {
            return false;
        }

        // Owned by a client mod -- it never reaches the network. Never our business.
        if (clientOwnsRoot.test(root)) {
            return false;
        }

        if (clicked && allowClickedCommands) {
            return false;
        }

        return !allowlist.contains(root);
    }
}
