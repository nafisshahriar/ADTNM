/*
 * DtnmaRecoveryTimelineReport.java
 * 300s-binned time-series report for RQ3 (Energy Blackout Self-Healing)
 * and Manager-Agent AoI / Downlink convergence trajectories.
 * Computes cohort delivery at done() so each bin reflects true end-to-end delivery.
 */
package report;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import core.DTNHost;
import core.Message;
import core.MessageListener;
import core.SimClock;
import core.UpdateListener;
import routing.DtnmaAgent;
import routing.DtnmaConfig;
import routing.DtnmaManagedRouter;
import routing.DtnmaRouter;
import routing.util.EnergyModel;

public class DtnmaRecoveryTimelineReport extends Report implements UpdateListener, MessageListener {

    private static final double SAMPLE_INTERVAL_SEC = 300.0;
    private static final double ROLLING_WINDOW_SEC = 900.0;
    private static final double HORIZON_SEC = 3600.0;

    private static final class MsgEvent {
        final double createTime;
        final boolean isCrit;
        boolean delivered = false;
        double delivTime = -1.0;

        MsgEvent(double t, boolean c) {
            this.createTime = t;
            this.isCrit = c;
        }
    }

    private static final class Snapshot {
        final double simTime;
        final int aliveNodes;
        final double meanSoc;
        final double shieldSafeFrac;
        final double meanAoi;
        final double v1Frac;

        Snapshot(double t, int alive, double soc, double safe, double aoi, double v1) {
            this.simTime = t;
            this.aliveNodes = alive;
            this.meanSoc = soc;
            this.shieldSafeFrac = safe;
            this.meanAoi = aoi;
            this.v1Frac = v1;
        }
    }

    private double lastSampleTime = -SAMPLE_INTERVAL_SEC;
    private Map<String, MsgEvent> msgMap = new HashMap<String, MsgEvent>();
    private List<MsgEvent> allEvents = new ArrayList<MsgEvent>();
    private List<Snapshot> snapshots = new ArrayList<Snapshot>();
    private Set<Integer> uplinkSources = new HashSet<Integer>();
    private Map<Integer, Double> firstGenTime = new HashMap<Integer, Double>();
    private Map<Integer, Double> latestDelivGenTime = new HashMap<Integer, Double>();
    private Map<Integer, Double> initialEnergyByHost = new HashMap<Integer, Double>();
    private boolean capturedInitEnergy = false;

    public DtnmaRecoveryTimelineReport() {
        super();
        init();
    }

    @Override
    protected void init() {
        super.init();
        this.lastSampleTime = -SAMPLE_INTERVAL_SEC;
        if (this.msgMap == null) this.msgMap = new HashMap<String, MsgEvent>();
        else this.msgMap.clear();
        if (this.allEvents == null) this.allEvents = new ArrayList<MsgEvent>();
        else this.allEvents.clear();
        if (this.snapshots == null) this.snapshots = new ArrayList<Snapshot>();
        else this.snapshots.clear();
        if (this.uplinkSources == null) this.uplinkSources = new HashSet<Integer>();
        else this.uplinkSources.clear();
        if (this.firstGenTime == null) this.firstGenTime = new HashMap<Integer, Double>();
        else this.firstGenTime.clear();
        if (this.latestDelivGenTime == null) this.latestDelivGenTime = new HashMap<Integer, Double>();
        else this.latestDelivGenTime.clear();
        if (this.initialEnergyByHost == null) this.initialEnergyByHost = new HashMap<Integer, Double>();
        else this.initialEnergyByHost.clear();
        this.capturedInitEnergy = false;
    }

    @Override
    public void newMessage(Message m) {
        if (isWarmup()) {
            addWarmupID(m.getId());
            return;
        }
        double now = getSimTime();
        boolean isCrit = DtnmaRouter.isCriticalMessage(m);
        MsgEvent ev = new MsgEvent(now, isCrit);
        this.msgMap.put(m.getId(), ev);
        this.allEvents.add(ev);

        if (DtnmaRouter.isTelemetryOrAlarmMessage(m) && m.getFrom() != null) {
            int src = m.getFrom().getAddress();
            this.uplinkSources.add(src);
            if (!this.firstGenTime.containsKey(src)) {
                this.firstGenTime.put(src, now);
            }
        }
    }

    @Override
    public void messageTransferred(Message m, DTNHost from, DTNHost to, boolean finalTarget) {
        if (isWarmupID(m.getId()) || !finalTarget) {
            return;
        }
        MsgEvent ev = this.msgMap.get(m.getId());
        if (ev != null && !ev.delivered) {
            ev.delivered = true;
            ev.delivTime = getSimTime();
            if (DtnmaRouter.isTelemetryOrAlarmMessage(m) && m.getFrom() != null) {
                int src = m.getFrom().getAddress();
                Double prev = this.latestDelivGenTime.get(src);
                if (prev == null || ev.createTime > prev) {
                    this.latestDelivGenTime.put(src, ev.createTime);
                }
            }
        }
    }

    @Override
    public void messageDeleted(Message m, DTNHost where, boolean dropped) {}

    @Override
    public void messageTransferAborted(Message m, DTNHost from, DTNHost to) {}

    @Override
    public void messageTransferStarted(Message m, DTNHost from, DTNHost to) {}

    @Override
    public void updated(List<DTNHost> hosts) {
        double now = SimClock.getTime();
        if (hosts != null && !this.capturedInitEnergy) {
            boolean foundAny = false;
            for (DTNHost h : hosts) {
                Object eObj = h.getComBus().getProperty(EnergyModel.ENERGY_VALUE_ID);
                if (eObj instanceof Double) {
                    this.initialEnergyByHost.put(h.getAddress(), (Double) eObj);
                    foundAny = true;
                }
            }
            if (foundAny || now > 2.0) {
                this.capturedInitEnergy = true;
            }
        }

        if ((now - this.lastSampleTime) < SAMPLE_INTERVAL_SEC) {
            return;
        }
        this.lastSampleTime = now;

        int alive = 0;
        double socSum = 0.0;
        int energyHosts = 0;
        int dtnmaAlive = 0;
        int safeOrShieldCount = 0;
        int v1Adopted = 0;

        if (hosts != null) {
            for (DTNHost h : hosts) {
                Object eObj = h.getComBus().getProperty(EnergyModel.ENERGY_VALUE_ID);
                boolean isAlive = !(eObj instanceof Double) || ((Double) eObj) > 1e-9;
                if (isAlive) {
                    alive++;
                }
                if (h.getRouter() instanceof DtnmaManagedRouter) {
                    DtnmaManagedRouter mr = (DtnmaManagedRouter) h.getRouter();
                    DtnmaAgent dr = mr.getDtnmaAgent();
                    socSum += mr.getEnergySoc();
                    energyHosts++;
                    if (isAlive && dr != null) {
                        dtnmaAlive++;
                        if (dr.getEffectiveManagementMode() != DtnmaConfig.ManagementMode.M0_STATIC
                                && (dr.isLastShieldIntervened()
                                    || dr.getLastActionIndex() == DtnmaRouter.REGIME_CONGESTED_SAFE
                                    || dr.getCurrentIngressMode() >= DtnmaRouter.INGRESS_THROTTLE_BULK)) {
                            safeOrShieldCount++;
                        }
                        if (dr.getAppliedControlVersion() >= 1) {
                            v1Adopted++;
                        }
                    }
                } else if (eObj instanceof Double) {
                    double curE = (Double) eObj;
                    Double initE = this.initialEnergyByHost.get(h.getAddress());
                    double soc = (initE != null && initE > 0.0)
                            ? Math.max(0.0, Math.min(1.0, curE / initE))
                            : (curE > 0.0 ? 1.0 : 0.0);
                    socSum += soc;
                    energyHosts++;
                }
            }
        }

        double aoiSum = 0.0;
        int aoiCnt = 0;
        for (Integer src : this.uplinkSources) {
            Double lg = this.latestDelivGenTime.get(src);
            Double fg = this.firstGenTime.get(src);
            double ref = (lg != null) ? lg : (fg != null ? fg : now);
            aoiSum += Math.max(0.0, now - ref);
            aoiCnt++;
        }

        double meanSoc = energyHosts > 0 ? socSum / energyHosts : 1.0;
        double safeFrac = dtnmaAlive > 0 ? (double) safeOrShieldCount / dtnmaAlive : 0.0;
        double meanAoi = aoiCnt > 0 ? aoiSum / aoiCnt : 0.0;
        double v1Frac = dtnmaAlive > 0 ? (double) v1Adopted / dtnmaAlive : 0.0;

        this.snapshots.add(new Snapshot(now, alive, meanSoc, safeFrac, meanAoi, v1Frac));
    }

    @Override
    public void done() {
        write("# SimTime\tAliveNodes\tMeanSoC\tShieldOrSafeFrac\tRollingDeliv\tRollingCritDeliv\tMeanAoI\tAdoptedCtrlV1Frac");
        double simEnd = getSimTime();
        for (Snapshot snap : this.snapshots) {
            double t = snap.simTime;
            double winStart = Math.max(0.0, t - ROLLING_WINDOW_SEC);
            int totWin = 0, delWin = 0, critTot = 0, critDel = 0;
            boolean horizonOk = (t + HORIZON_SEC <= simEnd + 1e-6);
            for (MsgEvent ev : this.allEvents) {
                if (ev.createTime >= winStart && ev.createTime <= t) {
                    boolean delH = ev.delivered && (!horizonOk || (ev.delivTime - ev.createTime) <= HORIZON_SEC);
                    totWin++;
                    if (delH) delWin++;
                    if (ev.isCrit) {
                        critTot++;
                        if (delH) critDel++;
                    }
                }
            }
            double rollDel = totWin > 0 ? (double) delWin / totWin : 0.0;
            double rollCritDel = critTot > 0 ? (double) critDel / critTot : 0.0;
            write(String.format(Locale.US, "%.1f\t%d\t%.4f\t%.4f\t%.4f\t%.4f\t%.2f\t%.4f",
                    t, snap.aliveNodes, snap.meanSoc, snap.shieldSafeFrac,
                    rollDel, rollCritDel, snap.meanAoi, snap.v1Frac));
        }
        super.done();
    }
}
