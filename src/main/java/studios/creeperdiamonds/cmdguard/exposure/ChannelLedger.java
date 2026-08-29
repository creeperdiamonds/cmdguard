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
