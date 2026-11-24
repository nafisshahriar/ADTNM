/*
 * DtnmaM0eEnergy25Router.java
 * Post-freeze exploratory control M0e-25: Operator-preinstalled energy-only
 * static guard at boot (SoC < 0.25, NO queue congestion throttle), immutable to
 * remote CTRL_UPDATE mutation.
 */
package routing;

import core.Settings;

public class DtnmaM0eEnergy25Router extends DtnmaRouter {

    private static final String ENERGY25_PREINSTALLED_RULES =
            "RULE: ARI://DTNMA/SRL/M0E_ENERGY25_GUARD | PRIORITY: 110 | DESC: Pre-installed energy-only survival guard (SoC < 0.25)\n"
          + "IF: (EDD_ENERGY_SOC < 0.25)\n"
          + "THEN: CTRL_SET_SPRAY_QUOTA(1); CTRL_SET_INGRESS_MODE(2); CTRL_SET_TELEMETRY_INTERVAL(600); CTRL_SET_BACKPRESSURE(true)\n"
          + "RULE: ARI://DTNMA/SRL/M0E_NOMINAL | PRIORITY: 10 | DESC: Nominal operation (no queue congestion throttle)\n"
          + "IF: (EDD_QUEUE_RATIO >= 0.0)\n"
          + "THEN: CTRL_SET_SPRAY_QUOTA(6); CTRL_SET_INGRESS_MODE(0); CTRL_SET_TELEMETRY_INTERVAL(60); CTRL_SET_BACKPRESSURE(true)\n";

    public DtnmaM0eEnergy25Router(Settings s) {
        super(s);
        this.config.managementMode = DtnmaConfig.ManagementMode.M2_AUTONOMIC_SRL;
        this.intelligenceMode = IntelligenceMode.STATIC_RULES;
        this.gossipEnabled = false;
        this.ackFeedbackEnabled = true;
        this.config.epidemicControlFlooding = false;
        this.config.shieldEnergyMin = 0.25;
        this.config.shieldCongestionQueue = 1.01;
        this.config.shieldWarnCritQueue = 1.01;
        loadRulesFromString(ENERGY25_PREINSTALLED_RULES);
        applyBaselinePresetKnobs();
    }

    protected DtnmaM0eEnergy25Router(DtnmaM0eEnergy25Router r) {
        super(r);
    }

    @Override
    public boolean supportsDynamicControlReconfig() {
        return false;
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaM0eEnergy25Router(this);
    }
}
