/*
 * DtnmaM2MobEnergyOnlyRouter.java
 * Isolated ablation preset of M2_MOBILITY_AWARE: enables energy-drain
 * Time-to-Depletion (TTD) horizon control only (disables ICT buffer scaling
 * and contact-duration replication scaling).
 */
package routing;

import core.Settings;

public class DtnmaM2MobEnergyOnlyRouter extends DtnmaM2MobRouter {

    public DtnmaM2MobEnergyOnlyRouter(Settings s) {
        super(s);
        this.config.mobAblationMode = "ENERGY_ONLY";
    }

    protected DtnmaM2MobEnergyOnlyRouter(DtnmaM2MobEnergyOnlyRouter r) {
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaM2MobEnergyOnlyRouter(this);
    }
}
