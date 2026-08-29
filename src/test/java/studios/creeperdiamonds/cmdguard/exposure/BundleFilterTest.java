package studios.creeperdiamonds.cmdguard.exposure;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

class BundleFilterTest {

    private static <T> Predicate<T> dropping(Set<T> toDrop) {
        return toDrop::contains;
    }

    @Test
    void nothingDroppedReturnsNull() {
        List<String> items = List.of("a", "b", "c");
        assertNull(BundleFilter.retainAllowed(items, dropping(Set.of())));
    }

    @Test
    void neverAddsAnEntryAbsentFromTheInput() {
        List<String> items = List.of("a", "b");
        List<String> kept = BundleFilter.retainAllowed(items, dropping(Set.of("b")));
        assertEquals(List.of("a"), kept);
    }

    @Test
    void preservesOrder() {
        List<String> items = List.of("a", "b", "c", "d", "e");
        List<String> kept = BundleFilter.retainAllowed(items, dropping(Set.of("b", "d")));
        assertEquals(List.of("a", "c", "e"), kept);
    }

    @Test
    void dropsTheFirstElement() {
        List<String> items = List.of("a", "b", "c");
        List<String> kept = BundleFilter.retainAllowed(items, dropping(Set.of("a")));
        assertEquals(List.of("b", "c"), kept);
    }

    @Test
    void dropsTheLastElement() {
        List<String> items = List.of("a", "b", "c");
        List<String> kept = BundleFilter.retainAllowed(items, dropping(Set.of("c")));
        assertEquals(List.of("a", "b"), kept);
    }

    @Test
    void dropsEverything() {
        List<String> items = List.of("a", "b", "c");
        List<String> kept = BundleFilter.retainAllowed(items, dropping(Set.of("a", "b", "c")));
        assertEquals(List.of(), kept);
    }

    @Test
    void evaluatesDropExactlyOncePerElement() {
        List<String> items = List.of("a", "b", "c", "d");
        int[] evaluations = new int[1];
        Predicate<String> countingDrop = item -> {
            evaluations[0]++;
            return item.equals("b") || item.equals("d");
        };
        List<String> kept = BundleFilter.retainAllowed(items, countingDrop);
        assertEquals(List.of("a", "c"), kept);
        assertEquals(4, evaluations[0], "drop must be evaluated exactly once per element");
    }

    @Test
    void isIndependentOfTheOriginalListAfterACopyIsMade() {
        List<String> items = List.of("a", "b", "c");
        List<String> kept = BundleFilter.retainAllowed(items, dropping(Set.of("b")));
        // Guard against accidentally returning a view backed by the input.
        assertNotSame(items, kept);
    }
}
