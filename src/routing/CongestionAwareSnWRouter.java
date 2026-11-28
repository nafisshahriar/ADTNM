/*
 * CongestionAwareSnWRouter.java
 * Independent congestion- and encounter-utility-aware Spray-and-Wait mobile DTN
 * literature baseline (CAFRep-inspired forwarding utility + static low-energy
 * relay guard, operating directly over SprayAndWaitRouter without DtnmaAgent).
 */
package routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;

public class CongestionAwareSnWRouter extends SprayAndWaitRouter {

    public static final double ENCOUNTER_WINDOW_SEC = 300.0;
    public static final double STATIC_ENERGY_RELAY_MIN_SOC = 0.25;
    public static final double PEER_MAX_OCCUPANCY = 0.90;
    public static final double UTILITY_MARGIN = 0.05;
    public static final double RELIEF_OCCUPANCY = 0.75;

    private double initialEnergy = 1.0;
    private Map<Integer, Double> encounterTimestamps;

    public CongestionAwareSnWRouter(Settings s) {
        super(s);
        this.encounterTimestamps = new HashMap<Integer, Double>();
    }

    protected CongestionAwareSnWRouter(CongestionAwareSnWRouter r) {
        super(r);
        if (this.energy != null && this.energy.getEnergy() > 0.0) {
            this.initialEnergy = this.energy.getEnergy();
        } else {
            this.initialEnergy = 1.0;
        }
        this.encounterTimestamps = new HashMap<Integer, Double>();
    }

    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);
        if (con != null && con.isUp()) {
            DTNHost peer = con.getOtherNode(getHost());
            if (peer != null) {
                if (this.encounterTimestamps == null) {
                    this.encounterTimestamps = new HashMap<Integer, Double>();
                }
                this.encounterTimestamps.put(peer.getAddress(), SimClock.getTime());
            }
        }
    }

    public double getEnergySoc() {
        if (this.energy == null || this.initialEnergy <= 0.0) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, this.energy.getEnergy() / this.initialEnergy));
    }

    public double getBufferOccupancyRatio() {
        long bSize = getBufferSize();
        if (bSize <= 0L) {
            return 0.0;
        }
        return (double) (bSize - getFreeBufferSize()) / (double) bSize;
    }

    public double getEncounterRatePerMin() {
        if (this.encounterTimestamps == null || this.encounterTimestamps.isEmpty()) {
            return 0.0;
        }
        double cutoff = SimClock.getTime() - ENCOUNTER_WINDOW_SEC;
        int recent = 0;
        Iterator<Map.Entry<Integer, Double>> it = this.encounterTimestamps.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Double> e = it.next();
            if (e.getValue() < cutoff) {
                it.remove();
            } else {
                recent++;
            }
        }
        return recent / (ENCOUNTER_WINDOW_SEC / 60.0);
    }

    /**
     * Composite forwarding utility: balances buffer headroom (1 - q) and local
     * encounter frequency in [0, 1].
     */
    public double getForwardingUtility() {
        double q = getBufferOccupancyRatio();
        double encNorm = Math.min(1.0, getEncounterRatePerMin() / 6.0);
        return 0.5 * (1.0 - Math.max(0.0, Math.min(1.0, q))) + 0.5 * encNorm;
    }

    /**
     * Evaluates whether forwarding a non-destination spray copy to the peer in
     * {@code con} satisfies static energy, buffer-congestion, and utility rules.
     */
    public boolean canRelayToPeer(Connection con) {
        if (con == null || !con.isUp()) {
            return false;
        }
        if (getEnergySoc() < STATIC_ENERGY_RELAY_MIN_SOC) {
            return false;
        }
        DTNHost peer = con.getOtherNode(getHost());
        if (peer == null) {
            return false;
        }
        MessageRouter pr = peer.getRouter();
        if (!(pr instanceof ActiveRouter)) {
            return false;
        }
        ActiveRouter peerActive = (ActiveRouter) pr;
        long peerBuf = peerActive.getBufferSize();
        double peerOcc = (peerBuf > 0L)
                ? ((double) (peerBuf - peerActive.getFreeBufferSize()) / (double) peerBuf)
                : 0.0;
        if (peerOcc >= PEER_MAX_OCCUPANCY) {
            return false;
        }
        if (pr instanceof CongestionAwareSnWRouter) {
            double selfOcc = getBufferOccupancyRatio();
            double selfUtil = getForwardingUtility();
            double peerUtil = ((CongestionAwareSnWRouter) pr).getForwardingUtility();
            return (peerUtil >= selfUtil - UTILITY_MARGIN) || (selfOcc >= RELIEF_OCCUPANCY);
        }
        return true;
    }

    /**
     * Filters non-destination spray forwarding connections while keeping
     * ActiveRouter maintenance and direct exchangeDeliverableMessages() intact
     * inside SprayAndWaitRouter.update().
     */
    @Override
    protected Connection tryMessagesToConnections(List<Message> messages, List<Connection> connections) {
        if (messages == null || messages.isEmpty() || connections == null || connections.isEmpty()) {
            return null;
        }
        if (getEnergySoc() < STATIC_ENERGY_RELAY_MIN_SOC) {
            return null;
        }
        List<Connection> eligibleConns = new ArrayList<Connection>(connections.size());
        for (Connection con : connections) {
            if (canRelayToPeer(con)) {
                eligibleConns.add(con);
            }
        }
        if (eligibleConns.isEmpty()) {
            return null;
        }
        return super.tryMessagesToConnections(messages, eligibleConns);
    }

    @Override
    public CongestionAwareSnWRouter replicate() {
        return new CongestionAwareSnWRouter(this);
    }
}
