/*
 * DtnmaAuditReport.java
 * Explainable AI / Forensic Audit Logger for Autonomic DTNMA (RFC 9675 / RFC 7575)
 * and Mobility-Aware Autonomic Resource Coordination.
 * Logs exact decision-time EDD snapshots, SafeMask bitmask, fallback/proposed/executed
 * actions, Shield status, causal ARI rule, and log-bytes-per-node-hour summary.
 */
package report;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import core.DTNHost;
import core.SimClock;
import core.UpdateListener;
import routing.DtnmaAgent;
import routing.DtnmaConfig;
import routing.DtnmaManagedRouter;
import routing.DtnmaPolicyProfile;
import routing.DtnmaRouter;

public class DtnmaAuditReport extends Report implements UpdateListener {

    private double lastLogTime = -1.0;
    private static final double DEFAULT_LOG_INTERVAL = 30.0;
    private double logInterval = DEFAULT_LOG_INTERVAL;

    private long totalRows = 0;
    private long traceableRows = 0;
    private long totalLogBytes = 0;
    private Set<Integer> auditedHosts = new HashSet<Integer>();

    public DtnmaAuditReport() {
        super();
        init();
        writeLineTracked("# ====================================================================");
        writeLineTracked("# RFC 9675 DTNMA + MOBILITY-AWARE AUTONOMIC EXPLAINABILITY AUDIT LOG");
        writeLineTracked("# Decision-time EDD state snapshot + SafeMask + Causal ARI for Replay");
        writeLineTracked("# ====================================================================");
        writeLineTracked("# SimTime\tDecisionTime\tNodeID\tMode\tQ\tCritQ\tdQ\tDensity\tSpeed\tSoC\tActiveConn\tCtrlVer\tShieldEnergyMin\tShieldWarnCritQ\tProposedAct\tFallbackAct\tExecutedAct\tShieldStatus\tSafeMask\tActiveRuleARI\tCausalExplanation");
    }

    @Override
    protected void init() {
        super.init();
        this.lastLogTime = -1.0;
        this.totalRows = 0;
        this.traceableRows = 0;
        this.totalLogBytes = 0;
        if (this.auditedHosts == null) this.auditedHosts = new HashSet<Integer>();
        else this.auditedHosts.clear();
    }

    private void writeLineTracked(String line) {
        write(line);
        this.totalLogBytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
    }

    private static String formatMaskBits(int maskBits) {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < DtnmaPolicyProfile.NUM_ACTIONS; i++) {
            sb.append(((maskBits >> i) & 1) == 1 ? '1' : '0');
        }
        return sb.toString();
    }

    @Override
    public void updated(List<DTNHost> hosts) {
        double simTime = SimClock.getTime();
        if (lastLogTime >= 0 && (simTime - lastLogTime) < logInterval) {
            return;
        }
        lastLogTime = simTime;

        for (DTNHost host : hosts) {
            if (!host.isRadioActive()) {
                continue;
            }
            if (host.getRouter() instanceof DtnmaManagedRouter) {
                DtnmaAgent router = ((DtnmaManagedRouter) host.getRouter()).getDtnmaAgent();
                if (router == null || router.getLastDecisionTime() < 0.0) {
                    continue;
                }
                this.auditedHosts.add(host.getAddress());

                String mode = router.getEffectiveManagementMode().name();
                String propAct = DtnmaPolicyProfile.get(router.getLastProposedProfile()).getName();
                String fbAct = DtnmaPolicyProfile.get(router.getLastFallbackProfile()).getName();
                String execAct = DtnmaPolicyProfile.get(router.getLastExecutedProfile()).getName();
                String shieldStatus = router.isLastShieldIntervened() ? "OVERRIDE" : "PASS";
                String maskStr = formatMaskBits(router.getLastSafeMaskBits());
                String curRule = router.getActiveRuleAri();
                String explanation = router.getLastExplanation();
                double shEnergyMin = router.getLastDecisionShieldEnergyMin();
                double shWarnCritQ = router.getLastDecisionShieldWarnCritQ();

                if (curRule != null && curRule.startsWith("ARI://")
                        && explanation != null && !explanation.trim().isEmpty()) {
                    this.traceableRows++;
                }
                this.totalRows++;

                String row = String.format(Locale.US,
                        "%.1f\t%.1f\t%d\t%s\t%.9f\t%.9f\t%.9f\t%.9f\t%.9f\t%.9f\t%d\t%d\t%.9f\t%.9f\t%s\t%s\t%s\t%s\t%s\t%s\t%s",
                        simTime, router.getLastDecisionTime(), host.getAddress(), mode,
                        router.getLastDecisionQ(), router.getLastDecisionCritQ(), router.getLastDecisionDq(),
                        router.getLastDecisionDensity(), router.getLastDecisionSpeed(),
                        router.getLastDecisionSoc(), router.getLastDecisionActiveConn(),
                        router.getLastDecisionCtrlVer(), shEnergyMin, shWarnCritQ,
                        propAct, fbAct, execAct, shieldStatus, maskStr, curRule, explanation);
                writeLineTracked(row);
            }
        }
    }

    @Override
    public void done() {
        double simHours = Math.max(1e-6, SimClock.getTime() / 3600.0);
        int nHosts = Math.max(1, this.auditedHosts.size());
        double logBytesPerNodeHour = (double) this.totalLogBytes / (nHosts * simHours);
        double traceableRatio = (this.totalRows > 0) ? (double) this.traceableRows / this.totalRows : 1.0;

        write("# ====================================================================");
        write(String.format(Locale.US, "# AUDIT_SUMMARY total_audited_decisions: %d", this.totalRows));
        write(String.format(Locale.US, "# AUDIT_SUMMARY traceable_ari_ratio: %.4f", traceableRatio));
        write(String.format(Locale.US, "# AUDIT_SUMMARY total_log_bytes: %d", this.totalLogBytes));
        write(String.format(Locale.US, "# AUDIT_SUMMARY log_bytes_per_node_hour: %.2f", logBytesPerNodeHour));
        write("# ====================================================================");
        super.done();
    }
}
