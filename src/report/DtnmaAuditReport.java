/*
 * DtnmaAuditReport.java
 * Explainable AI / Forensic Audit Logger for Autonomic DTNMA (RFC 9675 / RFC 7575).
 * Logs precisely WHY each node made what autonomic decision at each timestep.
 */
package report;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import core.DTNHost;
import core.SimClock;
import core.UpdateListener;
import routing.DtnmaRouter;

public class DtnmaAuditReport extends Report implements UpdateListener {

    private double lastLogTime = -1.0;
    private static final double DEFAULT_LOG_INTERVAL = 30.0; // Log audit snapshot every 30s
    private double logInterval = DEFAULT_LOG_INTERVAL;

    // Track previous rule per node to log rule transitions cleanly
    private Map<Integer, String> lastNodeRuleMap = new HashMap<Integer, String>();

    public DtnmaAuditReport() {
        super();
        init();
        write("# ====================================================================");
        write("# RFC 9675 DTNMA EXPLAINABILITY AUDIT LOG");
        write("# Causal evidence for node autonomic decisions across heterogeneous regimes");
        write("# ====================================================================");
        write("# SimTime\tNodeID\tActiveRuleARI\tRegime\tCausalExplanation");
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
                String curRule = router.getActiveRuleAri();
                String explanation = router.getLastExplanation();
                int regime = router.getLastActionIndex();
                String regimeDesc = router.getActionDescription(regime);

                // Write audit entry
                write(String.format(Locale.US, "%.1f\t%d\t%s\t%s\t%s",
                        simTime, host.getAddress(), curRule, regimeDesc, explanation));

                lastNodeRuleMap.put(host.getAddress(), curRule);
            }
        }
    }
}
