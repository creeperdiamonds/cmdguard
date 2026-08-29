# Exposure Whitelist Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Withhold client metadata that is not on the user's exposure whitelist, without ever fabricating a channel or identifier the client does not have.

**Architecture:** A pure decision layer (`ExposurePolicy`, `IdentifierFilter`, `ExposureSettings`, `ChannelLedger`) with no Minecraft imports, unit-tested on a machine with no game client. A thin Minecraft-facing layer (`PayloadRewriter`, `ExposureGuard`, `ConnectionMixin`) applies it at `Connection#send` on serverbound traffic, rewriting the five Fabric payload records that carry identifier collections and dropping everything else the policy withholds.

**Tech Stack:** Java 21, Fabric Loom 1.17.20, Gradle 9.5.0, Mixin, Gson (already a transitive dependency via Minecraft), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-08-29-exposure-whitelist-design.md`

## Global Constraints

- Minecraft 1.21.11, Fabric Loader 0.19.3, Fabric API 0.141.6+1.21.11, Mod Menu 17.0.0, Loom 1.17.20, Java 21. All pinned in `gradle.properties`; do not change them.
- Mojang official mappings. In 1.21.11 `ResourceLocation` is named `net.minecraft.resources.Identifier`.
- Package root: `studios.creeperdiamonds.cmdguard`. Author string: `Creeperdiamonds Studios`.
- Commits use the repo's configured identity (`creeperdiamonds`, noreply address). Do not set a different author.
- Branch: `exposure-whitelist`. Do not commit to `master`.
- **Never fabricate.** No filter may emit a channel or identifier that was not in its input. `minecraft:brand` is passed through untouched and always truthful.
- Default exposed namespaces: `fabric`, `minecraft`, `c`. Everything else withheld in both directions.
- Never withheld regardless of config: `minecraft:brand`, `c:version`, `fabric:registry/sync/complete`.
- Fail closed: any exception inside the exposure layer withholds. Mixins stay `"required": true` with `defaultRequire: 1`.
- There is no Minecraft client on this machine. Only pure-Java tests can be run here; everything Minecraft-facing is verified by compilation plus the manual checklist in the spec.

---

### Task 1: Test harness and `ExposurePolicy`

**Files:**
- Modify: `gradle.properties`
- Modify: `build.gradle`
- Create: `src/main/java/studios/creeperdiamonds/cmdguard/exposure/ExposurePolicy.java`
- Test: `src/test/java/studios/creeperdiamonds/cmdguard/exposure/ExposurePolicyTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `ExposurePolicy(Set<String> exposedNamespaces, Set<String> exposedChannels, Set<String> withheldChannels)`; `boolean isExposed(String channelId)`; `static Set<String> DEFAULT_NAMESPACES`; `static Set<String> NEVER_WITHHELD`.

- [ ] **Step 1: Add the JUnit version property**

Append to `gradle.properties`:

```properties
# Confirm against Maven Central before bumping, as with the versions above.
junit_version=5.11.4
```

- [ ] **Step 2: Add the test dependencies and platform**

In `build.gradle`, inside the existing `dependencies { }` block, append:

```groovy
    testImplementation platform("org.junit:junit-bom:${project.junit_version}")
    testImplementation "org.junit.jupiter:junit-jupiter"
    testRuntimeOnly "org.junit.platform:junit-platform-launcher"
```

And after the `java { }` block, append:

```groovy
tasks.named('test') {
    useJUnitPlatform()
    testLogging {
        events 'passed', 'skipped', 'failed'
    }
}
```

- [ ] **Step 3: Write the failing test**

Create `src/test/java/studios/creeperdiamonds/cmdguard/exposure/ExposurePolicyTest.java`:

```java
package studios.creeperdiamonds.cmdguard.exposure;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExposurePolicyTest {

    private static ExposurePolicy defaults() {
        return new ExposurePolicy(ExposurePolicy.DEFAULT_NAMESPACES, Set.of(), Set.of());
    }

    @Test
    void exposesDefaultNamespaces() {
        ExposurePolicy policy = defaults();
        assertTrue(policy.isExposed("fabric:registry/sync"));
        assertTrue(policy.isExposed("minecraft:register"));
        assertTrue(policy.isExposed("c:register"));
    }

    @Test
    void withholdsThirdPartyNamespaces() {
        assertFalse(defaults().isExposed("somemod:handshake"));
    }

    @Test
    void withholdsAmbiguousIdentifiers() {
        ExposurePolicy policy = defaults();
        assertFalse(policy.isExposed("nocolon"));
        assertFalse(policy.isExposed(":leadingcolon"));
        assertFalse(policy.isExposed("trailingcolon:"));
        assertFalse(policy.isExposed(null));
    }

    @Test
    void brandIsExposedEvenWhenExplicitlyWithheld() {
        ExposurePolicy policy = new ExposurePolicy(Set.of(), Set.of(), Set.of("minecraft:brand"));
        assertTrue(policy.isExposed("minecraft:brand"));
        assertTrue(policy.isExposed("c:version"));
        assertTrue(policy.isExposed("fabric:registry/sync/complete"));
    }

    @Test
    void channelWithholdBeatsNamespaceGrant() {
        ExposurePolicy policy = new ExposurePolicy(
                Set.of("fabric"), Set.of(), Set.of("fabric:accepted_attachments_v1"));
        assertFalse(policy.isExposed("fabric:accepted_attachments_v1"));
        assertTrue(policy.isExposed("fabric:registry/sync"));
    }

    @Test
    void channelGrantExposesWithoutExposingNamespace() {
        ExposurePolicy policy = new ExposurePolicy(Set.of(), Set.of("somemod:handshake"), Set.of());
        assertTrue(policy.isExposed("somemod:handshake"));
        assertFalse(policy.isExposed("somemod:other"));
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertTrue(defaults().isExposed("MINECRAFT:Register"));
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew test --tests "studios.creeperdiamonds.cmdguard.exposure.ExposurePolicyTest"`
Expected: FAIL — compilation error, `ExposurePolicy` does not exist.

- [ ] **Step 5: Write the implementation**

Create `src/main/java/studios/creeperdiamonds/cmdguard/exposure/ExposurePolicy.java`:

```java
package studios.creeperdiamonds.cmdguard.exposure;

import java.util.Locale;
import java.util.Set;

/**
 * Decides whether one channel may be disclosed to the connected server.
 *
 * <p>Immutable and free of Minecraft types, so it is testable without a game client and
 * safe to read from the netty event loop. Withholding is the default: anything this class
 * cannot confidently place is withheld, never exposed.
 */
public final class ExposurePolicy {

    /**
     * Exposed regardless of configuration.
     *
     * <p>{@code minecraft:brand} is the client's truthful identification and is never
     * touched. {@code c:version} is protocol negotiation carrying no mod data.
     * {@code fabric:registry/sync/complete} is a zero-byte acknowledgement -- verified
     * against Fabric API 0.141.6, its codec is {@code StreamCodec.unit} -- and stalling it
     * costs the player the join for no privacy gain.
     */
    public static final Set<String> NEVER_WITHHELD = Set.of(
            "minecraft:brand",
            "c:version",
            "fabric:registry/sync/complete");

    /** Generic namespaces every Fabric client has, which therefore distinguish nobody. */
    public static final Set<String> DEFAULT_NAMESPACES = Set.of("fabric", "minecraft", "c");

    private final Set<String> exposedNamespaces;
    private final Set<String> exposedChannels;
    private final Set<String> withheldChannels;

    public ExposurePolicy(Set<String> exposedNamespaces,
                          Set<String> exposedChannels,
                          Set<String> withheldChannels) {
        this.exposedNamespaces = lowercased(exposedNamespaces);
        this.exposedChannels = lowercased(exposedChannels);
        this.withheldChannels = lowercased(withheldChannels);
    }

    public boolean isExposed(String channelId) {
        if (channelId == null) {
            return false;
        }
        String id = channelId.toLowerCase(Locale.ROOT);

        if (NEVER_WITHHELD.contains(id)) {
            return true;
        }
        if (withheldChannels.contains(id)) {
            return false;
        }
        if (exposedChannels.contains(id)) {
            return true;
        }

        String namespace = namespaceOf(id);
        return namespace != null && exposedNamespaces.contains(namespace);
    }

    /** Null for anything that is not exactly one non-empty namespace and one non-empty path. */
    static String namespaceOf(String channelId) {
        int colon = channelId.indexOf(':');
        if (colon <= 0 || colon == channelId.length() - 1) {
            return null;
        }
        return channelId.substring(0, colon);
    }

    private static Set<String> lowercased(Set<String> input) {
        return input.stream()
                .filter(java.util.Objects::nonNull)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew test --tests "studios.creeperdiamonds.cmdguard.exposure.ExposurePolicyTest"`
Expected: PASS, 7 tests.

- [ ] **Step 7: Commit**

```bash
git add gradle.properties build.gradle src/test src/main/java/studios/creeperdiamonds/cmdguard/exposure/ExposurePolicy.java
git commit -m "feat: exposure policy and JUnit test harness"
```

---

### Task 2: `IdentifierFilter`

**Files:**
- Create: `src/main/java/studios/creeperdiamonds/cmdguard/exposure/IdentifierFilter.java`
- Test: `src/test/java/studios/creeperdiamonds/cmdguard/exposure/IdentifierFilterTest.java`

**Interfaces:**
- Consumes: `ExposurePolicy#isExposed`.
- Produces: `static List<String> retain(List<String> input, ExposurePolicy policy)`; `static Set<String> retain(Set<String> input, ExposurePolicy policy)`.

This is the single place the no-fabrication guarantee lives. Every rewrite in Task 6 routes through it.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/studios/creeperdiamonds/cmdguard/exposure/IdentifierFilterTest.java`:

```java
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
        assertEquals(new LinkedHashSet<>(List.of("fabric:a", "minecraft:c")),
                IdentifierFilter.retain(input, defaults()));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "studios.creeperdiamonds.cmdguard.exposure.IdentifierFilterTest"`
Expected: FAIL — compilation error, `IdentifierFilter` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/studios/creeperdiamonds/cmdguard/exposure/IdentifierFilter.java`:

```java
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "studios.creeperdiamonds.cmdguard.exposure.IdentifierFilterTest"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/studios/creeperdiamonds/cmdguard/exposure/IdentifierFilter.java src/test/java/studios/creeperdiamonds/cmdguard/exposure/IdentifierFilterTest.java
git commit -m "feat: identifier filter carrying the no-fabrication guarantee"
```

---

### Task 3: `ExposureSettings` — persisted shape, migration, per-server merge

**Files:**
- Create: `src/main/java/studios/creeperdiamonds/cmdguard/exposure/ExposureSettings.java`
- Test: `src/test/java/studios/creeperdiamonds/cmdguard/exposure/ExposureSettingsTest.java`

**Interfaces:**
- Consumes: `ExposurePolicy`.
- Produces: public mutable fields `enabled`, `filterInbound`, `exposedNamespaces`, `exposedChannels`, `withheldChannels`, `perServerNamespaces`; `ExposurePolicy policyFor(String serverKey)`; `void normalise()`.

`normalise()` is the config migration. Gson leaves fields absent from an existing `cmdguard.json` as null, so every field is null-guarded here. It must not overwrite a field the user deliberately emptied — only a null one.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/studios/creeperdiamonds/cmdguard/exposure/ExposureSettingsTest.java`:

```java
package studios.creeperdiamonds.cmdguard.exposure;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExposureSettingsTest {

    @Test
    void freshSettingsExposeOnlyTheDefaultNamespaces() {
        ExposurePolicy policy = new ExposureSettings().policyFor("play.example.net");
        assertTrue(policy.isExposed("fabric:registry/sync"));
        assertFalse(policy.isExposed("somemod:handshake"));
    }

    @Test
    void perServerGrantAppliesOnlyToThatServer() {
        ExposureSettings settings = new ExposureSettings();
        settings.perServerNamespaces.put("play.example.net",
                new LinkedHashSet<>(List.of("somemod")));

        assertTrue(settings.policyFor("play.example.net").isExposed("somemod:handshake"));
        assertFalse(settings.policyFor("other.example.net").isExposed("somemod:handshake"));
    }

    @Test
    void normaliseReplacesNullsLeftByAnOlderConfigFile() {
        ExposureSettings settings = new ExposureSettings();
        settings.exposedNamespaces = null;
        settings.exposedChannels = null;
        settings.withheldChannels = null;
        settings.perServerNamespaces = null;

        settings.normalise();

        assertEquals(ExposurePolicy.DEFAULT_NAMESPACES, settings.exposedNamespaces);
        assertEquals(Set.of(), settings.exposedChannels);
        assertEquals(Set.of(), settings.withheldChannels);
        assertTrue(settings.perServerNamespaces.isEmpty());
    }

    @Test
    void normaliseKeepsADeliberatelyEmptiedNamespaceSet() {
        ExposureSettings settings = new ExposureSettings();
        settings.exposedNamespaces = new LinkedHashSet<>();

        settings.normalise();

        assertTrue(settings.exposedNamespaces.isEmpty(),
                "an empty set is a strict-mode choice, not a missing field");
    }

    @Test
    void normaliseLowercasesEntries() {
        ExposureSettings settings = new ExposureSettings();
        settings.exposedNamespaces = new LinkedHashSet<>(List.of("SomeMod"));

        settings.normalise();

        assertTrue(settings.policyFor("any").isExposed("somemod:handshake"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "studios.creeperdiamonds.cmdguard.exposure.ExposureSettingsTest"`
Expected: FAIL — compilation error, `ExposureSettings` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/studios/creeperdiamonds/cmdguard/exposure/ExposureSettings.java`:

```java
package studios.creeperdiamonds.cmdguard.exposure;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The persisted half of the exposure layer: plain fields Gson can read and write, with no
 * Minecraft types, so the merge rules are testable without a game client.
 */
public final class ExposureSettings {

    public boolean enabled = true;

    /** Drop inbound payloads on withheld channels, so a mod never sees the probe. */
    public boolean filterInbound = true;

    public Set<String> exposedNamespaces = new LinkedHashSet<>(ExposurePolicy.DEFAULT_NAMESPACES);
    public Set<String> exposedChannels = new LinkedHashSet<>();
    public Set<String> withheldChannels = new LinkedHashSet<>();

    /** Extra namespaces granted for one server address only. */
    public Map<String, Set<String>> perServerNamespaces = new LinkedHashMap<>();

    /** Builds the immutable snapshot a single connection uses for its whole lifetime. */
    public ExposurePolicy policyFor(String serverKey) {
        Set<String> namespaces = new LinkedHashSet<>(exposedNamespaces);
        Set<String> extra = perServerNamespaces.get(serverKey);
        if (extra != null) {
            namespaces.addAll(extra);
        }
        return new ExposurePolicy(namespaces, exposedChannels, withheldChannels);
    }

    /**
     * Repairs a config written before this feature existed. Gson leaves absent fields
     * null; an empty set, by contrast, is a deliberate strict-mode choice and is kept.
     */
    public void normalise() {
        if (exposedNamespaces == null) {
            exposedNamespaces = new LinkedHashSet<>(ExposurePolicy.DEFAULT_NAMESPACES);
        }
        if (exposedChannels == null) {
            exposedChannels = new LinkedHashSet<>();
        }
        if (withheldChannels == null) {
            withheldChannels = new LinkedHashSet<>();
        }
        if (perServerNamespaces == null) {
            perServerNamespaces = new LinkedHashMap<>();
        }

        exposedNamespaces = lower(exposedNamespaces);
        exposedChannels = lower(exposedChannels);
        withheldChannels = lower(withheldChannels);

        Map<String, Set<String>> repaired = new LinkedHashMap<>();
        perServerNamespaces.forEach((server, namespaces) -> {
            if (server != null && namespaces != null) {
                repaired.put(server.toLowerCase(Locale.ROOT), lower(namespaces));
            }
        });
        perServerNamespaces = repaired;
    }

    private static Set<String> lower(Set<String> input) {
        Set<String> out = new LinkedHashSet<>();
        for (String value : input) {
            if (value != null) {
                out.add(value.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "studios.creeperdiamonds.cmdguard.exposure.ExposureSettingsTest"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/studios/creeperdiamonds/cmdguard/exposure/ExposureSettings.java src/test/java/studios/creeperdiamonds/cmdguard/exposure/ExposureSettingsTest.java
git commit -m "feat: persisted exposure settings with config migration"
```

---

### Task 4: `ChannelLedger`

**Files:**
- Create: `src/main/java/studios/creeperdiamonds/cmdguard/exposure/ChannelLedger.java`
- Test: `src/test/java/studios/creeperdiamonds/cmdguard/exposure/ChannelLedgerTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `record Entry(String channel, boolean exposed, int withheldCount)`; `void record(String channel, boolean exposed)`; `List<Entry> snapshot()`; `int totalWithheld()`; `void reset()`.

Written from the netty event loop and read from the client thread, so every method is synchronized. It survives a disconnect on purpose: after a kick the player needs to see what was withheld, and there is no chat left to write to.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/studios/creeperdiamonds/cmdguard/exposure/ChannelLedgerTest.java`:

```java
package studios.creeperdiamonds.cmdguard.exposure;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelLedgerTest {

    @Test
    void recordsEachChannelOnceAndCountsWithholds() {
        ChannelLedger ledger = new ChannelLedger();
        ledger.record("somemod:handshake", false);
        ledger.record("somemod:handshake", false);
        ledger.record("fabric:registry/sync", true);

        List<ChannelLedger.Entry> entries = ledger.snapshot();
        assertEquals(2, entries.size());
        assertEquals(2, ledger.totalWithheld());
    }

    @Test
    void snapshotIsSortedByChannelForStableOutput() {
        ChannelLedger ledger = new ChannelLedger();
        ledger.record("zmod:z", false);
        ledger.record("amod:a", false);

        assertEquals(List.of("amod:a", "zmod:z"),
                ledger.snapshot().stream().map(ChannelLedger.Entry::channel).toList());
    }

    @Test
    void lastDecisionWins() {
        ChannelLedger ledger = new ChannelLedger();
        ledger.record("somemod:handshake", false);
        ledger.record("somemod:handshake", true);

        assertTrue(ledger.snapshot().get(0).exposed());
        assertEquals(1, ledger.snapshot().get(0).withheldCount());
    }

    @Test
    void resetClearsEverything() {
        ChannelLedger ledger = new ChannelLedger();
        ledger.record("somemod:handshake", false);
        ledger.reset();

        assertTrue(ledger.snapshot().isEmpty());
        assertEquals(0, ledger.totalWithheld());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "studios.creeperdiamonds.cmdguard.exposure.ChannelLedgerTest"`
Expected: FAIL — compilation error, `ChannelLedger` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/studios/creeperdiamonds/cmdguard/exposure/ChannelLedger.java`:

```java
package studios.creeperdiamonds.cmdguard.exposure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What was seen and what was decided about it. Informational only -- the filter never
 * consults this, so an incomplete ledger cannot widen what is disclosed.
 *
 * <p>Written from the netty event loop, read from the client thread, hence synchronized.
 * It deliberately outlives a disconnect: a server that requires a mod you withheld will
 * kick you, and there is no chat left to explain why.
 */
public final class ChannelLedger {

    public record Entry(String channel, boolean exposed, int withheldCount) {
    }

    private final Map<String, Entry> entries = new TreeMap<>();

    public synchronized void record(String channel, boolean exposed) {
        Entry previous = entries.get(channel);
        int withheld = previous == null ? 0 : previous.withheldCount();
        if (!exposed) {
            withheld++;
        }
        entries.put(channel, new Entry(channel, exposed, withheld));
    }

    public synchronized List<Entry> snapshot() {
        return List.copyOf(new ArrayList<>(entries.values()));
    }

    public synchronized int totalWithheld() {
        int total = 0;
        for (Entry entry : entries.values()) {
            total += entry.withheldCount();
        }
        return total;
    }

    public synchronized void reset() {
        entries.clear();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "studios.creeperdiamonds.cmdguard.exposure.ChannelLedgerTest"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/studios/creeperdiamonds/cmdguard/exposure/ChannelLedger.java src/test/java/studios/creeperdiamonds/cmdguard/exposure/ChannelLedgerTest.java
git commit -m "feat: channel ledger surviving disconnect"
```

---

### Task 5: Verify mapped signatures against generated sources

**Files:**
- Modify: `NOTES.md`

Nothing Minecraft-facing has been written yet, and every remaining task depends on names nobody on this machine has confirmed. There is no Loom cache here, so this task generates one and writes down what it finds. Budget time: `genSources` decompiles Minecraft and is slow on a 2-core machine.

**Interfaces:**
- Consumes: nothing.
- Produces: confirmed signatures recorded in `NOTES.md` for Tasks 6-8.

- [ ] **Step 1: Generate the decompiled sources**

Run: `./gradlew genSources`
Expected: completes and produces a sources jar under `~/.gradle/caches/fabric-loom`.

- [ ] **Step 2: Confirm the outbound funnel in `net.minecraft.network.Connection`**

Open the decompiled `Connection` and answer three questions in writing:

1. What is the exact descriptor of the public send method or methods? Record every overload.
2. Do all overloads delegate to one private funnel? If so, record its name and descriptor — that is the better mixin target, because an overload that skips the funnel would skip the filter.
3. What is the accessor that reports the direction of this connection (the `PacketFlow` this connection is sending)? Record its exact name.

- [ ] **Step 3: Confirm the inbound handler**

Find the method on the client packet listener that receives `ClientboundCustomPayloadPacket`. Record its exact name and descriptor, and which class declares it (`ClientCommonPacketListenerImpl` or `ClientPacketListener`).

- [ ] **Step 4: Confirm the payload accessors**

Confirm that `ServerboundCustomPayloadPacket` exposes its payload, and record the accessor name. Confirm `CustomPacketPayload#type()` and `CustomPacketPayload.Type#id()` return an `Identifier`.

- [ ] **Step 5: Record the findings**

Append a `## Verified mappings, 1.21.11` section to `NOTES.md` listing every signature found in Steps 2-4, in the same style as the existing verified-versions note in `gradle.properties`. Tasks 6-8 use these names verbatim; if a name here is wrong the build fails, which is the intended safety net.

- [ ] **Step 6: Commit**

```bash
git add NOTES.md
git commit -m "docs: record verified 1.21.11 mappings for the exposure layer"
```

---

### Task 6: `PayloadRewriter`

**Files:**
- Create: `src/main/java/studios/creeperdiamonds/cmdguard/exposure/PayloadRewriter.java`

**Interfaces:**
- Consumes: `ExposurePolicy`, `IdentifierFilter`.
- Produces: `static CustomPacketPayload rewrite(CustomPacketPayload payload, ExposurePolicy policy)` — returns a filtered instance of the same record type, or the argument unchanged when it matches none.

This is the only file that imports Fabric `impl` types. Keeping the unstable surface in one file is deliberate: when Fabric API is bumped, exactly one file fails to compile.

There is no unit test for this task. It touches Minecraft and Fabric types that cannot be constructed on a machine without the game; it is verified by compilation and by the manual checklist in Task 10.

- [ ] **Step 1: Write the implementation**

Create `src/main/java/studios/creeperdiamonds/cmdguard/exposure/PayloadRewriter.java`:

```java
package studios.creeperdiamonds.cmdguard.exposure;

import net.fabricmc.fabric.impl.attachment.sync.c2s.AcceptedAttachmentsPayloadC2S;
import net.fabricmc.fabric.impl.networking.CommonRegisterPayload;
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
import net.fabricmc.fabric.impl.recipe.ingredient.CustomIngredientPayloadC2S;
import net.fabricmc.fabric.impl.recipe.sync.SupportedRecipeSerializersPayloadC2S;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Rebuilds a payload with the identifiers the policy withholds removed.
 *
 * <p>A byte-level rewrap is not possible here. Encoding resolves a codec by channel id and
 * casts the payload to that codec's type, so a foreign payload class sent under an
 * existing id fails on the cast. The only way to rewrite is to construct another instance
 * of the same record -- which means importing Fabric's unstable impl types.
 *
 * <p>That dependency is confined to this file on purpose. The Fabric API version is pinned
 * in gradle.properties, so a bump that reshapes these records breaks the build here rather
 * than shipping a jar that quietly stops filtering.
 *
 * <p>Non-identifier fields are copied through untouched. A payload matching none of these
 * types is returned unchanged: unfamiliar payloads are passed or dropped whole, never
 * partially rewritten.
 */
public final class PayloadRewriter {
    private PayloadRewriter() {
    }

    public static CustomPacketPayload rewrite(CustomPacketPayload payload, ExposurePolicy policy) {
        if (payload instanceof RegistrationPayload registration) {
            return new RegistrationPayload(registration.id(), keep(registration.channels(), policy));
        }
        if (payload instanceof CommonRegisterPayload common) {
            return new CommonRegisterPayload(
                    common.version(), common.phase(), keep(common.channels(), policy));
        }
        if (payload instanceof AcceptedAttachmentsPayloadC2S attachments) {
            return new AcceptedAttachmentsPayloadC2S(keep(attachments.acceptedAttachments(), policy));
        }
        if (payload instanceof SupportedRecipeSerializersPayloadC2S serializers) {
            return new SupportedRecipeSerializersPayloadC2S(
                    keep(serializers.synchronizedSerializers(), policy));
        }
        if (payload instanceof CustomIngredientPayloadC2S ingredients) {
            return new CustomIngredientPayloadC2S(
                    ingredients.protocolVersion(), keep(ingredients.registeredSerializers(), policy));
        }
        return payload;
    }

    /** True when this payload carries identifiers the policy may need to strip. */
    public static boolean isRewritable(CustomPacketPayload payload) {
        return payload instanceof RegistrationPayload
                || payload instanceof CommonRegisterPayload
                || payload instanceof AcceptedAttachmentsPayloadC2S
                || payload instanceof SupportedRecipeSerializersPayloadC2S
                || payload instanceof CustomIngredientPayloadC2S;
    }

    private static List<Identifier> keep(List<Identifier> input, ExposurePolicy policy) {
        List<String> kept = IdentifierFilter.retain(input.stream().map(Identifier::toString).toList(), policy);
        return kept.stream().map(Identifier::parse).toList();
    }

    private static Set<Identifier> keep(Set<Identifier> input, ExposurePolicy policy) {
        Set<String> asStrings = new LinkedHashSet<>();
        for (Identifier id : input) {
            asStrings.add(id.toString());
        }
        Set<Identifier> kept = new LinkedHashSet<>();
        for (String id : IdentifierFilter.retain(asStrings, policy)) {
            kept.add(Identifier.parse(id));
        }
        return kept;
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. A failure here means a record shape in Fabric API 0.141.6 differs from the spec's table — fix the call, and correct the spec's table in the same commit rather than leaving the two disagreeing.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/studios/creeperdiamonds/cmdguard/exposure/PayloadRewriter.java
git commit -m "feat: rewrite Fabric identifier payloads through the policy"
```

---

### Task 7: `ExposureGuard` and the outbound mixin

**Files:**
- Create: `src/main/java/studios/creeperdiamonds/cmdguard/exposure/ExposureGuard.java`
- Create: `src/main/java/studios/creeperdiamonds/cmdguard/mixin/ConnectionMixin.java`
- Modify: `src/main/resources/cmdguard.mixins.json`
- Modify: `src/main/java/studios/creeperdiamonds/cmdguard/GuardConfig.java`

**Interfaces:**
- Consumes: `ExposurePolicy`, `ExposureSettings`, `ChannelLedger`, `PayloadRewriter`, and the signatures recorded in Task 5.
- Produces: `ExposureGuard.shouldDrop(Packet<?>)`, `ExposureGuard.rewriteOrSame(Packet<?>)`, `ExposureGuard.allowInbound(Identifier)`, `ExposureGuard.ledger()`, `ExposureGuard.resetForNewConnection()`; `GuardConfig.exposure` field.

- [ ] **Step 1: Add the settings field to `GuardConfig`**

In `GuardConfig.java`, add the import `studios.creeperdiamonds.cmdguard.exposure.ExposureSettings;` and the field, next to the existing `allowlist`:

```java
    public ExposureSettings exposure = new ExposureSettings();
```

In `load()`, inside the `if (loaded != null)` block, before `return loaded;`, add the migration:

```java
                    if (loaded.exposure == null) {
                        loaded.exposure = new ExposureSettings();
                    }
                    loaded.exposure.normalise();
```

- [ ] **Step 2: Write `ExposureGuard`**

Create `src/main/java/studios/creeperdiamonds/cmdguard/exposure/ExposureGuard.java`:

```java
package studios.creeperdiamonds.cmdguard.exposure;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import studios.creeperdiamonds.cmdguard.CmdGuardClient;
import studios.creeperdiamonds.cmdguard.GuardConfig;

import java.util.Locale;

/**
 * The facade the mixins call. Everything here fails closed: if a decision cannot be made,
 * the payload is withheld and the reason is logged, because a privacy layer that fails
 * open without saying so is worse than none.
 */
public final class ExposureGuard {

    private static final ChannelLedger LEDGER = new ChannelLedger();

    /** Snapshotted once per connection so a mid-session config edit cannot change it. */
    private static volatile ExposurePolicy policy;

    private ExposureGuard() {
    }

    public static ChannelLedger ledger() {
        return LEDGER;
    }

    /** Called when a new connection opens; the next payload rebuilds the snapshot. */
    public static void resetForNewConnection() {
        policy = null;
        LEDGER.reset();
    }

    private static ExposurePolicy policy() {
        ExposurePolicy current = policy;
        if (current == null) {
            current = GuardConfig.get().exposure.policyFor(currentServerKey());
            policy = current;
        }
        return current;
    }

    public static String currentServerKey() {
        Minecraft client = Minecraft.getInstance();
        ServerData server = client == null ? null : client.getCurrentServer();
        if (server == null || server.ip == null) {
            return "singleplayer";
        }
        return server.ip.toLowerCase(Locale.ROOT);
    }

    private static boolean active() {
        GuardConfig config = GuardConfig.get();
        return config.enabled && config.exposure.enabled;
    }

    /** True when this packet must not leave the client at all. */
    public static boolean shouldDrop(Packet<?> packet) {
        if (!(packet instanceof ServerboundCustomPayloadPacket custom) || !active()) {
            return false;
        }
        try {
            String channel = channelOf(custom);
            boolean exposed = policy().isExposed(channel);
            LEDGER.record(channel, exposed);
            return !exposed;
        } catch (RuntimeException e) {
            CmdGuardClient.LOGGER.error("[cmdguard] exposure check failed, withholding", e);
            return true;
        }
    }

    /** Returns the packet with withheld identifiers stripped, or the original unchanged. */
    public static Packet<?> rewriteOrSame(Packet<?> packet) {
        if (!(packet instanceof ServerboundCustomPayloadPacket custom) || !active()) {
            return packet;
        }
        try {
            CustomPacketPayload payload = custom.payload();
            if (!PayloadRewriter.isRewritable(payload)) {
                return packet;
            }
            CustomPacketPayload filtered = PayloadRewriter.rewrite(payload, policy());
            return filtered == payload ? packet : new ServerboundCustomPayloadPacket(filtered);
        } catch (RuntimeException e) {
            CmdGuardClient.LOGGER.error("[cmdguard] payload rewrite failed", e);
            return packet;
        }
    }

    /** False when an inbound payload on this channel must not reach the mod that owns it. */
    public static boolean allowInbound(Identifier channel) {
        GuardConfig config = GuardConfig.get();
        if (!active() || !config.exposure.filterInbound) {
            return true;
        }
        try {
            String id = channel.toString();
            boolean exposed = policy().isExposed(id);
            if (!exposed) {
                LEDGER.record(id, false);
            }
            return exposed;
        } catch (RuntimeException e) {
            CmdGuardClient.LOGGER.error("[cmdguard] inbound check failed, withholding", e);
            return false;
        }
    }

    private static String channelOf(ServerboundCustomPayloadPacket packet) {
        return packet.payload().type().id().toString();
    }
}
```

Note on `rewriteOrSame` failing open: a rewrite failure returns the original packet, but `shouldDrop` has already decided whether the channel itself may be disclosed. A rewrite failure can therefore leak identifiers *inside* an exposed channel. If Step 4's compile shows any way for `rewrite` to throw on a valid payload, change this catch to return a payload with an empty collection instead.

- [ ] **Step 3: Write the mixin**

Create `src/main/java/studios/creeperdiamonds/cmdguard/mixin/ConnectionMixin.java`. Replace `send` and the `PacketFlow` accessor with the exact names recorded in Task 5:

```java
package studios.creeperdiamonds.cmdguard.mixin;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studios.creeperdiamonds.cmdguard.exposure.ExposureGuard;

/**
 * The outbound choke point.
 *
 * <p>Deliberately Connection rather than the client packet listener: a mod holding the
 * Connection can build a payload packet and send it directly, skipping the listener
 * entirely -- and a mod written to answer server probes is exactly the kind that would.
 * Hooking here also covers the configuration phase, where every Fabric API
 * client-to-server payload lives.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {

    @Shadow
    public abstract PacketFlow getSending();

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"), cancellable = true)
    private void cmdguard$dropWithheld(Packet<?> packet, CallbackInfo ci) {
        if (getSending() == PacketFlow.SERVERBOUND && ExposureGuard.shouldDrop(packet)) {
            ci.cancel();
        }
    }

    @ModifyVariable(method = "send(Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"), argsOnly = true)
    private Packet<?> cmdguard$filterIdentifiers(Packet<?> packet) {
        if (getSending() != PacketFlow.SERVERBOUND) {
            return packet;
        }
        return ExposureGuard.rewriteOrSame(packet);
    }
}
```

If Task 5 found that the public send methods delegate to a private funnel, target the funnel instead and keep both injectors on it. If there are several public overloads that do not share a funnel, add one pair of injectors per overload — an unhooked overload is an unfiltered path.

- [ ] **Step 4: Register the mixin**

In `src/main/resources/cmdguard.mixins.json`, add `"ConnectionMixin"` to the `client` array:

```json
  "client": [
    "ClientPacketListenerMixin",
    "ConnectionMixin"
  ],
```

- [ ] **Step 5: Compile**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, existing tests still pass. A mixin target failure here is the intended safety net — correct the name from Task 5's notes rather than lowering `defaultRequire`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/studios/creeperdiamonds/cmdguard/exposure/ExposureGuard.java src/main/java/studios/creeperdiamonds/cmdguard/mixin/ConnectionMixin.java src/main/resources/cmdguard.mixins.json src/main/java/studios/creeperdiamonds/cmdguard/GuardConfig.java
git commit -m "feat: withhold non-whitelisted payloads at the connection"
```

---

### Task 8: Inbound filtering and connection reset

**Files:**
- Modify: `src/main/java/studios/creeperdiamonds/cmdguard/mixin/ClientPacketListenerMixin.java`
- Modify: `src/main/java/studios/creeperdiamonds/cmdguard/CmdGuardClient.java`

**Interfaces:**
- Consumes: `ExposureGuard.allowInbound`, `ExposureGuard.resetForNewConnection`, and the inbound handler signature recorded in Task 5.
- Produces: no new public API.

- [ ] **Step 1: Add the inbound injector**

In `ClientPacketListenerMixin.java`, add imports for `ClientboundCustomPayloadPacket`, `CallbackInfo` is already present, and `ExposureGuard`. Then append this method, replacing the method name with the one recorded in Task 5:

```java
    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void cmdguard$dropWithheldInbound(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        if (!ExposureGuard.allowInbound(packet.payload().type().id())) {
            ci.cancel();
        }
    }
```

If Task 5 found this handler is declared on `ClientCommonPacketListenerImpl` rather than `ClientPacketListener`, create `src/main/java/studios/creeperdiamonds/cmdguard/mixin/ClientCommonPacketListenerImplMixin.java` with the same injector and register it in `cmdguard.mixins.json` instead of editing this file.

- [ ] **Step 2: Reset the snapshot on each new connection**

In `CmdGuardClient.java`, add the imports:

```java
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import studios.creeperdiamonds.cmdguard.exposure.ExposureGuard;
```

and register the reset inside `onInitializeClient()`, after the existing `C2SPlayChannelEvents` registration:

```java
        // A connection gets one policy snapshot for its whole lifetime, so a config edit
        // mid-session cannot filter the configuration phase under one policy and the play
        // phase under another.
        ClientConfigurationConnectionEvents.INIT.register((handler, client) ->
                ExposureGuard.resetForNewConnection());
```

- [ ] **Step 3: Compile**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/studios/creeperdiamonds/cmdguard/mixin src/main/java/studios/creeperdiamonds/cmdguard/CmdGuardClient.java src/main/resources/cmdguard.mixins.json
git commit -m "feat: drop inbound probes on withheld channels"
```

---

### Task 9: Commands

**Files:**
- Modify: `src/main/java/studios/creeperdiamonds/cmdguard/CmdGuardCommands.java`

**Interfaces:**
- Consumes: `GuardConfig.exposure`, `ExposureGuard.ledger()`, `ExposurePolicy`.
- Produces: `/cmdguard exposure`, `/cmdguard expose <namespace>`, `/cmdguard expose global <namespace>`, `/cmdguard withhold <namespace>`.

Output goes through the file's existing `feedback(ctx, Component)` helper, not through `OutboundGuard.say` — commands already have a source to reply to.

- [ ] **Step 1: Add the imports**

At the top of `CmdGuardCommands.java`, add:

```java
import net.minecraft.network.chat.MutableComponent;
import studios.creeperdiamonds.cmdguard.exposure.ChannelLedger;
import studios.creeperdiamonds.cmdguard.exposure.ExposureGuard;
import studios.creeperdiamonds.cmdguard.exposure.ExposurePolicy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
```

- [ ] **Step 2: Add the subcommands to the tree**

In `register(...)`, insert these three `.then(...)` calls immediately after the existing `audit` literal and before `clear`:

```java
                .then(ClientCommandManager.literal("exposure").executes(CmdGuardCommands::exposure))
                .then(ClientCommandManager.literal("expose")
                        .then(ClientCommandManager.literal("global")
                                .then(ClientCommandManager.argument("namespace", StringArgumentType.word())
                                        .executes(ctx -> expose(ctx,
                                                StringArgumentType.getString(ctx, "namespace"), true))))
                        .then(ClientCommandManager.argument("namespace", StringArgumentType.word())
                                .executes(ctx -> expose(ctx,
                                        StringArgumentType.getString(ctx, "namespace"), false))))
                .then(ClientCommandManager.literal("withhold")
                        .then(ClientCommandManager.argument("namespace", StringArgumentType.word())
                                .executes(ctx -> withhold(ctx,
                                        StringArgumentType.getString(ctx, "namespace")))))
```

- [ ] **Step 3: Add the three executors**

Append these methods before the private `feedback` helper:

```java
    private static int exposure(CommandContext<FabricClientCommandSource> ctx) {
        List<ChannelLedger.Entry> entries = ExposureGuard.ledger().snapshot();

        if (entries.isEmpty()) {
            feedback(ctx, Component.literal("No channels observed yet on this connection.")
                    .withStyle(ChatFormatting.GRAY));
            return 1;
        }

        long exposed = entries.stream().filter(ChannelLedger.Entry::exposed).count();
        feedback(ctx, Component.literal("CmdGuard exposure on "
                        + ExposureGuard.currentServerKey() + ": ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(exposed + " exposed").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(", " + (entries.size() - exposed) + " withheld")
                        .withStyle(ChatFormatting.YELLOW)));

        for (ChannelLedger.Entry entry : entries) {
            MutableComponent line = Component.literal("  " + entry.channel() + " ")
                    .withStyle(ChatFormatting.WHITE)
                    .append(entry.exposed()
                            ? Component.literal("EXPOSED").withStyle(ChatFormatting.GREEN)
                            : Component.literal("WITHHELD").withStyle(ChatFormatting.GOLD));

            if (entry.withheldCount() > 0) {
                line.append(Component.literal(" x" + entry.withheldCount())
                        .withStyle(ChatFormatting.GRAY));
            }
            if (ExposurePolicy.NEVER_WITHHELD.contains(entry.channel())) {
                line.append(Component.literal("  always exposed").withStyle(ChatFormatting.DARK_GRAY));
            }
            if ("fabric:registry/sync/complete".equals(entry.channel())) {
                line.append(Component.literal(" -- required to finish joining, carries no data")
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
            feedback(ctx, line);
        }
        return 1;
    }

    private static int expose(CommandContext<FabricClientCommandSource> ctx,
                              String namespace, boolean global) {
        GuardConfig config = GuardConfig.get();
        String value = namespace.toLowerCase(Locale.ROOT);
        String server = ExposureGuard.currentServerKey();
        boolean added;

        if (global) {
            added = config.exposure.exposedNamespaces.add(value);
        } else {
            added = config.exposure.perServerNamespaces
                    .computeIfAbsent(server, key -> new LinkedHashSet<>())
                    .add(value);
        }
        config.save();

        feedback(ctx, Component.literal(added
                        ? "Exposing " + value + (global ? " everywhere" : " on " + server)
                        : value + " was already exposed")
                .withStyle(added ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        feedback(ctx, Component.literal("Takes effect on your next connection.")
                .withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static int withhold(CommandContext<FabricClientCommandSource> ctx, String namespace) {
        GuardConfig config = GuardConfig.get();
        String value = namespace.toLowerCase(Locale.ROOT);

        boolean removed = config.exposure.exposedNamespaces.remove(value);
        Set<String> perServer =
                config.exposure.perServerNamespaces.get(ExposureGuard.currentServerKey());
        if (perServer != null) {
            removed |= perServer.remove(value);
        }
        config.save();

        feedback(ctx, Component.literal(removed
                        ? "Withholding " + value + " -- takes effect on your next connection."
                        : value + " was not exposed")
                .withStyle(removed ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
        return 1;
    }
```

- [ ] **Step 4: Compile**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/studios/creeperdiamonds/cmdguard/CmdGuardCommands.java
git commit -m "feat: exposure commands"
```

---

### Task 10: Documentation, strict dependency, and manual acceptance

**Files:**
- Modify: `src/main/java/studios/creeperdiamonds/cmdguard/ChannelAudit.java`
- Modify: `src/main/resources/fabric.mod.json`
- Modify: `README.md`
- Modify: `src/main/java/studios/creeperdiamonds/cmdguard/ConfigScreen.java`

- [ ] **Step 1: Rewrite the `ChannelAudit` javadoc**

The current class javadoc argues that suppressing a reply while staying connected deceives the operator. Shipping the exposure layer beside that text makes the comment false. Replace the two paragraphs beginning "Transparency, not concealment." with:

```java
/**
 * The readout of the exposure layer.
 *
 * <p>A server cannot read your mods folder and Fabric Loader never sends a mod list. What
 * a server CAN do is ask on a custom channel, or simply read the channel list your client
 * advertises -- and either names your mods. This lists the channels involved and what
 * CmdGuard decided about each.
 *
 * <p>CmdGuard withholds what is not on the exposure whitelist and never fabricates. It
 * does not claim to be vanilla, does not alter minecraft:brand, and never advertises a
 * channel or identifier the client does not actually have. Declining to answer is a
 * refusal; inventing an answer would be a lie, and this mod does not do the second.
 */
```

- [ ] **Step 2: Declare the strict Fabric API dependency**

In `fabric.mod.json`, set the `fabric-api` entry under `depends` to the exact pinned version rather than a range:

```json
    "fabric-api": "0.141.6+1.21.11"
```

`PayloadRewriter` imports Fabric `impl` records. A user on a different Fabric API version must get a clean refusal to start, not a crash partway through a handshake.

- [ ] **Step 3: Add the exposure toggles to the Mod Menu screen**

In `ConfigScreen.java`, insert these two widgets after the existing "Audit mod channels" button and before the "Done" button:

```java
        this.addRenderableWidget(Button.builder(
                Component.literal("Exposure whitelist: " + (config.exposure.enabled ? "ON" : "OFF")),
                button -> {
                    config.exposure.enabled = !config.exposure.enabled;
                    config.save();
                    button.setMessage(Component.literal("Exposure whitelist: "
                            + (config.exposure.enabled ? "ON" : "OFF")));
                }).bounds(centerX - 100, y + 72, 200, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Inbound probes: "
                        + (config.exposure.filterInbound ? "blocked" : "allowed")),
                button -> {
                    config.exposure.filterInbound = !config.exposure.filterInbound;
                    config.save();
                    button.setMessage(Component.literal("Inbound probes: "
                            + (config.exposure.filterInbound ? "blocked" : "allowed")));
                }).bounds(centerX - 100, y + 96, 200, 20).build());
```

Then move the existing "Done" button down so it does not overlap: change its bounds from `y + 84` to `y + 132`.

Namespace editing stays out of this screen; that is what the commands are for.

- [ ] **Step 4: Document what this does and does not do**

Add a README section titled `Exposure whitelist`. It must state, in plain words:

- What is withheld by default: every channel outside `fabric`, `minecraft`, `c`, in both directions, including the identifier lists inside Fabric API's own attachment, recipe-serializer and custom-ingredient payloads.
- What is not hidden: `minecraft:brand` still reads `fabric`, Fabric API's channels are still advertised, and a server can still tell you are modded and roughly on what Fabric API version. This reduces mod-inventory disclosure; it does not make you look unmodded.
- That withholding degrades the mods you withheld, because the server will not sync what you declined to accept.
- That a server requiring a client mod you are withholding will kick you, and that `/cmdguard expose <namespace>` plus a reconnect is the fix.

- [ ] **Step 5: Build and run the full test suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, 22 tests pass.

- [ ] **Step 6: Commit and open a pull request**

```bash
git add -A
git commit -m "docs: state the disclosure-control stance and its limits"
git push -u origin exposure-whitelist
gh pr create --title "Client metadata exposure whitelist" --body "Implements docs/superpowers/specs/2026-08-29-exposure-whitelist-design.md"
```

CI runs on pull requests, and `./gradlew build` runs `test`, so the pure tests gate the merge.

- [ ] **Step 7: Manual acceptance on a machine with a Minecraft client**

Unit tests cannot show the interception point is correct. This must be run by hand before the work is called done, per the spec's acceptance section:

1. Join a vanilla server. Connects normally.
2. Join a Fabric server running mods, with third-party client mods installed. The connection completes with no hang in the configuration phase — a hang means `fabric:registry/sync/complete` is being wrongly withheld.
3. `/cmdguard exposure` lists withheld channels with a non-zero withheld count.
4. `/cmdguard expose <namespace>` for one withheld mod, reconnect, confirm the count drops and that namespace reads EXPOSED.
5. The command guard and `/cmdguard audit` behave as before.

Record the results in `NOTES.md`. Until step 7 is done, the feature is untested against a real server and should be described that way.
