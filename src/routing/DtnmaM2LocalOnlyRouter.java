/*
 * DtnmaM2LocalOnlyRouter.java
 * Post-freeze exploratory isolation check (M2-LocalOnly): Identical to
 * DtnmaM2SrlRouter (rules_vdtn.txt local SRLs + shield + ACK pruning), but
 * blocks manager-pushed CTRL_UPDATE_* / CTRL_GROUP_* policy mutations so
 * local autonomic adaptation is isolated from hybrid remote updates.
 */
package routing;

import core.Settings;

public class DtnmaM2LocalOnlyRouter extends DtnmaM2SrlRouter {

    public DtnmaM2LocalOnlyRouter(Settings s) {
        super(s);
    }

    protected DtnmaM2LocalOnlyRouter(DtnmaM2LocalOnlyRouter r) {
        super(r);
    }

    @Override
    public boolean supportsDynamicControlReconfig() {
        return false;
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaM2LocalOnlyRouter(this);
    }
}
