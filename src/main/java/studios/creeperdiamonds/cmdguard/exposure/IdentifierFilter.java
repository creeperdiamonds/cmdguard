package studios.creeperdiamonds.cmdguard.exposure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Removes entries a policy withholds. It can only ever shorten its input.
 *
 * <p>This is the whole no-fabrication guarantee in one place: every rewrite the mod
 * performs routes through here, so "we never add an entry the client did not have" is a
 * property of one tested function rather than of scattered intent.
 */
public final class IdentifierFilter {
    private IdentifierFilter() {
    }

    public static List<String> retain(List<String> input, ExposurePolicy policy) {
        List<String> kept = new ArrayList<>(input.size());
        collect(input, policy, kept::add);
        return Collections.unmodifiableList(kept);
    }

    public static Set<String> retain(Set<String> input, ExposurePolicy policy) {
        Set<String> kept = new LinkedHashSet<>();
        collect(input, policy, kept::add);
        return Collections.unmodifiableSet(kept);
    }

    private static void collect(Collection<String> input,
                                ExposurePolicy policy,
                                java.util.function.Consumer<String> sink) {
        for (String id : input) {
            if (policy.isExposed(id)) {
                sink.accept(id);
            }
        }
    }
}
