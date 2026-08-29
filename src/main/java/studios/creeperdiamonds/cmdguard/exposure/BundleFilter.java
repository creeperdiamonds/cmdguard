package studios.creeperdiamonds.cmdguard.exposure;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The order-preserving, allocate-only-when-needed removal {@link ExposureGuard#filterBundle}
 * builds on. Minecraft-free and generic on purpose: {@code ClientboundBundlePacket}'s
 * sub-packet list is the one real caller, but the removal logic itself -- walk, drop some,
 * preserve order, never allocate a new list unless something is actually removed -- has
 * nothing Minecraft-shaped about it, so it is unit-tested here with plain strings instead of
 * through a game object neither a test nor a build can construct.
 */
public final class BundleFilter {
    private BundleFilter() {
    }

    /**
     * Returns a list holding every element of {@code items} for which {@code drop} returned
     * {@code false}, in the same order, or {@code null} when {@code drop} returned
     * {@code false} for every element -- the caller's cue to keep using its own original
     * collection rather than a copy.
     *
     * <p>{@code drop} is evaluated exactly once per element, in iteration order, never more.
     * {@link ExposureGuard}'s use of this passes a {@code drop} that records a ledger entry
     * and, on a channel's first withhold, a log line -- side effects that a second
     * evaluation of the same element would double-count or double-log.
     *
     * <p>No {@code ArrayList} is allocated until the first element {@code drop} returns
     * {@code true} for. The overwhelmingly common case is nothing dropped at all -- vanilla
     * emits a packet bundle per entity spawn -- so allocating and copying on every bundle
     * regardless would otherwise be steady garbage on the netty event loop for no reason.
     * The elements seen before that first drop are copied from {@code items} itself rather
     * than by re-evaluating {@code drop} on them (see above), which is why {@code items}
     * must be safely re-iterable up to the point of the first drop -- true of any
     * {@code List}, which is what every real caller passes.
     */
    public static <T> List<T> retainAllowed(Iterable<T> items, Predicate<T> drop) {
        List<T> kept = null;
        int seenBeforeFirstDrop = 0;
        for (T item : items) {
            if (kept == null) {
                if (drop.test(item)) {
                    kept = new ArrayList<>();
                    copyFirst(items, seenBeforeFirstDrop, kept);
                } else {
                    seenBeforeFirstDrop++;
                }
            } else if (!drop.test(item)) {
                kept.add(item);
            }
        }
        return kept;
    }

    /** Copies the first {@code count} elements of {@code items} into {@code dest}, in order. */
    private static <T> void copyFirst(Iterable<T> items, int count, List<T> dest) {
        int i = 0;
        for (T item : items) {
            if (i == count) {
                return;
            }
            dest.add(item);
            i++;
        }
    }
}
