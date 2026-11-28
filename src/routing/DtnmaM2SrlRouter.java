/*
 * DtnmaM2SrlRouter.java
 * M2-SRL baseline: Autonomic local declarative SRL engine + Safety Shield
 * + manager-pushed remote policy overlays.
 */
package routing;

import core.Settings;

public class DtnmaM2SrlRouter extends DtnmaRouter {

    public DtnmaM2SrlRouter(Settings s) {
        super(s);
        this.config.managementMode = DtnmaConfig.ManagementMode.M2_AUTONOMIC_SRL;
        this.ackFeedbackEnabled = true;
        applyBaselinePresetKnobs();
    }

    protected DtnmaM2SrlRouter(DtnmaM2SrlRouter r) {
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaM2SrlRouter(this);
    }
}
