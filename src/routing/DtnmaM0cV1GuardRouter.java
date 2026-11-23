/*
 * DtnmaM0cV1GuardRouter.java
 * Comparison B M0c preset: Operator-preinstalled V1 energy & congestion guard
 * at boot (shieldEnergyMin=0.25, shieldCongestionQueue=0.88, Priority-110 V1 SRL),
 * immune to remote CTRL_UPDATE mutation (static policy deployment).
 */
package routing;

import core.Settings;

public class DtnmaM0cV1GuardRouter extends DtnmaRouter {

    private static final String V1_PREINSTALLED_RULES =
            "RULE: ARI://DTNMA/SRL/V1_PREINSTALLED_GUARD | PRIORITY: 110 | DESC: Pre-installed V1 energy and congestion guard\n"
          + "IF: (EDD_ENERGY_SOC < 0.25) || (EDD_QUEUE_RATIO >= 0.88)\n"
          + "THEN: CTRL_SET_SPRAY_QUOTA(1); CTRL_SET_INGRESS_MODE(2); CTRL_SET_TELEMETRY_INTERVAL(600); CTRL_SET_BACKPRESSURE(true)\n"
          + "RULE: ARI://DTNMA/SRL/V1_PREINSTALLED_NOMINAL | PRIORITY: 10 | DESC: Nominal operation outside V1 guard\n"
          + "IF: (EDD_QUEUE_RATIO >= 0.0)\n"
          + "THEN: CTRL_SET_SPRAY_QUOTA(6); CTRL_SET_INGRESS_MODE(0); CTRL_SET_TELEMETRY_INTERVAL(60); CTRL_SET_BACKPRESSURE(true)\n";

    public DtnmaM0cV1GuardRouter(Settings s) {
        super(s);
        this.config.managementMode = DtnmaConfig.ManagementMode.M2_AUTONOMIC_SRL;
        this.intelligenceMode = IntelligenceMode.STATIC_RULES;
        this.gossipEnabled = false;
        this.ackFeedbackEnabled = true;
        this.config.epidemicControlFlooding = false;
        this.config.shieldEnergyMin = 0.25;
        this.config.shieldCongestionQueue = 0.88;
        loadRulesFromString(V1_PREINSTALLED_RULES);
        applyBaselinePresetKnobs();
    }

    protected DtnmaM0cV1GuardRouter(DtnmaM0cV1GuardRouter r) {
        super(r);
    }

    @Override
    public boolean supportsDynamicControlReconfig() {
        return false;
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaM0cV1GuardRouter(this);
    }
}
