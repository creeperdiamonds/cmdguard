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

    /**
     * How many distinct channels this connection has seen on each side of the decision,
     * plus the total number of individual payloads withheld. What the one-line summary in
     * {@code ExposureGuard.beginConnection} reports for the connection that just ended.
     */
    public record Counts(int exposed, int withheld, int withheldPayloads) {

        public boolean isEmpty() {
            return exposed == 0 && withheld == 0;
        }
    }

    private final Map<String, Entry> entries = new TreeMap<>();

    /**
     * Records one decision about one channel.
     *
     * @return true when this call is the <em>first</em> withhold recorded for this channel,
     *         which is the caller's cue to log it once rather than once per payload. A
     *         channel that flips from exposed to withheld mid-connection counts as a first
     *         withhold, because that transition is worth a line in the log too.
     */
    public synchronized boolean record(String channel, boolean exposed) {
        Entry previous = entries.get(channel);
        int withheld = previous == null ? 0 : previous.withheldCount();
        boolean firstWithhold = !exposed && withheld == 0;
        if (!exposed) {
            withheld++;
        }
        entries.put(channel, new Entry(channel, exposed, withheld));
        return firstWithhold;
    }

    public synchronized List<Entry> snapshot() {
        return List.copyOf(new ArrayList<>(entries.values()));
    }

    public synchronized Counts counts() {
        int exposed = 0;
        int withheld = 0;
        int withheldPayloads = 0;
        for (Entry entry : entries.values()) {
            if (entry.exposed()) {
                exposed++;
            } else {
                withheld++;
            }
            withheldPayloads += entry.withheldCount();
        }
        return new Counts(exposed, withheld, withheldPayloads);
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
