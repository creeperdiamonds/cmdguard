package studios.creeperdiamonds.cmdguard.exposure;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentifierFilterTest {

    private static ExposurePolicy defaults() {
        return new ExposurePolicy(ExposurePolicy.DEFAULT_NAMESPACES, Set.of(), Set.of());
    }

    @Test
    void removesWithheldEntriesAndKeepsOrder() {
        List<String> input = List.of(
                "minecraft:register", "somemod:handshake", "fabric:registry/sync", "other:probe");
        assertEquals(List.of("minecraft:register", "fabric:registry/sync"),
                IdentifierFilter.retain(input, defaults()));
    }

    @Test
    void neverEmitsAnEntryAbsentFromInput() {
        List<String> input = List.of("somemod:a", "somemod:b");
        List<String> output = IdentifierFilter.retain(input, defaults());
        assertTrue(input.containsAll(output), "filter fabricated an entry");
    }

    @Test
    void withholdingEverythingYieldsEmpty() {
        assertEquals(List.of(), IdentifierFilter.retain(List.of("somemod:a"), defaults()));
    }

    @Test
    void emptyInputYieldsEmpty() {
        assertEquals(List.of(), IdentifierFilter.retain(List.of(), defaults()));
    }

    @Test
    void strippedChannelsAreAlsoWithheldInbound() {
        ExposurePolicy policy = defaults();
        List<String> advertised = List.of(
                "minecraft:register", "fabric:registry/sync", "somemod:handshake", "other:probe");
        List<String> kept = IdentifierFilter.retain(advertised, policy);

        for (String channel : advertised) {
            // One predicate gates the advertisement, the inbound delivery and the outbound
            // reply, so an advertisement can never promise a channel the guard then refuses.
            assertEquals(policy.isExposed(channel), kept.contains(channel),
                    "advertisement disagrees with enforcement for " + channel);
        }
    }

    @Test
    void filtersSetsAndPreservesIterationOrder() {
        Set<String> input = new LinkedHashSet<>(
                List.of("fabric:a", "somemod:b", "minecraft:c"));

        // Compared as a List on purpose: Set equality ignores order, so asserting against
        // another Set would pass even if the filter shuffled its output.
        assertEquals(List.of("fabric:a", "minecraft:c"),
                List.copyOf(IdentifierFilter.retain(input, defaults())));
    }
}
