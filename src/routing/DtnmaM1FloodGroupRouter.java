/*
 * DtnmaM1FloodGroupRouter.java
 * Post-freeze exploratory control M1-F-Group: Remote-only control over DTN
 * using epidemic flooding of a single shared group-addressed policy bundle per wave
 * (CTRL_GROUP_*), isolating flooding cost from the 120-unicast-ID artifact in v1.0 M1-F.
 */
package routing;

import core.Settings;

public class DtnmaM1FloodGroupRouter extends DtnmaRouter {

    public DtnmaM1FloodGroupRouter(Settings s) {
        super(s);
        this.config.managementMode = DtnmaConfig.ManagementMode.M1_REMOTE_ONLY;
        this.intelligenceMode = IntelligenceMode.STATIC_RULES;
        this.gossipEnabled = false;
        this.ackFeedbackEnabled = true;
        this.config.epidemicControlFlooding = true;
        applyBaselinePresetKnobs();
    }

    protected DtnmaM1FloodGroupRouter(DtnmaM1FloodGroupRouter r) {
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaM1FloodGroupRouter(this);
    }
}
