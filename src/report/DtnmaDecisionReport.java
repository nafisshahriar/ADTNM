/*
 * DtnmaDecisionReport.java
 * Verification report for DTNMA closed-loop autonomic policy adaptation,
 * decentralized decision tracking, and critical fault observability.
 * Distinguishes congestion buffer-eviction drops (ttl > 0) from natural TTL expirations (ttl <= 0).
 */
package report;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import core.DTNHost;
import core.Message;
import core.MessageListener;
import core.SimClock;
import core.SimScenario;
import core.UpdateListener;
import routing.DtnmaRouter;
import routing.DtnmaSafetyShield;

/**
 * Custom verification report that logs node-level autonomic decisions,
 * queue dynamics, and message drops over time.
 * Supports the 3-Tier Verification Pipeline:
 * Tier 1: Micro-level event trace
 * Tier 2: Domain-specific critical fault preservation vs bulk science shedding
 * Tier 3: Macro-level metrics
 */
public class DtnmaDecisionReport extends Report implements UpdateListener, MessageListener {

    private double lastLogTime = -1.0;
    private static final double DEFAULT_LOG_INTERVAL = 10.0; // Log state every 10 seconds
    private double logInterval = DEFAULT_LOG_INTERVAL;

    // Domain metrics tracking (Option A: buffer eviction vs TTL expiration)
    private int criticalCreated = 0;
    private int criticalDelivered = 0;
    private int criticalBufferDropped = 0;
    private int criticalTtlExpired = 0;

    private int bulkCreated = 0;
    private int bulkDelivered = 0;
    private int bulkBufferDropped = 0;
    private int bulkTtlExpired = 0;

    private Map<Integer, Integer> actionCounts = new HashMap<Integer, Integer>();

    public DtnmaDecisionReport() {
        super();
        init();
        write("# SimTime\tNodeID\tQueueFill%\tActionTaken\tActionDescription");
        actionCounts.put(0, 0); // NOMINAL
        actionCounts.put(1, 0); // THROTTLE
        actionCounts.put(2, 0); // SHED
    }

    @Override
    public void updated(List<DTNHost> hosts) {
        double simTime = SimClock.getTime();
        if (lastLogTime >= 0 && (simTime - lastLogTime) < logInterval) {
            return;
        }
        lastLogTime = simTime;

        for (DTNHost host : hosts) {
            if (host.getRouter() instanceof DtnmaRouter) {
                DtnmaRouter router = (DtnmaRouter) host.getRouter();
                double qRatio = host.getBufferOccupancy();
                int action = router.getLastActionIndex();
                String actionDesc = router.getActionDescription(action);

                Integer count = actionCounts.get(action);
                actionCounts.put(action, (count == null ? 1 : count + 1));

                write(String.format(Locale.US, "%.1f\t%d\t%.2f\t%d\t%s",
                        simTime, host.getAddress(), qRatio, action, actionDesc));
            }
        }
    }

    // MessageListener hooks to verify critical fault vs bulk science buffer drops
    @Override
    public void messageDeleted(Message m, DTNHost where, boolean dropped) {
        if (isWarmupID(m.getId())) {
            return;
        }
        if (dropped) {
            boolean isCritical = DtnmaRouter.isCriticalMessage(m);
            boolean isTtlExpired = (m.getTtl() <= 0);

            if (isTtlExpired) {
                if (isCritical) {
                    criticalTtlExpired++;
                } else {
                    bulkTtlExpired++;
                }
            } else {
                if (isCritical) {
                    criticalBufferDropped++;
                } else {
                    bulkBufferDropped++;
                }
                write(String.format(Locale.US, "# DROP_EVENT\t%.1f\tNode:%d\tMsg:%s\tIsCritical:%b",
                        SimClock.getTime(), where.getAddress(), m.getId(), isCritical));
            }
        }
    }

    @Override
    public void newMessage(Message m) {
        if (isWarmup()) {
            addWarmupID(m.getId());
            return;
        }
        if (DtnmaRouter.isCriticalMessage(m)) {
            criticalCreated++;
        } else {
            bulkCreated++;
        }
    }

    @Override
    public void messageTransferred(Message m, DTNHost from, DTNHost to, boolean firstDelivery) {
        if (isWarmupID(m.getId())) {
            return;
        }
        if (firstDelivery) {
            if (DtnmaRouter.isCriticalMessage(m)) {
                criticalDelivered++;
            } else {
                bulkDelivered++;
            }
        }
    }

    @Override
    public void messageTransferAborted(Message m, DTNHost from, DTNHost to) {}

    @Override
    public void messageTransferStarted(Message m, DTNHost from, DTNHost to) {}

    @Override
    public void done() {
        long shieldDecisions = 0;
        long shieldInterventions = 0;
        List<DTNHost> hosts = SimScenario.getInstance().getHosts();
        if (hosts != null) {
            for (DTNHost h : hosts) {
                if (h.getRouter() instanceof DtnmaRouter) {
                    DtnmaSafetyShield shield = ((DtnmaRouter) h.getRouter()).getSafetyShield();
                    if (shield != null) {
                        shieldDecisions += shield.getTotalDecisions();
                        shieldInterventions += shield.getTotalInterventions();
                    }
                }
            }
        }
        double shieldIntPct = shieldDecisions > 0 ? ((double) shieldInterventions / shieldDecisions) * 100.0 : 0.0;

        write("# ====================================================================");
        write("# DOMAIN-SPECIFIC VERIFICATION SUMMARY (Research Question Proof)");
        write("# ====================================================================");
        write(String.format(Locale.US, "# Critical Fault Messages Created:   %d", criticalCreated));
        write(String.format(Locale.US, "# Critical Fault Messages Delivered: %d", criticalDelivered));
        write(String.format(Locale.US, "# Critical Fault Messages Dropped:   %d", criticalBufferDropped));
        write(String.format(Locale.US, "# Critical Fault TTL Expired:        %d", criticalTtlExpired));
        double critDeliveryRatio = criticalCreated > 0 ? ((double) criticalDelivered / criticalCreated) * 100.0 : 0.0;
        write(String.format(Locale.US, "# Critical Fault Delivery Ratio:     %.2f%%", critDeliveryRatio));
        write("# --------------------------------------------------------------------");
        write(String.format(Locale.US, "# Bulk Science Messages Created:     %d", bulkCreated));
        write(String.format(Locale.US, "# Bulk Science Messages Delivered:   %d", bulkDelivered));
        write(String.format(Locale.US, "# Bulk Science Messages Dropped:     %d", bulkBufferDropped));
        write(String.format(Locale.US, "# Bulk Science TTL Expired:          %d", bulkTtlExpired));
        write("# --------------------------------------------------------------------");
        write(String.format(Locale.US, "# Autonomic Nominal Actions:         %d", actionCounts.get(0)));
        write(String.format(Locale.US, "# Autonomic Throttle Actions:        %d", actionCounts.get(1)));
        write(String.format(Locale.US, "# Autonomic Shed Actions:            %d", actionCounts.get(2)));
        write(String.format(Locale.US, "# Shield Decisions Total:            %d", shieldDecisions));
        write(String.format(Locale.US, "# Shield Interventions Total:        %d (%.2f%%)", shieldInterventions, shieldIntPct));
        write("# ====================================================================");

        super.done();
    }
}
