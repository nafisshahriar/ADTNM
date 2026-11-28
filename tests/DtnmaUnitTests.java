/*
 * DtnmaUnitTests.java
 * Zero-dependency unit test suite for ADTNM Safety Shield,
 * 16B Contact Beacons, Relay-Time Token Conservation, All-Critical Zero-Overshoot,
 * Cross-Substrate Management, First-Class Mobility EDDs, and M2 Mobility-Aware Coordination.
 */
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.MessageListener;
import core.ModuleCommunicationBus;
import core.MovementListener;
import core.NetworkInterface;
import core.Settings;
import core.SimClock;
import core.SimScenario;
import core.VBRConnection;
import report.DtnmaMetricsReport;
import interfaces.ConnectivityGrid;
import interfaces.SimpleBroadcastInterface;
import movement.MovementModel;
import movement.StationaryMovement;
import routing.CongestionAwareSnWRouter;
import routing.ControlAction;
import routing.DtnmaAgent;
import routing.DtnmaConfig;
import routing.DtnmaContactBeacon;
import routing.DtnmaM0bStaticRouter;
import routing.DtnmaM0eEnergy25Router;
import routing.DtnmaM1RemoteRouter;
import routing.DtnmaM2MobBufferOnlyRouter;
import routing.DtnmaM2MobEnergyOnlyRouter;
import routing.DtnmaM2MobReplicaOnlyRouter;
import routing.DtnmaM2MobRouter;
import routing.DtnmaM2SrlRouter;
import routing.DtnmaManagedRouter;
import routing.DtnmaPolicyProfile;
import routing.DtnmaRouter;
import routing.DtnmaRuleParser;
import routing.DtnmaSafetyShield;
import routing.EddTelemetrySampler;
import routing.ManagedEpidemicRouter;
import routing.ManagedMaxPropRouter;
import routing.ManagedProphetV2Router;
import routing.ManagedSprayAndWaitRouter;
import routing.PredicateExpression;
import routing.StateBasedRule;
import routing.util.EnergyModel;

public class DtnmaUnitTests {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean cond, String name, String detail) {
        if (cond) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.err.println("[FAIL] " + name + " -> " + detail);
        }
    }

    private static class StubSampler extends EddTelemetrySampler {
        double q = 0.0;
        double critQ = 0.0;
        double drift = 0.0;
        double dens = 0.0;
        double soc = 1.0;

        StubSampler() {
            super(null, null);
        }

        @Override
        public double getEddValue(String eddName) {
            if (EDD_QUEUE_RATIO.equals(eddName)) return q;
            if (EDD_CRIT_QUEUE_RATIO.equals(eddName)) return critQ;
            if (EDD_QUEUE_DRIFT.equals(eddName)) return drift;
            if (EDD_NODE_DENSITY.equals(eddName)) return dens;
            if (EDD_ENERGY_SOC.equals(eddName)) return soc;
            return 0.0;
        }
    }

    private static void test1SafetyShieldInvariantsAndLiveness() {
        DtnmaConfig cfg = new DtnmaConfig();
        DtnmaSafetyShield shield = new DtnmaSafetyShield(cfg);
        StubSampler sampler = new StubSampler();

        PredicateExpression pCrit = new PredicateExpression(true);
        pCrit.addClause(EddTelemetrySampler.EDD_QUEUE_RATIO, PredicateExpression.Operator.GREATER_EQUAL, 0.86);
        StateBasedRule critRule = new StateBasedRule("ARI://DTNMA/SRL/TEST_CRIT", "Test critical rule", 100, pCrit);
        critRule.addAction(ControlAction.setSprayQuota(2));
        critRule.addAction(ControlAction.setIngressMode(1));
        List<StateBasedRule> rules = Collections.singletonList(critRule);

        // S1 Critical saturation (CritQ >= 0.50) OR active surge (Q>=0.94 && dQ>=0.015) -> only {0}
        // Whereas steady full evictable bulk cache (Q=0.95, CritQ=0.05, dQ=0.0) keeps P4 admissible!
        sampler.q = 0.95; sampler.critQ = 0.55; sampler.drift = 0.0; sampler.dens = 1.0; sampler.soc = 1.0;
        boolean[] mCrit = shield.computeSafeActionMask(sampler, rules, new StringBuilder());
        boolean onlyZeroCrit = mCrit[0];
        for (int a = 1; a < DtnmaPolicyProfile.NUM_ACTIONS; a++) {
            onlyZeroCrit = onlyZeroCrit && !mCrit[a];
        }
        DtnmaSafetyShield.ShieldDecision dCrit = shield.enforce(7, 0, sampler, rules);

        sampler.q = 0.95; sampler.critQ = 0.05; sampler.drift = 0.0;
        boolean[] mSteadyBulk = shield.computeSafeActionMask(sampler, Collections.<StateBasedRule>emptyList(), new StringBuilder());
        sampler.drift = 0.02;
        boolean[] mSurge = shield.computeSafeActionMask(sampler, Collections.<StateBasedRule>emptyList(), new StringBuilder());
        boolean onlyZeroSurge = mSurge[0] && !mSurge[1] && !mSurge[4];
        check(onlyZeroCrit && dCrit.getExecutedAction() == 0 && dCrit.isIntervened()
                && mSteadyBulk[4] && onlyZeroSurge,
                "Test1a: Shield S1 distinguishes CritQ/active-surge {0} from steady full bulk cache (P4 admissible)",
                "crit=" + Arrays.toString(mCrit) + " steady=" + Arrays.toString(mSteadyBulk) + " surge=" + Arrays.toString(mSurge));

        // S2 Low Energy SoC < 0.20 -> restrict to {0, 1} with mask[0]=true
        sampler.q = 0.30; sampler.critQ = 0.0; sampler.drift = 0.0; sampler.soc = 0.15;
        boolean[] mEnergy = shield.computeSafeActionMask(sampler, rules, new StringBuilder());
        check(mEnergy[0] && mEnergy[1] && !mEnergy[2] && !mEnergy[7],
                "Test1b: Shield S2 energy SoC<0.20 survival failsafe restricts to {0,1}",
                "mask=" + Arrays.toString(mEnergy));

        // S3 Dense Storm Guard (Q >= 0.78 && Density >= 5.0) -> blocks profiles 6,7
        sampler.q = 0.80; sampler.soc = 1.0; sampler.dens = 6.0;
        boolean[] mStorm = shield.computeSafeActionMask(sampler, rules, new StringBuilder());
        check(mStorm[0] && mStorm[4] && !mStorm[6] && !mStorm[7],
                "Test1c: Shield S3 dense storm guard blocks high-spray {6,7}",
                "mask=" + Arrays.toString(mStorm));

        // S4 Critical Declarative Rule (Q=0.87 triggers maxQuota=2 -> blocks profiles >= 2)
        sampler.q = 0.87; sampler.dens = 2.0;
        boolean[] mRule = shield.computeSafeActionMask(sampler, rules, new StringBuilder());
        check(mRule[0] && mRule[1] && !mRule[2] && !mRule[4],
                "Test1d: Shield S4 priority-100 rule enforces quota<=2 while keeping liveness",
                "mask=" + Arrays.toString(mRule));
    }

    private static void test5BeaconQuantizationAndWireSizes() {
        boolean sizesOk = (DtnmaContactBeacon.BEACON_SIZE_BYTES == 16)
                && (DtnmaContactBeacon.ACK_ID_BYTES == 4);

        DtnmaContactBeacon b = new DtnmaContactBeacon(42, 1234.5, 0.731, -0.125, 0.855, 12.345, 2, 7);
        boolean roundTripOk = b.getHostAddress() == 42
                && Math.abs(b.getTimestamp() - 1234.5) < 0.01
                && Math.abs(b.getQueueRatio() - 0.731) < 1e-4
                && Math.abs(b.getQueueDriftEwma() - (-0.125)) < 1e-4
                && Math.abs(b.getEnergySoc() - 0.855) < 1e-4
                && Math.abs(b.getNodeSpeed() - 12.345) <= 0.001
                && b.getMobilityClass() == 2
                && b.getActiveDegree() == 7;

        check(sizesOk && roundTripOk,
                "Test5: ContactBeacon 16B wire quantization & 4B ACK ID size",
                "sizesOk=" + sizesOk + " roundTripOk=" + roundTripOk);
    }

    private static void test6BinaryTokenConservationAtRelay() {
        boolean ok = true;
        String err = "";
        for (int copies = 2; copies <= 12; copies++) {
            for (int quota = 1; quota <= 12; quota++) {
                int bulkSend = DtnmaRouter.computeRelaySplit(copies, quota, false);
                int bulkRemain = copies - bulkSend;
                if (quota == 1 && bulkSend != 0) {
                    ok = false; err = "bulkSend!=0 at quota=1";
                }
                if (quota >= 2 && (bulkSend < 1 || bulkRemain < 1 || bulkSend + bulkRemain != copies)) {
                    ok = false; err = "conservation failed at copies=" + copies + " quota=" + quota;
                }

                int critSend = DtnmaRouter.computeRelaySplit(copies, quota, true);
                int critRemain = copies - critSend;
                if (critSend != copies / 2 || critSend + critRemain != copies || critRemain < 1) {
                    ok = false; err = "critical split failed at copies=" + copies;
                }
            }
        }
        check(ok, "Test6: Relay-time adaptive split enforces exact Binary Token Conservation", err);
    }

    private static void test7AllCriticalFullBufferZeroOvershoot() {
        String cfgText =
                "Scenario.endTime = 100\n" +
                "MovementModel.worldSize = 100, 100\n" +
                "Group.bufferSize = 40000\n" +
                "Group.msgTtl = 300\n" +
                "Group.nrofHosts = 2\n" +
                "Group.speed = 1.0, 1.0\n" +
                "Group.waitTime = 0, 0\n" +
                "Group.nodeLocation = 0, 0\n";
        Settings.initFromStream(new ByteArrayInputStream(cfgText.getBytes(StandardCharsets.UTF_8)));
        Settings groupSettings = new Settings("Group");
        MovementModel mm = new StationaryMovement(groupSettings);
        DtnmaRouter proto = new DtnmaRouter(groupSettings);
        List<NetworkInterface> ifaces = new ArrayList<NetworkInterface>();

        DTNHost h0 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "n", ifaces, new ModuleCommunicationBus(), mm, proto);
        DTNHost h1 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "n", ifaces, new ModuleCommunicationBus(), mm, proto);

        DtnmaRouter r0 = (DtnmaRouter) h0.getRouter();

        Message c1 = new Message(h0, h1, "CRITICAL_FAULT_1", 20000);
        Message c2 = new Message(h0, h1, "CRITICAL_FAULT_2", 20000);
        boolean ok1 = r0.createNewMessage(c1);
        boolean ok2 = r0.createNewMessage(c2);
        // Simulate sole-copy critical messages (copies == 1)
        c1.updateProperty(DtnmaRouter.MSG_COPIES_PROPERTY, 1);
        c2.updateProperty(DtnmaRouter.MSG_COPIES_PROPERTY, 1);

        // Attempt bulk creation & critical creation on full 40k sole-copy critical buffer
        Message b1 = new Message(h0, h1, "SCIENCE_DATA_1", 10000);
        Message c3 = new Message(h0, h1, "CRITICAL_FAULT_3", 15000);
        boolean bulkBlocked = !r0.createNewMessage(b1);
        boolean critBlockedNoOvershoot = !r0.createNewMessage(c3);
        double occAfterSoleBlock = r0.getBufferOccupancyRatio();

        boolean soleProtected = r0.hasMessage("CRITICAL_FAULT_1") && r0.hasMessage("CRITICAL_FAULT_2");

        // Even if c2 holds copies = 4 spray tokens, it is still un-ACKed and MUST stay protected (admitted bundles never evicted)
        c2.updateProperty(DtnmaRouter.MSG_COPIES_PROPERTY, 4);
        Message c4 = new Message(h0, h1, "CRITICAL_FAULT_4", 15000);
        boolean unackedBlocked = !r0.createNewMessage(c4);
        double occAfterUnacked = r0.getBufferOccupancyRatio();

        boolean passedAll = ok1 && ok2 && bulkBlocked && critBlockedNoOvershoot
                && soleProtected && occAfterSoleBlock <= 1.000001
                && unackedBlocked && occAfterUnacked <= 1.000001
                && r0.hasMessage("CRITICAL_FAULT_1") && r0.hasMessage("CRITICAL_FAULT_2");

        check(passedAll,
                "Test7: All-Critical Full-Buffer enforces occ <= 1.0 & never evicts un-ACKed critical copies",
                String.format("occ1=%.4f occ2=%.4f ok1=%s ok2=%s blkB=%s blkC=%s unackBlk=%s",
                        occAfterSoleBlock, occAfterUnacked, ok1, ok2, bulkBlocked, critBlockedNoOvershoot, unackedBlocked));

        // Test8: localCreationRejected counter, ManagedSprayAndWaitRouter, & DtnmaM2MobRouter
        boolean rejCountOk = (r0.getLocalCreationRejected() == 3);

        String cfgSnW = cfgText + "SprayAndWaitRouter.nrofCopies = 6\nSprayAndWaitRouter.binaryMode = true\n";
        Settings.initFromStream(new ByteArrayInputStream(cfgSnW.getBytes(StandardCharsets.UTF_8)));
        Settings grpSnW = new Settings("Group");
        MovementModel mm2 = new StationaryMovement(grpSnW);
        ManagedSprayAndWaitRouter snwProto = new ManagedSprayAndWaitRouter(grpSnW);
        DTNHost hs0 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "p", ifaces, new ModuleCommunicationBus(), mm2, snwProto);
        DTNHost hs1 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "p", ifaces, new ModuleCommunicationBus(), mm2, snwProto);
        ManagedSprayAndWaitRouter pSnW = (ManagedSprayAndWaitRouter) hs0.getRouter();

        Message psCrit1 = new Message(hs0, hs1, "CRITICAL_FAULT_P1", 20000);
        Message psCrit2 = new Message(hs0, hs1, "CRITICAL_FAULT_P2", 20000);
        boolean psOk1 = pSnW.createNewMessage(psCrit1);
        boolean psOk2 = pSnW.createNewMessage(psCrit2);
        psCrit1.updateProperty(ManagedSprayAndWaitRouter.MSG_COUNT_PROPERTY, 1);
        psCrit2.updateProperty(ManagedSprayAndWaitRouter.MSG_COUNT_PROPERTY, 1);
        Message psBulk = new Message(hs0, hs1, "SCIENCE_DATA_P1", 10000);
        boolean psBlkBulk = !pSnW.createNewMessage(psBulk);
        boolean psOccOk = pSnW.getBufferOccupancyRatio() <= 1.000001
                && pSnW.getDtnmaAgent().getLocalCreationRejected() == 1
                && pSnW.hasMessage("CRITICAL_FAULT_P1")
                && pSnW.hasMessage("CRITICAL_FAULT_P2");

        DtnmaM2MobRouter mobProto = new DtnmaM2MobRouter(grpSnW);
        boolean mobFlagsOk = mobProto.getDtnmaAgent().getEffectiveManagementMode() == DtnmaConfig.ManagementMode.M2_MOBILITY_AWARE
                && mobProto.isAckFeedbackEnabled();

        check(rejCountOk && psOk1 && psOk2 && psBlkBulk && psOccOk && mobFlagsOk,
                "Test8: localCreationRejected audit, ManagedSprayAndWait protection & DtnmaM2MobRouter flags",
                String.format("rej=%d psOk=(%s,%s,%s,%s) flags=%s",
                        r0.getLocalCreationRejected(), psOk1, psOk2, psBlkBulk, psOccOk, mobFlagsOk));
    }

    private static void test9ProtectedFifoEvictionAndCriticalOccupancy() {
        String cfgText =
                "Scenario.endTime = 100\n" +
                "MovementModel.worldSize = 100, 100\n" +
                "Group.bufferSize = 40000\n" +
                "Group.msgTtl = 300\n" +
                "Group.nrofHosts = 2\n" +
                "Group.speed = 1.0, 1.0\n" +
                "Group.waitTime = 0, 0\n" +
                "Group.nodeLocation = 0, 0\n";
        Settings.initFromStream(new ByteArrayInputStream(cfgText.getBytes(StandardCharsets.UTF_8)));
        Settings groupSettings = new Settings("Group");
        MovementModel mm = new StationaryMovement(groupSettings);
        DtnmaRouter proto = new DtnmaRouter(groupSettings);
        List<NetworkInterface> ifaces = new ArrayList<NetworkInterface>();

        DTNHost h0 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "n", ifaces, new ModuleCommunicationBus(), mm, proto);
        DTNHost h1 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "n", ifaces, new ModuleCommunicationBus(), mm, proto);
        DtnmaRouter r0 = (DtnmaRouter) h0.getRouter();

        Message crit = new Message(h0, h1, "CRITICAL_FAULT_PFIFO", 20000);
        crit.setReceiveTime(1.0);
        boolean okCrit = r0.createNewMessage(crit);
        double critOccHalf = r0.getCriticalOccupancyRatio();

        Message bOld = new Message(h0, h1, "SCIENCE_DATA_OLD", 10000);
        bOld.setReceiveTime(5.0);
        boolean okOld = r0.createNewMessage(bOld);

        Message bNew = new Message(h0, h1, "SCIENCE_DATA_NEW", 10000);
        bNew.setReceiveTime(10.0);
        boolean okNew = r0.createNewMessage(bNew);
        bNew.updateProperty(DtnmaRouter.MSG_COPIES_PROPERTY, 1);

        Message bThird = new Message(h0, h1, "SCIENCE_DATA_THIRD", 10000);
        bThird.setReceiveTime(15.0);
        boolean okThird = r0.createNewMessage(bThird);

        boolean critOccOk = Math.abs(critOccHalf - 0.50) < 1e-6
                && Math.abs(r0.getCriticalOccupancyRatio() - 0.50) < 1e-6;
        boolean fifoOk = okCrit && okOld && okNew && okThird
                && r0.hasMessage("CRITICAL_FAULT_PFIFO")
                && !r0.hasMessage("SCIENCE_DATA_OLD")
                && r0.hasMessage("SCIENCE_DATA_NEW")
                && r0.hasMessage("SCIENCE_DATA_THIRD")
                && r0.getBufferOccupancyRatio() <= 1.000001;

        check(critOccOk && fifoOk,
                "Test9: Protected FIFO evicts oldest bulk before copies=1 while protecting critical & reporting critical occupancy",
                String.format("critOccHalf=%.4f fifoOk=%s hasCrit=%s hasOld=%s hasNew=%s hasThird=%s occ=%.4f",
                        critOccHalf, fifoOk, r0.hasMessage("CRITICAL_FAULT_PFIFO"),
                        r0.hasMessage("SCIENCE_DATA_OLD"), r0.hasMessage("SCIENCE_DATA_NEW"),
                        r0.hasMessage("SCIENCE_DATA_THIRD"), r0.getBufferOccupancyRatio()));
    }

    private static void test11ComponentLadderAndDownlinkControl() {
        String cfgText =
                "Scenario.endTime = 100\n" +
                "MovementModel.worldSize = 100, 100\n" +
                "Group.bufferSize = 100000\n" +
                "Group.msgTtl = 300\n" +
                "Group.nrofHosts = 2\n" +
                "Group.speed = 1.0, 1.0\n" +
                "Group.waitTime = 0, 0\n" +
                "Group.nodeLocation = 0, 0\n" +
                "DtnmaRouter.staticQuota = 6\n";
        Settings.initFromStream(new ByteArrayInputStream(cfgText.getBytes(StandardCharsets.UTF_8)));
        Settings grp = new Settings("Group");
        MovementModel mm = new StationaryMovement(grp);
        List<NetworkInterface> ifaces = new ArrayList<NetworkInterface>();

        DtnmaM2SrlRouter srlProto = new DtnmaM2SrlRouter(grp);
        DTNHost hFix = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "f", ifaces, new ModuleCommunicationBus(), mm, srlProto);
        DtnmaM2SrlRouter rFix = (DtnmaM2SrlRouter) hFix.getRouter();
        rFix.update();
        boolean fixedOk = (rFix.getDtnmaAgent().getEffectiveManagementMode() == DtnmaConfig.ManagementMode.M2_AUTONOMIC_SRL)
                && rFix.isAckFeedbackEnabled()
                && rFix.getCurrentSprayQuota() == 6
                && rFix.getDecisionCpuCount() >= 1;

        DtnmaM0bStaticRouter statProto = new DtnmaM0bStaticRouter(grp);
        DTNHost hL6 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "l6", ifaces, new ModuleCommunicationBus(), mm, statProto);
        DtnmaM0bStaticRouter rL6 = (DtnmaM0bStaticRouter) hL6.getRouter();
        rL6.update();

        boolean m0bOk = (rL6.getCurrentSprayQuota() == 6)
                && (rL6.getDtnmaAgent().getEffectiveManagementMode() == DtnmaConfig.ManagementMode.M0_STATIC);

        boolean prefixOk = DtnmaRouter.isCriticalMessage("CTRL_UPDATE_V1_1")
                && DtnmaRouter.isCriticalMessage("ALARM_1")
                && DtnmaRouter.isCriticalMessage("TELEM_CRIT_1")
                && DtnmaRouter.isDownlinkControlMessage(new Message(hFix, hL6, "CTRL_UPDATE_V1_1", 1024))
                && !DtnmaRouter.isTelemetryOrAlarmMessage(new Message(hFix, hL6, "CTRL_UPDATE_V1_1", 1024))
                && DtnmaRouter.isTelemetryOrAlarmMessage(new Message(hFix, hL6, "TELEM_CRIT_1", 1024));

        check(fixedOk && m0bOk && prefixOk,
                "Test11: M2-SRL CPU counter, M0b L=6 preset & CTRL/TELEM classification",
                String.format("fixedOk=%s m0bQuota=%d prefixOk=%s",
                        fixedOk, rL6.getCurrentSprayQuota(), prefixOk));
    }

    private static void test12DownlinkInBandControlAdoption() {
        String cfgText =
                "Scenario.endTime = 200\n" +
                "MovementModel.worldSize = 100, 100\n" +
                "bt.transmitSpeed = 250000\n" +
                "bt.transmitRange = 100\n" +
                "Group.bufferSize = 500000\n" +
                "Group.msgTtl = 300\n" +
                "Group.nrofHosts = 2\n" +
                "Group.speed = 1.0, 1.0\n" +
                "Group.waitTime = 0, 0\n" +
                "Group.nodeLocation = 0, 0\n";
        Settings.initFromStream(new ByteArrayInputStream(cfgText.getBytes(StandardCharsets.UTF_8)));
        SimClock.reset();
        Settings grp = new Settings("Group");
        Settings ifaceSettings = new Settings("bt");
        SimpleBroadcastInterface ifaceProto = new SimpleBroadcastInterface(ifaceSettings);
        ifaceProto.setGroupSettings(grp);
        MovementModel mm = new StationaryMovement(grp);
        DtnmaRouter proto = new DtnmaRouter(grp);

        DTNHost hMgr = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "m", Collections.<NetworkInterface>singletonList(ifaceProto), new ModuleCommunicationBus(), mm, proto);
        DTNHost hAgt = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "a", Collections.<NetworkInterface>singletonList(ifaceProto), new ModuleCommunicationBus(), mm, proto);

        DtnmaRouter rMgr = (DtnmaRouter) hMgr.getRouter();
        DtnmaRouter rAgt = (DtnmaRouter) hAgt.getRouter();

        hMgr.forceConnection(hAgt, null, true);

        Message cmdV1 = new Message(hMgr, hAgt, "CTRL_UPDATE_V1_1", 4096);
        rMgr.createNewMessage(cmdV1);
        for (int step = 0; step < 30; step++) {
            SimClock.getInstance().advance(0.1);
            hMgr.update(false);
            hAgt.update(false);
        }
        boolean v1Adopted = (rAgt.getAppliedControlVersion() == 1)
                && (rAgt.getConfig().shieldEnergyMin >= 0.25)
                && rAgt.getRuleDatabase().stream().anyMatch(r -> "ARI://DTNMA/SRL/DOWNLINK_V1_ENERGY_GUARD".equals(r.getAri()));

        Message cmdV2 = new Message(hMgr, hAgt, "CTRL_UPDATE_V2_1", 4096);
        rMgr.createNewMessage(cmdV2);
        for (int step = 0; step < 30; step++) {
            SimClock.getInstance().advance(0.1);
            hMgr.update(false);
            hAgt.update(false);
        }
        boolean v2Adopted = (rAgt.getAppliedControlVersion() == 2)
                && (Math.abs(rAgt.getConfig().shieldEnergyMin - 0.20) < 1e-6)
                && rAgt.getRuleDatabase().stream().noneMatch(r -> "ARI://DTNMA/SRL/DOWNLINK_V1_ENERGY_GUARD".equals(r.getAri()));

        check(v1Adopted && v2Adopted,
                "Test12: In-band RFC 9675 Downlink CTRL_UPDATE V1/V2 delivery triggers live shield & SRL reconfig",
                String.format("v1Adopted=%s (ver=%d,minE=%.2f) v2Adopted=%s (ver=%d,minE=%.2f)",
                        v1Adopted, rAgt.getAppliedControlVersion(), rAgt.getConfig().shieldEnergyMin,
                        v2Adopted, rAgt.getAppliedControlVersion(), rAgt.getConfig().shieldEnergyMin));
    }

    private static void test13ComposedStockRoutersProtection() {
        String cfgText =
                "Scenario.endTime = 200\n" +
                "MovementModel.worldSize = 100, 100\n" +
                "bt.transmitSpeed = 250000\n" +
                "bt.transmitRange = 100\n" +
                "Group.bufferSize = 50000\n" +
                "Group.msgTtl = 300\n" +
                "Group.nrofHosts = 8\n" +
                "Group.speed = 1.0, 1.0\n" +
                "Group.waitTime = 0, 0\n" +
                "Group.nodeLocation = 0, 0\n" +
                "SprayAndWaitRouter.nrofCopies = 6\n" +
                "SprayAndWaitRouter.binaryMode = true\n" +
                "ProphetV2Router.secondsInTimeUnit = 30\n";
        Settings.initFromStream(new ByteArrayInputStream(cfgText.getBytes(StandardCharsets.UTF_8)));
        SimClock.reset();
        Settings grp = new Settings("Group");
        Settings ifaceSettings = new Settings("bt");
        SimpleBroadcastInterface ifaceProto = new SimpleBroadcastInterface(ifaceSettings);
        ifaceProto.setGroupSettings(grp);
        MovementModel mm = new StationaryMovement(grp);

        DtnmaManagedRouter[] protos = new DtnmaManagedRouter[] {
                new ManagedSprayAndWaitRouter(grp),
                new ManagedProphetV2Router(grp),
                new ManagedMaxPropRouter(grp),
                new ManagedEpidemicRouter(grp)
        };

        boolean allProtected = true;
        StringBuilder failDetail = new StringBuilder();
        for (int i = 0; i < protos.length; i++) {
            routing.MessageRouter proto = protos[i].getManagedActiveRouter();
            DTNHost h = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                    "w" + i, Collections.<NetworkInterface>singletonList(ifaceProto), new ModuleCommunicationBus(), mm, proto);
            DTNHost dst = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                    "d" + i, Collections.<NetworkInterface>singletonList(ifaceProto), new ModuleCommunicationBus(), mm, proto);
            DtnmaManagedRouter mr = (DtnmaManagedRouter) h.getRouter();
            DtnmaAgent ag = mr.getDtnmaAgent();

            // Fill 50KB buffer with 20KB un-ACKed critical + bulk, forcing bulk eviction
            mr.getManagedActiveRouter().createNewMessage(new Message(h, dst, "CRITICAL_FAULT_1_" + i, 20000));
            mr.getManagedActiveRouter().createNewMessage(new Message(h, dst, "BULK_1_" + i, 20000));
            mr.getManagedActiveRouter().createNewMessage(new Message(h, dst, "BULK_2_" + i, 20000));
            // Fill remaining space with critical to saturate
            mr.getManagedActiveRouter().createNewMessage(new Message(h, dst, "CRITICAL_FAULT_2_" + i, 25000));
            // Next critical (20KB) cannot fit even after dropping all bulk -> must reject cleanly and count criticalCreationRejected
            boolean extraCrit = mr.getManagedActiveRouter().createNewMessage(new Message(h, dst, "CRITICAL_FAULT_3_" + i, 20000));
            boolean critPreserved = mr.hasManagedMessage("CRITICAL_FAULT_1_" + i) && mr.hasManagedMessage("CRITICAL_FAULT_2_" + i);
            boolean occOk = mr.getBufferOccupancyRatio() <= 1.0 + 1e-9;
            boolean critRejTracked = !extraCrit && ag.getCriticalCreationRejected() >= 1;
            if (!critPreserved || !occOk || !critRejTracked) {
                allProtected = false;
                failDetail.append(mr.getClass().getSimpleName())
                          .append("{critPreserved=").append(critPreserved)
                          .append(",occ=").append(mr.getBufferOccupancyRatio())
                          .append(",critRej=").append(ag.getCriticalCreationRejected()).append("} ");
            }
        }
        check(allProtected,
                "Test13: Composed Managed{SnW,PRoPHETv2,MaxProp,Epidemic} protect un-ACKed critical & track split rejections",
                failDetail.toString());
    }

    private static void test15ManagementParadigmsM0M1M2() {
        String baseCfg =
                "Scenario.endTime = 200\n" +
                "MovementModel.worldSize = 100, 100\n" +
                "bt.transmitSpeed = 250000\n" +
                "bt.transmitRange = 100\n" +
                "Group.bufferSize = 100000\n" +
                "Group.msgTtl = 300\n" +
                "Group.nrofHosts = 5\n" +
                "Group.speed = 1.0, 1.0\n" +
                "Group.waitTime = 0, 0\n" +
                "Group.nodeLocation = 0, 0\n" +
                "DtnmaRouter.staticQuota = 6\n";
        Settings.initFromStream(new ByteArrayInputStream((baseCfg + "DtnmaRouter.managementMode = M0_STATIC\n").getBytes(StandardCharsets.UTF_8)));
        SimClock.reset();
        Settings grp0 = new Settings("Group");
        SimpleBroadcastInterface if0 = new SimpleBroadcastInterface(new Settings("bt"));
        if0.setGroupSettings(grp0);
        MovementModel mm0 = new StationaryMovement(grp0);
        DtnmaRouter pM0 = new DtnmaRouter(grp0);

        Settings.initFromStream(new ByteArrayInputStream((baseCfg + "DtnmaRouter.managementMode = M1_REMOTE_ONLY\n").getBytes(StandardCharsets.UTF_8)));
        Settings grp1 = new Settings("Group");
        DtnmaRouter pM1 = new DtnmaRouter(grp1);

        Settings.initFromStream(new ByteArrayInputStream((baseCfg + "DtnmaRouter.managementMode = M2_AUTONOMIC_SRL\n").getBytes(StandardCharsets.UTF_8)));
        Settings grp2 = new Settings("Group");
        DtnmaRouter pM2 = new DtnmaRouter(grp2);

        DTNHost hMgr = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "mgr", Collections.<NetworkInterface>singletonList(if0), new ModuleCommunicationBus(), mm0, pM2);
        DTNHost hM0 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "m0", Collections.<NetworkInterface>singletonList(if0), new ModuleCommunicationBus(), mm0, pM0);
        DTNHost hM1 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "m1", Collections.<NetworkInterface>singletonList(if0), new ModuleCommunicationBus(), mm0, pM1);
        DTNHost hM2 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "m2", Collections.<NetworkInterface>singletonList(if0), new ModuleCommunicationBus(), mm0, pM2);

        DtnmaRouter rM0 = (DtnmaRouter) hM0.getRouter();
        DtnmaRouter rM1 = (DtnmaRouter) hM1.getRouter();
        DtnmaRouter rM2 = (DtnmaRouter) hM2.getRouter();

        // Saturate buffers >= 96% -> only M2 should react locally without remote update
        for (DtnmaRouter rr : Arrays.asList(rM0, rM1, rM2)) {
            rr.createNewMessage(new Message(rr.getManagedHost(), hMgr, "CRITICAL_SAT", 96000));
        }
        SimClock.getInstance().advance(2.0);
        hM0.update(false);
        hM1.update(false);
        hM2.update(false);

        boolean localSplitOk = (rM0.getCurrentSprayQuota() == 6)
                && (rM1.getCurrentSprayQuota() == 6)
                && (rM2.getCurrentSprayQuota() <= 2);

        // Remote CTRL_UPDATE_THROTTLE arrives -> mutates M1, ignored by M0
        hMgr.forceConnection(hM1, null, true);
        hMgr.forceConnection(hM0, null, true);
        Message mThrot1 = new Message(hMgr, hM1, "CTRL_UPDATE_THROTTLE_1", 1024);
        Message mThrot0 = new Message(hMgr, hM0, "CTRL_UPDATE_THROTTLE_0", 1024);
        rM1.getDtnmaAgent().applyDownlinkControlBundle(mThrot1);
        rM0.getDtnmaAgent().applyDownlinkControlBundle(mThrot0);

        boolean throtOk = (rM1.getCurrentSprayQuota() <= 2)
                && (rM1.getDtnmaAgent().getCurrentIngressMode() >= 1)
                && (rM0.getCurrentSprayQuota() == 6);

        // Later CTRL_UPDATE_NOMINAL restores M1 back to nominal L=6
        SimClock.getInstance().advance(10.0);
        Message mNom1 = new Message(hMgr, hM1, "CTRL_UPDATE_NOMINAL_1", 1024);
        rM1.getDtnmaAgent().applyDownlinkControlBundle(mNom1);
        boolean nomOk = (rM1.getCurrentSprayQuota() == 6) && (rM1.getDtnmaAgent().getCurrentIngressMode() == 0);

        check(localSplitOk && throtOk && nomOk,
                "Test15: M0 vs M1 remote-only vs M2 local SRL split & repeated CTRL_UPDATE",
                String.format("localSplit=%s throt=%s nom=%s (M1q=%d)",
                        localSplitOk, throtOk, nomOk, rM1.getCurrentSprayQuota()));
    }

    private static void test18InterventionTiersPresetsAndCatchAllRule() {
        // 1. Verify updated rules_vdtn.txt catch-all release on steady Q=0.90, dQ=0.0, CritQ=0.05
        String rulePath = new File("scenarios/rules_vdtn.txt").isFile()
                ? "scenarios/rules_vdtn.txt" : "../ADTNM/scenarios/rules_vdtn.txt";
        boolean rulesOk = false;
        String ruleInfo = "missing";
        try {
            List<StateBasedRule> rules = DtnmaRuleParser.parseFile(rulePath);
            StubSampler s = new StubSampler();
            s.q = 0.90; s.critQ = 0.05; s.drift = 0.0; s.soc = 0.80; s.dens = 2.0;
            StateBasedRule firedSteady = null;
            for (StateBasedRule r : rules) {
                if (r.isTriggered(s)) { firedSteady = r; break; }
            }
            s.q = 0.90; s.drift = 0.010;
            StateBasedRule firedSurge = null;
            for (StateBasedRule r : rules) {
                if (r.isTriggered(s)) { firedSurge = r; break; }
            }
            s.q = 0.40; s.drift = 0.0; s.soc = 0.15;
            StateBasedRule firedLowSoc = null;
            for (StateBasedRule r : rules) {
                if (r.isTriggered(s)) { firedLowSoc = r; break; }
            }
            rulesOk = (firedSteady != null && "ARI://DTNMA/SRL/VDTN_URBAN_NOMINAL".equals(firedSteady.getAri()))
                    && (firedSurge != null && firedSurge.getPriority() == 80)
                    && (firedLowSoc != null && firedLowSoc.getPriority() == 100);
            ruleInfo = String.format("steady=%s surge=%s lowSoc=%s",
                    firedSteady != null ? firedSteady.getAri() : "null",
                    firedSurge != null ? firedSurge.getAri() : "null",
                    firedLowSoc != null ? firedLowSoc.getAri() : "null");
        } catch (Exception e) {
            ruleInfo = e.getMessage();
        }

        // 2. Verify M0b/M1/M2-SRL/M2-Mob presets, densityAdaptiveBeacons scaling, & InterventionTier 1 vs Tier 3
        String baseCfg =
                "Scenario.endTime = 200\n" +
                "MovementModel.worldSize = 100, 100\n" +
                "bt.transmitSpeed = 250000\n" +
                "bt.transmitRange = 100\n" +
                "Group.bufferSize = 500000\n" +
                "Group.msgTtl = 300\n" +
                "Group.nrofHosts = 5\n" +
                "Group.speed = 1.0, 1.0\n" +
                "Group.waitTime = 0, 0\n" +
                "Group.nodeLocation = 0, 0\n" +
                "SprayAndWaitRouter.nrofCopies = 6\n" +
                "SprayAndWaitRouter.binaryMode = true\n" +
                "DtnmaRouter.staticQuota = 6\n" +
                "DtnmaRouter.beaconRefreshInterval = 5.0\n" +
                "DtnmaRouter.densityAdaptiveBeacons = true\n";
        Settings.initFromStream(new ByteArrayInputStream(baseCfg.getBytes(StandardCharsets.UTF_8)));
        SimClock.reset();
        Settings grp = new Settings("Group");
        SimpleBroadcastInterface ifc = new SimpleBroadcastInterface(new Settings("bt"));
        ifc.setGroupSettings(grp);
        MovementModel mm = new StationaryMovement(grp);

        DtnmaM0bStaticRouter p0b = new DtnmaM0bStaticRouter(grp);
        DtnmaM1RemoteRouter p1 = new DtnmaM1RemoteRouter(grp);
        DtnmaM2SrlRouter p2 = new DtnmaM2SrlRouter(grp);
        DtnmaM2MobRouter pmob = new DtnmaM2MobRouter(grp);

        DTNHost h0b = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "m0b", Collections.<NetworkInterface>singletonList(ifc), new ModuleCommunicationBus(), mm, p0b);
        DTNHost h1 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "m1", Collections.<NetworkInterface>singletonList(ifc), new ModuleCommunicationBus(), mm, p1);
        DTNHost h2 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "m2", Collections.<NetworkInterface>singletonList(ifc), new ModuleCommunicationBus(), mm, p2);
        DTNHost hmob = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "mob", Collections.<NetworkInterface>singletonList(ifc), new ModuleCommunicationBus(), mm, pmob);

        boolean presetsOk = ((DtnmaRouter) h0b.getRouter()).getDtnmaAgent().getEffectiveManagementMode() == DtnmaConfig.ManagementMode.M0_STATIC
                && ((DtnmaRouter) h1.getRouter()).getDtnmaAgent().getEffectiveManagementMode() == DtnmaConfig.ManagementMode.M1_REMOTE_ONLY
                && ((DtnmaRouter) h2.getRouter()).getDtnmaAgent().getEffectiveManagementMode() == DtnmaConfig.ManagementMode.M2_AUTONOMIC_SRL
                && ((DtnmaRouter) hmob.getRouter()).getDtnmaAgent().getEffectiveManagementMode() == DtnmaConfig.ManagementMode.M2_MOBILITY_AWARE;

        DtnmaAgent mobAg = ((DtnmaRouter) hmob.getRouter()).getDtnmaAgent();
        mobAg.ensureSubsystemsInitialized();
        for (int k = 1; k <= 10; k++) {
            DTNHost peer = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                    "bp" + k, Collections.<NetworkInterface>singletonList(ifc), new ModuleCommunicationBus(), mm, pmob);
            mobAg.getEddSampler().recordContact(peer);
        }
        boolean beaconAdaptOk = Math.abs(mobAg.getEffectiveBeaconRefreshInterval() - 10.0) < 1e-6;

        Settings.initFromStream(new ByteArrayInputStream(
                (baseCfg + "DtnmaRouter.interventionTier = TIER1_PROTECTION_ONLY\n").getBytes(StandardCharsets.UTF_8)));
        ManagedSprayAndWaitRouter pmTier1 = new ManagedSprayAndWaitRouter(new Settings("Group"));
        pmTier1.getDtnmaAgent().setDynamicSprayQuota(1);
        pmTier1.getDtnmaAgent().setIngressMode(DtnmaRouter.INGRESS_CRITICAL_ONLY);
        boolean tier1Ok = pmTier1.getDtnmaAgent().getInterventionTier() == DtnmaConfig.InterventionTier.TIER1_PROTECTION_ONLY
                && pmTier1.getDtnmaAgent().getEffectiveBulkSprayQuota(6) == 6
                && !pmTier1.getDtnmaAgent().isCriticalOnlyBulkSheddingActive()
                && pmTier1.getDtnmaAgent().allowNonDirectBulkForward(
                        new Message(h0b, h1, "BULK_T1", 1024), 0.90, 0.96, 0.10, true, 0.1, 0.2);

        check(rulesOk && presetsOk && beaconAdaptOk && tier1Ok,
                "Test18: InterventionTier 1/3, M0b..M2-Mob presets, densityAdaptiveBeacons, & rules_vdtn catch-all release",
                String.format("rules=%s(%s) presets=%s beaconAdapt=%s tier1=%s",
                        rulesOk, ruleInfo, presetsOk, beaconAdaptOk, tier1Ok));
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }

    private static void test20NominalBackpressureAndAckEvictionSplit() {
        String rulePath = new File("scenarios/rules_vdtn.txt").isFile()
                ? "scenarios/rules_vdtn.txt" : "../ADTNM/scenarios/rules_vdtn.txt";

        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("dtnma_test20_");
            String repDir = tmpDir.toAbsolutePath().toString().replace('\\', '/');

            SimScenario.reset();
            ConnectivityGrid.reset();
            DTNHost.reset();
            Message.reset();
            NetworkInterface.reset();
            MovementModel.reset();
            String cfg =
                    "Scenario.name = Test20Scenario\n" +
                    "Scenario.simulateConnections = true\n" +
                    "Scenario.updateInterval = 1.0\n" +
                    "Scenario.endTime = 200\n" +
                    "Scenario.nrofHostGroups = 3\n" +
                    "MovementModel.worldSize = 100, 100\n" +
                    "MovementModel.warmup = 0\n" +
                    "MovementModel.rngSeed = 1\n" +
                    "Events.nrof = 0\n" +
                    "bt.type = SimpleBroadcastInterface\n" +
                    "bt.transmitSpeed = 250000\n" +
                    "bt.transmitRange = 100\n" +
                    "Group.movementModel = StationaryMovement\n" +
                    "Group.bufferSize = 500000\n" +
                    "Group.msgTtl = 300\n" +
                    "Group.speed = 1.0, 1.0\n" +
                    "Group.waitTime = 0, 0\n" +
                    "Group.nodeLocation = 0, 0\n" +
                    "Group.nrofInterfaces = 1\n" +
                    "Group.interface1 = bt\n" +
                    "SprayAndWaitRouter.nrofCopies = 6\n" +
                    "SprayAndWaitRouter.binaryMode = true\n" +
                    "DtnmaRouter.rulesFile = " + rulePath.replace('\\', '/') + "\n" +
                    "Group1.groupID = m\n" +
                    "Group1.nrofHosts = 1\n" +
                    "Group1.router = DtnmaM2SrlRouter\n" +
                    "Group1.nodeLocation = 500, 500\n" +
                    "Group2.groupID = e\n" +
                    "Group2.nrofHosts = 2\n" +
                    "Group2.router = ManagedEpidemicRouter\n" +
                    "Group2.nodeLocation = 0, 0\n" +
                    "Group3.groupID = d\n" +
                    "Group3.nrofHosts = 1\n" +
                    "Group3.router = ManagedEpidemicRouter\n" +
                    "Group3.nodeLocation = 500, 500\n" +
                    "Report.warmup = 0\n" +
                    "Report.reportDir = " + repDir + "\n" +
                    "DtnmaMetricsReport.eventA = 0, 100\n" +
                    "DtnmaMetricsReport.eventHorizon = 100\n";
            Settings.initFromStream(new ByteArrayInputStream(cfg.getBytes(StandardCharsets.UTF_8)));
            SimClock.reset();

            List<DTNHost> hosts = SimScenario.getInstance().getHosts();
            DTNHost hM2 = hosts.get(0);
            DTNHost hEaeA = hosts.get(1);
            DTNHost hEaeB = hosts.get(2);
            DTNHost hDst = hosts.get(3);

            // 1. Check M2 with rules_vdtn.txt nominal backpressure == true (P3)
            DtnmaM2SrlRouter rM2 = (DtnmaM2SrlRouter) hM2.getRouter();
            rM2.getDtnmaAgent().loadRulesFromFile(rulePath);
            SimClock.getInstance().advance(2.0);
            hM2.update(false);
            boolean vdtnBpOk = rM2.getDtnmaAgent().isBackpressureEnabled() && rM2.getLastExecutedProfile() == 3;

            // 2. Check ManagedEpidemicRouter nominal backpressure holds bulk at peerQ=0.80 while sending critical
            ManagedEpidemicRouter rEaeA = (ManagedEpidemicRouter) hEaeA.getRouter();
            ManagedEpidemicRouter rEaeB = (ManagedEpidemicRouter) hEaeB.getRouter();
            rEaeB.createNewMessage(new Message(hEaeB, hDst, "BULK_FILL_400K", 400000));
            rEaeA.createNewMessage(new Message(hEaeA, hDst, "BULK_BP_HOLD", 1000));
            hEaeA.forceConnection(hEaeB, null, true);
            SimClock.getInstance().advance(2.0);
            hEaeA.update(true);
            boolean eaeHoldBulk = !rEaeA.isTransferring();
            rEaeA.createNewMessage(new Message(hEaeA, hDst, "CRITICAL_BP_BYPASS", 1000));
            SimClock.getInstance().advance(2.0);
            hEaeA.update(true);
            boolean eaeAllowCrit = rEaeA.isSending("CRITICAL_BP_BYPASS") && !rEaeA.isSending("BULK_BP_HOLD");
            hEaeA.forceConnection(hEaeB, null, false);

            // 3. Verify isHostKnownAcked + DtnmaMetricsReport ACKed vs un-ACKed eviction split
            SimClock.getInstance().setTime(10.0);
            DtnmaMetricsReport rep = new DtnmaMetricsReport();
            Message mAcked = new Message(hDst, hEaeB, "CRITICAL_ACKED_20", 1000);
            Message mUnacked = new Message(hEaeB, hDst, "CRITICAL_UNACKED_20", 1000);
            ManagedEpidemicRouter rDtnma = (ManagedEpidemicRouter) hDst.getRouter();
            rDtnma.getDtnmaAgent().ensureSubsystemsInitialized();

            boolean beforeAckFalse = !DtnmaMetricsReport.isHostKnownAcked(hDst, mAcked)
                    && !DtnmaMetricsReport.isHostKnownAcked(hEaeB, mAcked);
            rep.newMessage(mAcked);
            rep.newMessage(mUnacked);

            SimClock.getInstance().setTime(20.0);
            rDtnma.getDtnmaAgent().getDeliveredAckSet().put(mAcked.getId(), Boolean.TRUE);
            rEaeB.receiveMessage(mAcked, hDst);
            rEaeB.messageTransferred(mAcked.getId(), hDst);
            boolean afterAckTrue = DtnmaMetricsReport.isHostKnownAcked(hDst, mAcked)
                    && DtnmaMetricsReport.isHostKnownAcked(hEaeB, mAcked)
                    && !DtnmaMetricsReport.isHostKnownAcked(hEaeB, mUnacked);

            rep.messageTransferred(mAcked, hDst, hEaeB, true);
            SimClock.getInstance().setTime(30.0);
            rep.messageDeleted(mAcked, hDst, true);
            rep.messageDeleted(mUnacked, hEaeB, true);

            SimClock.getInstance().setTime(50.0);
            rep.done();

            Path outFile = tmpDir.resolve("Test20Scenario_DtnmaMetricsReport.txt");
            Map<String, String> kv = new HashMap<String, String>();
            for (String line : Files.readAllLines(outFile, StandardCharsets.UTF_8)) {
                int idx = line.indexOf(": ");
                if (idx > 0) {
                    kv.put(line.substring(0, idx).trim(), line.substring(idx + 2).trim());
                }
            }
            boolean reportSplitOk = "1".equals(kv.get("critical_buffer_dropped"))
                    && "1".equals(kv.get("critical_buffer_dropped_acked"))
                    && "1".equals(kv.get("unique_critical_lost"))
                    && "1".equals(kv.get("eventA_crit_evicted"))
                    && "1".equals(kv.get("eventA_crit_evicted_acked"))
                    && "1".equals(kv.get("eventA_crit_unique_lost"));

            check(vdtnBpOk && eaeHoldBulk && eaeAllowCrit && beforeAckFalse && afterAckTrue && reportSplitOk,
                    "Test20: Nominal backpressure (P3), isHostKnownAcked, & ACKed critical eviction split work cleanly",
                    String.format("vdtnBp=%s eaeHold=%s eaeCrit=%s ackPre=%s ackPost=%s split=%s(cbd=%s,cbda=%s,ev=%s,eva=%s,ul=%s)",
                            vdtnBpOk, eaeHoldBulk, eaeAllowCrit, beforeAckFalse, afterAckTrue, reportSplitOk,
                            kv.get("critical_buffer_dropped"), kv.get("critical_buffer_dropped_acked"),
                            kv.get("eventA_crit_evicted"), kv.get("eventA_crit_evicted_acked"),
                            kv.get("eventA_crit_unique_lost")));
        } catch (Exception e) {
            check(false, "Test20: Nominal backpressure (P3), isHostKnownAcked, & ACKed critical eviction split",
                    "Exception: " + e.toString());
        } finally {
            SimScenario.reset();
            ConnectivityGrid.reset();
            DTNHost.reset();
            Message.reset();
            NetworkInterface.reset();
            MovementModel.reset();
            if (tmpDir != null) {
                deleteRecursive(tmpDir.toFile());
            }
        }
    }

    private static void test21EnergyOnlyGuardsAndDownlinkReport() {
        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("dtnma_test21_");
            String repDir = tmpDir.toAbsolutePath().toString().replace('\\', '/');

            SimScenario.reset();
            ConnectivityGrid.reset();
            DTNHost.reset();
            Message.reset();
            NetworkInterface.reset();
            MovementModel.reset();

            String cfg =
                    "Scenario.name = Test21Scenario\n" +
                    "Scenario.simulateConnections = false\n" +
                    "Scenario.updateInterval = 1.0\n" +
                    "Scenario.endTime = 200\n" +
                    "Scenario.nrofHostGroups = 2\n" +
                    "MovementModel.worldSize = 1000, 1000\n" +
                    "MovementModel.warmup = 0\n" +
                    "MovementModel.rngSeed = 1\n" +
                    "Events.nrof = 0\n" +
                    "bt.type = SimpleBroadcastInterface\n" +
                    "bt.transmitSpeed = 250000\n" +
                    "bt.transmitRange = 35\n" +
                    "Group.movementModel = StationaryMovement\n" +
                    "Group.bufferSize = 100000\n" +
                    "Group.msgTtl = 300\n" +
                    "Group.speed = 1.0, 1.0\n" +
                    "Group.waitTime = 0, 0\n" +
                    "Group.nrofInterfaces = 1\n" +
                    "Group.interface1 = bt\n" +
                    "Group.initialEnergy = 100.0\n" +
                    "Group.scanEnergy = 0.0\n" +
                    "Group.scanResponseEnergy = 0.0\n" +
                    "Group.transmitEnergy = 0.0\n" +
                    "Group.energyWarmup = 0\n" +
                    "SprayAndWaitRouter.nrofCopies = 6\n" +
                    "SprayAndWaitRouter.binaryMode = true\n" +
                    "Group1.groupID = q\n" +
                    "Group1.nrofHosts = 1\n" +
                    "Group1.router = DtnmaM0eEnergy25Router\n" +
                    "Group1.nodeLocation = 500, 500\n" +
                    "Group2.groupID = mgr\n" +
                    "Group2.nrofHosts = 2\n" +
                    "Group2.router = DtnmaM1RemoteRouter\n" +
                    "Group2.nodeLocation = 0, 0\n" +
                    "Report.warmup = 0\n" +
                    "Report.reportDir = " + repDir + "\n";
            Settings.initFromStream(new ByteArrayInputStream(cfg.getBytes(StandardCharsets.UTF_8)));
            SimClock.reset();

            List<DTNHost> hosts = SimScenario.getInstance().getHosts();
            DTNHost h25 = hosts.get(0);
            DTNHost hMgr0 = hosts.get(1);
            DTNHost hMgr1 = hosts.get(2);

            DtnmaM0eEnergy25Router r25 = (DtnmaM0eEnergy25Router) h25.getRouter();
            DtnmaM1RemoteRouter rMgr1 = (DtnmaM1RemoteRouter) hMgr1.getRouter();

            // 1. M0e-25 stays nominal at Q=0.95, SoC=1.0 and ignores remote CTRL_UPDATE
            r25.createNewMessage(new Message(h25, hMgr0, "BULK_FULL_25", 95000));
            SimClock.getInstance().advance(2.0);
            h25.update(false);
            r25.getDtnmaAgent().applyDownlinkControlBundle(new Message(hMgr0, h25, "CTRL_UPDATE_THROTTLE_2", 1024));
            boolean nomHighQOk = !r25.supportsDynamicControlReconfig()
                    && r25.getCurrentSprayQuota() == 6
                    && r25.getDtnmaAgent().getCurrentIngressMode() == DtnmaRouter.INGRESS_ACCEPT_ALL;

            // 2. At SoC=0.22, M0e-25 triggers P0 critical-only hold
            h25.getComBus().updateProperty(EnergyModel.ENERGY_VALUE_ID, 22.0);
            SimClock.getInstance().advance(2.0);
            h25.update(false);
            boolean trig022Ok = r25.getCurrentSprayQuota() == 1
                    && r25.getDtnmaAgent().getCurrentIngressMode() == DtnmaRouter.INGRESS_CRITICAL_ONLY;

            // 3. M1-Remote adopts CTRL_UPDATE_THROTTLE on destination delivery + DtnmaMetricsReport counts downlink
            SimClock.getInstance().setTime(10.0);
            DtnmaMetricsReport rep = new DtnmaMetricsReport();
            Message dThrot = new Message(hMgr0, hMgr1, "CTRL_UPDATE_THROTTLE_W1_1", 4096);
            boolean clsOk = DtnmaRouter.isDownlinkControlMessage(dThrot)
                    && !DtnmaRouter.isTelemetryOrAlarmMessage(dThrot);
            rep.newMessage(dThrot);

            hMgr1.update(false);
            SimClock.getInstance().setTime(15.0);
            rMgr1.receiveMessage(dThrot, hMgr0);
            rMgr1.messageTransferred(dThrot.getId(), hMgr0);
            rep.messageTransferred(dThrot, hMgr0, hMgr1, true);
            boolean remThrotOk = rMgr1.getCurrentSprayQuota() == 2
                    && rMgr1.getDtnmaAgent().getCurrentIngressMode() == DtnmaRouter.INGRESS_THROTTLE_BULK;

            rep.done();
            Path outFile = tmpDir.resolve("Test21Scenario_DtnmaMetricsReport.txt");
            Map<String, String> kv = new HashMap<String, String>();
            for (String line : Files.readAllLines(outFile, StandardCharsets.UTF_8)) {
                int idx = line.indexOf(": ");
                if (idx > 0) {
                    kv.put(line.substring(0, idx).trim(), line.substring(idx + 2).trim());
                }
            }
            boolean repOk = "1".equals(kv.get("downlink_created")) && "1".equals(kv.get("downlink_delivered"));

            check(nomHighQOk && trig022Ok && clsOk && remThrotOk && repOk,
                    "Test21: M0e-25 energy guard & DtnmaMetricsReport downlink adoption tracking",
                    String.format("nom=%s trig022=%s cls=%s remThrot=%s rep=%s(dc=%s,dd=%s)",
                            nomHighQOk, trig022Ok, clsOk, remThrotOk, repOk,
                            kv.get("downlink_created"), kv.get("downlink_delivered")));
        } catch (Exception e) {
            e.printStackTrace();
            check(false, "Test21: M0e-25 energy guard & downlink tracking",
                    "Exception: " + e.toString());
        } finally {
            SimScenario.reset();
            ConnectivityGrid.reset();
            DTNHost.reset();
            Message.reset();
            NetworkInterface.reset();
            MovementModel.reset();
            if (tmpDir != null) {
                deleteRecursive(tmpDir.toFile());
            }
        }
    }

    private static void test23FirstClassMobilityEdds() {
        String cfg =
                "Scenario.endTime = 1000\n" +
                "MovementModel.worldSize = 100, 100\n" +
                "bt.transmitSpeed = 250000\n" +
                "bt.transmitRange = 100\n" +
                "Group.bufferSize = 500000\n" +
                "Group.msgTtl = 300\n" +
                "Group.nrofHosts = 3\n" +
                "Group.speed = 1.0, 1.0\n" +
                "Group.waitTime = 0, 0\n" +
                "Group.nodeLocation = 0, 0\n" +
                "Group.initialEnergy = 100\n" +
                "Group.scanEnergy = 0\n" +
                "Group.scanResponseEnergy = 0\n" +
                "Group.transmitEnergy = 0\n" +
                "Group.router = DtnmaM2MobRouter\n" +
                "Group.nrofInterfaces = 1\n" +
                "Group.interface1 = bt\n" +
                "Scenario.nrofHostGroups = 1\n" +
                "Group1.groupID = m\n";
        Settings.initFromStream(new ByteArrayInputStream(cfg.getBytes(StandardCharsets.UTF_8)));
        SimClock.reset();
        Settings grp = new Settings("Group");
        SimpleBroadcastInterface ifc = new SimpleBroadcastInterface(new Settings("bt"));
        ifc.setGroupSettings(grp);
        MovementModel mm = new StationaryMovement(grp);
        DtnmaM2MobRouter proto = new DtnmaM2MobRouter(grp);
        List<NetworkInterface> ifaces = Collections.<NetworkInterface>singletonList(ifc);

        DTNHost h0 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "m", ifaces, new ModuleCommunicationBus(), mm, proto);
        DTNHost h1 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "p", ifaces, new ModuleCommunicationBus(), mm, proto);
        DtnmaM2MobRouter r0 = (DtnmaM2MobRouter) h0.getRouter();
        SimClock.getInstance().setTime(1.0);
        r0.update();
        EddTelemetrySampler s0 = r0.getDtnmaAgent().getEddSampler();

        SimClock.getInstance().setTime(10.0);
        s0.recordContact(h1);
        SimClock.getInstance().setTime(16.0);
        s0.recordContactEnd(h1);
        double dur1 = s0.getEddValue(EddTelemetrySampler.EDD_CONTACT_DURATION_MEAN);

        SimClock.getInstance().setTime(106.0);
        s0.recordContact(h1);
        s0.sampleNow();
        double ictVal = s0.getEddValue(EddTelemetrySampler.EDD_INTER_CONTACT_TIME);
        double encRate = s0.getEddValue(EddTelemetrySampler.EDD_ENCOUNTER_RATE);

        h0.getComBus().updateProperty(EnergyModel.ENERGY_VALUE_ID, 40.0);
        SimClock.getInstance().setTime(126.0);
        s0.sampleNow();
        double ttdRatio = s0.getEddValue(EddTelemetrySampler.EDD_ENERGY_TTD_RATIO);
        long stateBytes = r0.getDtnmaAgent().estimateAgentStateBytes();

        boolean ok = Math.abs(dur1 - 6.0) < 1e-4
                && Math.abs(ictVal - 90.0) < 1e-4
                && encRate > 0.0
                && ttdRatio < 1.0 && ttdRatio > 0.0
                && stateBytes > 512L;
        check(ok,
                "Test23: First-class mobility EDDs (duration, ICT, encounter rate, energy TTD, stateBytes)",
                String.format("dur1=%.2f ict=%.2f enc=%.3f ttd=%.3f bytes=%d",
                        dur1, ictVal, encRate, ttdRatio, stateBytes));
    }

    private static void test24MobilityAwareControllerAndAblations() {
        String cfg =
                "Scenario.endTime = 1000\n" +
                "MovementModel.worldSize = 100, 100\n" +
                "bt.transmitSpeed = 250000\n" +
                "bt.transmitRange = 100\n" +
                "Group.bufferSize = 500000\n" +
                "Group.msgTtl = 300\n" +
                "Group.nrofHosts = 3\n" +
                "Group.speed = 1.0, 1.0\n" +
                "Group.waitTime = 0, 0\n" +
                "Group.nodeLocation = 0, 0\n" +
                "Group.initialEnergy = 100\n" +
                "Group.scanEnergy = 0\n" +
                "Group.scanResponseEnergy = 0\n" +
                "Group.transmitEnergy = 0\n" +
                "DtnmaRouter.mobHorizonSec = 600\n";
        Settings.initFromStream(new ByteArrayInputStream(cfg.getBytes(StandardCharsets.UTF_8)));
        SimClock.reset();
        Settings grp = new Settings("Group");
        SimpleBroadcastInterface ifc = new SimpleBroadcastInterface(new Settings("bt"));
        ifc.setGroupSettings(grp);
        MovementModel mm = new StationaryMovement(grp);
        List<NetworkInterface> ifaces = Collections.<NetworkInterface>singletonList(ifc);

        DtnmaM2MobRouter proto = new DtnmaM2MobRouter(grp);
        DTNHost h0 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "m", ifaces, new ModuleCommunicationBus(), mm, proto);
        DTNHost h1 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "p", ifaces, new ModuleCommunicationBus(), mm, proto);
        DtnmaM2MobRouter r0 = (DtnmaM2MobRouter) h0.getRouter();
        r0.getDtnmaAgent().ensureSubsystemsInitialized();

        // 1. Healthy nominal -> P3 (L=6, accept all)
        SimClock.getInstance().setTime(5.0);
        r0.update();
        boolean nomOk = r0.getCurrentSprayQuota() == 6
                && r0.getDtnmaAgent().getCurrentIngressMode() == DtnmaRouter.INGRESS_ACCEPT_ALL
                && "ARI://DTNMA/MOB/NOMINAL_BALANCED".equals(r0.getDtnmaAgent().getActiveRuleAri());

        // 2. Fast energy drain -> TTD warn zone triggers graduated quota cap P2 (L=4, ing=0),
        // then deeper drain triggers P1 bulk throttle (L=2, ing=1) before 0.20 static floor
        h0.getComBus().updateProperty(EnergyModel.ENERGY_VALUE_ID, 60.0);
        SimClock.getInstance().setTime(25.0);
        r0.update();
        boolean energyCapOk = r0.getCurrentSprayQuota() == 4
                && r0.getDtnmaAgent().getCurrentIngressMode() == DtnmaRouter.INGRESS_ACCEPT_ALL
                && "ARI://DTNMA/MOB/ENERGY_HORIZON_CAP".equals(r0.getDtnmaAgent().getActiveRuleAri());

        h0.getComBus().updateProperty(EnergyModel.ENERGY_VALUE_ID, 48.0);
        SimClock.getInstance().setTime(35.0);
        r0.update();
        boolean energyThrottleOk = r0.getCurrentSprayQuota() == 2
                && r0.getDtnmaAgent().getCurrentIngressMode() == DtnmaRouter.INGRESS_THROTTLE_BULK
                && "ARI://DTNMA/MOB/ENERGY_HORIZON_THROTTLE".equals(r0.getDtnmaAgent().getActiveRuleAri());

        // 3. Ablation REPLICA_ONLY ignores energy-horizon drain trigger when SoC >= shieldEnergyMin
        r0.getDtnmaAgent().getConfig().mobAblationMode = "REPLICA_ONLY";
        h0.getComBus().updateProperty(EnergyModel.ENERGY_VALUE_ID, 55.0);
        SimClock.getInstance().setTime(45.0);
        r0.update();
        boolean repAblationOk = r0.getCurrentSprayQuota() == 6;

        // 4. Short contact duration (<12s) + moderate queue triggers SHORT_CONTACT_CAP (L=4)
        EddTelemetrySampler s0 = r0.getDtnmaAgent().getEddSampler();
        SimClock.getInstance().setTime(50.0);
        s0.recordContact(h1);
        SimClock.getInstance().setTime(54.0);
        s0.recordContactEnd(h1);
        r0.createNewMessage(new Message(h0, h1, "BULK_DUR_TEST", 300000));
        SimClock.getInstance().setTime(56.0);
        r0.update();
        boolean shortCapOk = r0.getCurrentSprayQuota() == 4
                && "ARI://DTNMA/MOB/SHORT_CONTACT_CAP".equals(r0.getDtnmaAgent().getActiveRuleAri());

        check(nomOk && energyCapOk && energyThrottleOk && repAblationOk && shortCapOk,
                "Test24: M2-Mob coordinates graduated energy TTD, contact duration cap, and ablation switches",
                String.format("nom=%s eCap=%s eThrot=%s repAbl=%s shortCap=%s(ari=%s,q=%d)",
                        nomOk, energyCapOk, energyThrottleOk, repAblationOk, shortCapOk,
                        r0.getDtnmaAgent().getActiveRuleAri(), r0.getCurrentSprayQuota()));
    }

    private static void test25DeployabilityOverheadMetricsInReport() {
        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("dtnma_test25_");
            String repDir = tmpDir.toAbsolutePath().toString().replace('\\', '/');
            SimScenario.reset(); ConnectivityGrid.reset(); DTNHost.reset(); Message.reset();
            NetworkInterface.reset(); MovementModel.reset();

            String cfg =
                    "Scenario.name = Test25Scenario\n" +
                    "Scenario.simulateConnections = true\n" +
                    "Scenario.updateInterval = 1.0\n" +
                    "Scenario.endTime = 200\n" +
                    "Scenario.nrofHostGroups = 1\n" +
                    "MovementModel.worldSize = 100, 100\n" +
                    "MovementModel.warmup = 0\n" +
                    "MovementModel.rngSeed = 1\n" +
                    "Events.nrof = 0\n" +
                    "bt.type = SimpleBroadcastInterface\n" +
                    "bt.transmitSpeed = 250000\n" +
                    "bt.transmitRange = 100\n" +
                    "Group.movementModel = StationaryMovement\n" +
                    "Group.bufferSize = 500000\n" +
                    "Group.msgTtl = 300\n" +
                    "Group.speed = 1.0, 1.0\n" +
                    "Group.waitTime = 0, 0\n" +
                    "Group.nodeLocation = 0, 0\n" +
                    "Group.nrofInterfaces = 1\n" +
                    "Group.interface1 = bt\n" +
                    "Group1.groupID = mob\n" +
                    "Group1.nrofHosts = 2\n" +
                    "Group1.router = DtnmaM2MobRouter\n" +
                    "Report.warmup = 0\n" +
                    "Report.reportDir = " + repDir + "\n";
            Settings.initFromStream(new ByteArrayInputStream(cfg.getBytes(StandardCharsets.UTF_8)));
            SimClock.reset();
            SimScenario.getInstance();
            DtnmaMetricsReport rep = new DtnmaMetricsReport();
            SimClock.getInstance().setTime(10.0);
            rep.done();

            Path outFile = tmpDir.resolve("Test25Scenario_DtnmaMetricsReport.txt");
            Map<String, Double> vals = new HashMap<String, Double>();
            for (String line : Files.readAllLines(outFile, StandardCharsets.UTF_8)) {
                int idx = line.indexOf(": ");
                if (idx > 0) {
                    try {
                        vals.put(line.substring(0, idx).trim(), Double.parseDouble(line.substring(idx + 2).trim()));
                    } catch (NumberFormatException ignored) {}
                }
            }
            boolean hasKeys = vals.containsKey("agent_state_bytes_per_node")
                    && vals.containsKey("ctrl_energy_share")
                    && vals.containsKey("avg_contact_duration_s")
                    && vals.containsKey("avg_ict_s")
                    && vals.containsKey("avg_encounter_rate_per_min");
            boolean ok = hasKeys && vals.get("agent_state_bytes_per_node") >= 512.0 && vals.get("ctrl_energy_share") >= 0.0;
            check(ok,
                    "Test25: DtnmaMetricsReport emits mobile deployability & mobility summary metrics",
                    "keysOk=" + hasKeys + " stateBytes=" + vals.get("agent_state_bytes_per_node"));
        } catch (Exception e) {
            e.printStackTrace();
            check(false, "Test25: DtnmaMetricsReport emits mobile deployability metrics", "Exception: " + e.toString());
        } finally {
            SimScenario.reset(); ConnectivityGrid.reset(); DTNHost.reset(); Message.reset();
            NetworkInterface.reset(); MovementModel.reset();
            if (tmpDir != null) deleteRecursive(tmpDir.toFile());
        }
    }

    private static void test26CrossSubstrateMobAndAblationPresets() {
        String cfg =
                "Scenario.endTime = 1000\n" +
                "MovementModel.worldSize = 100, 100\n" +
                "bt.transmitSpeed = 250000\n" +
                "bt.transmitRange = 100\n" +
                "Group.bufferSize = 500000\n" +
                "Group.msgTtl = 300\n" +
                "Group.nrofHosts = 4\n" +
                "Group.speed = 1.0, 1.0\n" +
                "Group.waitTime = 0, 0\n" +
                "Group.nodeLocation = 0, 0\n" +
                "SprayAndWaitRouter.nrofCopies = 6\n" +
                "SprayAndWaitRouter.binaryMode = true\n" +
                "ProphetV2Router.secondsInTimeUnit = 30\n" +
                "DtnmaRouter.managementMode = M2_MOBILITY_AWARE\n" +
                "DtnmaRouter.mobHorizonSec = 600\n";
        Settings.initFromStream(new ByteArrayInputStream(cfg.getBytes(StandardCharsets.UTF_8)));
        SimClock.reset();
        Settings grp = new Settings("Group");
        SimpleBroadcastInterface ifc = new SimpleBroadcastInterface(new Settings("bt"));
        ifc.setGroupSettings(grp);
        MovementModel mm = new StationaryMovement(grp);
        List<NetworkInterface> ifaces = Collections.<NetworkInterface>singletonList(ifc);

        routing.DtnmaManagedRouter[] protos = new routing.DtnmaManagedRouter[] {
                new ManagedSprayAndWaitRouter(grp),
                new ManagedProphetV2Router(grp),
                new ManagedMaxPropRouter(grp),
                new ManagedEpidemicRouter(grp)
        };
        boolean crossOk = true;
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < protos.length; i++) {
            DTNHost h = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                    "cm" + i, ifaces, new ModuleCommunicationBus(), mm, protos[i].getManagedActiveRouter());
            routing.DtnmaManagedRouter mr = (routing.DtnmaManagedRouter) h.getRouter();
            DtnmaAgent ag = mr.getDtnmaAgent();
            SimClock.getInstance().setTime(5.0 + i);
            ag.onBeforeUpdate(SimClock.getTime());
            boolean modeOk = ag.getConfig().managementMode == DtnmaConfig.ManagementMode.M2_MOBILITY_AWARE;
            boolean ariOk = ag.getActiveRuleAri() != null && ag.getActiveRuleAri().startsWith("ARI://DTNMA/MOB/");
            boolean bytesOk = ag.estimateAgentStateBytes() >= 512L;
            if (!modeOk || !ariOk || !bytesOk) {
                crossOk = false;
                detail.append(mr.getClass().getSimpleName())
                      .append("{mode=").append(modeOk)
                      .append(",ari=").append(ag.getActiveRuleAri())
                      .append(",bytes=").append(ag.estimateAgentStateBytes()).append("} ");
            }
        }

        DtnmaM2MobEnergyOnlyRouter eProto = (DtnmaM2MobEnergyOnlyRouter) (new DtnmaM2MobEnergyOnlyRouter(grp)).replicate();
        DtnmaM2MobBufferOnlyRouter bProto = (DtnmaM2MobBufferOnlyRouter) (new DtnmaM2MobBufferOnlyRouter(grp)).replicate();
        DtnmaM2MobReplicaOnlyRouter rProto = (DtnmaM2MobReplicaOnlyRouter) (new DtnmaM2MobReplicaOnlyRouter(grp)).replicate();
        boolean presetsOk = "ENERGY_ONLY".equals(eProto.getDtnmaAgent().getConfig().mobAblationMode)
                && "BUFFER_ONLY".equals(bProto.getDtnmaAgent().getConfig().mobAblationMode)
                && "REPLICA_ONLY".equals(rProto.getDtnmaAgent().getConfig().mobAblationMode);

        check(crossOk && presetsOk,
                "Test26: M2_MOBILITY_AWARE runs across Managed{SnW,PRoPHETv2,MaxProp,Epidemic} and ablation presets preserve mode",
                detail.toString() + " presetsOk=" + presetsOk);
    }

    private static void test27CongestionAwareSnWBaseline() {
        String cfg =
                "Scenario.endTime = 500\n" +
                "MovementModel.worldSize = 100, 100\n" +
                "bt.transmitSpeed = 250000\n" +
                "bt.transmitRange = 100\n" +
                "Group.bufferSize = 100000\n" +
                "Group.msgTtl = 300\n" +
                "Group.nrofHosts = 3\n" +
                "Group.speed = 1.0, 1.0\n" +
                "Group.waitTime = 0, 0\n" +
                "Group.nodeLocation = 0, 0\n" +
                "Group.initialEnergy = 100\n" +
                "Group.scanEnergy = 0\n" +
                "Group.scanResponseEnergy = 0\n" +
                "Group.transmitEnergy = 0\n" +
                "SprayAndWaitRouter.nrofCopies = 6\n" +
                "SprayAndWaitRouter.binaryMode = true\n";
        Settings.initFromStream(new ByteArrayInputStream(cfg.getBytes(StandardCharsets.UTF_8)));
        SimClock.reset();
        Settings grp = new Settings("Group");
        SimpleBroadcastInterface ifc = new SimpleBroadcastInterface(new Settings("bt"));
        ifc.setGroupSettings(grp);
        MovementModel mm = new StationaryMovement(grp);
        List<NetworkInterface> ifaces = Collections.<NetworkInterface>singletonList(ifc);

        CongestionAwareSnWRouter proto = new CongestionAwareSnWRouter(grp);
        DTNHost h0 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "ca0", ifaces, new ModuleCommunicationBus(), mm, proto);
        DTNHost h1 = new DTNHost(new ArrayList<MessageListener>(), new ArrayList<MovementListener>(),
                "ca1", ifaces, new ModuleCommunicationBus(), mm, proto);
        CongestionAwareSnWRouter r0 = (CongestionAwareSnWRouter) h0.getRouter();
        CongestionAwareSnWRouter r1 = (CongestionAwareSnWRouter) h1.getRouter();
        SimClock.getInstance().setTime(1.0);
        r0.update();
        r1.update();

        double uInit = r0.getForwardingUtility();
        Connection upCon = new VBRConnection(h0, ifaces.get(0), h1, ifaces.get(0));
        r0.changedConnection(upCon);
        SimClock.getInstance().setTime(10.0);
        double encRate = r0.getEncounterRatePerMin();
        double uAfter = r0.getForwardingUtility();
        boolean canRelayHealthy = r0.canRelayToPeer(upCon);

        h0.getComBus().updateProperty(EnergyModel.ENERGY_VALUE_ID, 20.0);
        boolean relaySuppressedLowEnergy = !r0.canRelayToPeer(upCon);

        boolean ok = encRate > 0.0 && uInit >= 0.0 && uAfter >= uInit && uAfter <= 1.0
                && canRelayHealthy && relaySuppressedLowEnergy;
        check(ok,
                "Test27: CongestionAwareSnWRouter tracks encounter utility and enforces static energy cutoff",
                String.format("enc=%.2f u0=%.3f u1=%.3f healthy=%s cut=%s",
                        encRate, uInit, uAfter, canRelayHealthy, relaySuppressedLowEnergy));
    }

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println("ADTNM MOBILITY-AWARE DTNMA JAVA UNIT TEST SUITE");
        System.out.println("=================================================================");
        test1SafetyShieldInvariantsAndLiveness();
        test5BeaconQuantizationAndWireSizes();
        test6BinaryTokenConservationAtRelay();
        test7AllCriticalFullBufferZeroOvershoot();
        test9ProtectedFifoEvictionAndCriticalOccupancy();
        test11ComponentLadderAndDownlinkControl();
        test12DownlinkInBandControlAdoption();
        test13ComposedStockRoutersProtection();
        test15ManagementParadigmsM0M1M2();
        test18InterventionTiersPresetsAndCatchAllRule();
        test20NominalBackpressureAndAckEvictionSplit();
        test21EnergyOnlyGuardsAndDownlinkReport();
        test23FirstClassMobilityEdds();
        test24MobilityAwareControllerAndAblations();
        test25DeployabilityOverheadMetricsInReport();
        test26CrossSubstrateMobAndAblationPresets();
        test27CongestionAwareSnWBaseline();
        System.out.println("=================================================================");
        System.out.println("Results: " + passed + " passed, " + failed + " failed.");
        System.out.println("=================================================================");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
