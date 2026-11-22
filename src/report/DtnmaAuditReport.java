/*
 * DtnmaAuditReport.java
 * Explainable AI / Forensic Audit Logger for Autonomic DTNMA (RFC 9675 / RFC 7575)
 * and Shielded CMDP Reinforcement Learning.
 * Logs Proposed vs Executed actions, Shield interventions, Dual Lambda, and Causal Reasoning.
 */
package report;

import java.util.List;
import java.util.Locale;

import core.DTNHost;
import core.SimClock;
import core.UpdateListener;
import routing.DtnmaPolicyProfile;
import routing.DtnmaRouter;
import routing.ShieldedCmdpRlAgent;

public class DtnmaAuditReport extends Report implements UpdateListener {

    private double lastLogTime = -1.0;
    private static final double DEFAULT_LOG_INTERVAL = 30.0;
    private double logInterval = DEFAULT_LOG_INTERVAL;

    public DtnmaAuditReport() {
        super();
        init();
        write("# ====================================================================");
        write("# RFC 9675 DTNMA + SHIELDED CMDP RL EXPLAINABILITY AUDIT LOG");
        write("# Causal evidence for node autonomic & RL decisions across heterogeneous regimes");
        write("# ====================================================================");
        write("# SimTime\tNodeID\tMode\tProposedAct\tExecutedAct\tShieldStatus\tActiveRuleARI\tLambda\tCausalExplanation");
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
                String mode = router.getIntelligenceMode().name();
                String propAct = DtnmaPolicyProfile.get(router.getLastProposedProfile()).getName();
                String execAct = DtnmaPolicyProfile.get(router.getLastExecutedProfile()).getName();
                String shieldStatus = router.isLastShieldIntervened() ? "OVERRIDE" : "PASS";
                String curRule = router.getActiveRuleAri();
                ShieldedCmdpRlAgent agent = router.getRlAgent();
                double lambda = (agent != null) ? agent.getLambda() : 0.0;
                String explanation = router.getLastExplanation();

                write(String.format(Locale.US, "%.1f\t%d\t%s\t%s\t%s\t%s\t%s\t%.4f\t%s",
                        simTime, host.getAddress(), mode, propAct, execAct,
                        shieldStatus, curRule, lambda, explanation));
            }
        }
    }
}
