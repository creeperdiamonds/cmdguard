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
