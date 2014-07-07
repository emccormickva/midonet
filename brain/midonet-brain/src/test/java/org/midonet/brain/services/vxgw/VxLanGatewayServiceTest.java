/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw;

import java.util.Collection;
import java.util.UUID;

import com.google.inject.Guice;
import com.google.inject.Injector;

import mockit.Expectations;
import mockit.Mocked;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.junit.Before;
import org.junit.Test;

import org.midonet.brain.BrainTestUtils;
import org.midonet.brain.southbound.midonet.MidoVxLanPeer;
import org.midonet.brain.southbound.vtep.VtepBroker;
import org.midonet.brain.southbound.vtep.VtepDataClient;
import org.midonet.brain.southbound.vtep.VtepDataClientProvider;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.cluster.data.VTEP;
import org.midonet.cluster.data.VtepBinding;
import org.midonet.cluster.data.host.Host;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;
import org.midonet.packets.IPv4Addr;

import static org.junit.Assert.assertTrue;

public class VxLanGatewayServiceTest {

    /*
     * Vtep parameters
     */
    private static final IPv4Addr vtepMgmtIp = IPv4Addr.apply("192.169.0.20");
    private static final int vtepMgmntPort = 6632;
    private static final int vni = 42;

    /*
     * Host parameters
     */
    private IPv4Addr tunnelZoneHostIp = IPv4Addr.apply("192.169.0.100");

    /*
     * Mocked components for the vtep data client
     */

    @Mocked
    private VtepDataClient vtepClient;
    @Mocked
    private VtepDataClientProvider vtepDataClientProvider;

    /*
     * Midonet data client
     */
    private DataClient dataClient = null;
    private ZookeeperConnectionWatcher zkConnWatcher;

    private UUID makeUnboundBridge(String name) throws SerializationException,
                                                       StateAccessException {
        Bridge bridge = new Bridge();
        bridge.setName(name);
        return dataClient.bridgesCreate(bridge);
    }

    private UUID makeBoundBridge(String name, String vtepPort, short vlan)
        throws SerializationException, StateAccessException {
        UUID bridgeId = makeUnboundBridge(name);
        dataClient.vtepAddBinding(vtepMgmtIp, vtepPort, vlan, bridgeId);
        dataClient.bridgeCreateVxLanPort(bridgeId, vtepMgmtIp, vtepMgmntPort,
                                         vni);
        return bridgeId;
    }

    @Before
    public void before() throws Exception {
        HierarchicalConfiguration config = new HierarchicalConfiguration();
        BrainTestUtils.fillTestConfig(config);
        Injector injector = Guice.createInjector(
            BrainTestUtils.modules(config));

        Directory directory = injector.getInstance(Directory.class);
        BrainTestUtils.setupZkTestDirectory(directory);

        dataClient = injector.getInstance(DataClient.class);
        zkConnWatcher = new ZookeeperConnectionWatcher();

        Host host = new Host();
        host.setName("TestHost");
        UUID hostId = dataClient.hostsCreate(UUID.randomUUID(), host);

        HostZkManager hostManager = injector.getInstance(HostZkManager.class);
        hostManager.makeAlive(hostId);
        assertTrue(dataClient.hostsIsAlive(hostId));

        TunnelZone tz = new TunnelZone();
        tz.setName("TestTz");
        UUID tzId = dataClient.tunnelZonesCreate(tz);
        TunnelZone.HostConfig zoneHost = new TunnelZone.HostConfig(hostId);
        zoneHost.setIp(tunnelZoneHostIp.toIntIPv4());
        dataClient.tunnelZonesAddMembership(tzId, zoneHost);

        VTEP vtep = new VTEP();
        vtep.setId(vtepMgmtIp);
        vtep.setMgmtPort(vtepMgmntPort);
        vtep.setTunnelZone(tzId);
        dataClient.vtepCreate(vtep);

    }

    /**
     * Check the suscription life cycle inside the gateway service.
     */
    @Test
    public void testBasicLifecycle(@Mocked final VxLanGwBroker vxGwBroker,
                                   @Mocked final VtepBroker vtepBroker,
                                   @Mocked final MidoVxLanPeer midoPeer) {
        new Expectations() {{
            // Per vtep
            vtepDataClientProvider.get(); result = vtepClient; times = 1;
            VtepBroker vB = new VtepBroker(vtepClient); times = 1;
            MidoVxLanPeer mP = new MidoVxLanPeer(dataClient); times = 1;
            new VxLanGwBroker(vB, mP); result = vxGwBroker; times = 1;
            vxGwBroker.start(); times = 1;
            vtepClient.connect(vtepMgmtIp, vtepMgmntPort); times = 1;

            // Shutdown
            vxGwBroker.shutdown(); times = 1;
            vtepClient.disconnect(); times = 1;
        }};

        VxLanGatewayService gwsrv = new VxLanGatewayService(
            dataClient, vtepDataClientProvider, zkConnWatcher);
        gwsrv.startAndWait();
        gwsrv.stopAndWait();
    }

    /**
     * Test the addition of a bridge.
     */
    @Test
    public void testBridgeAddition(@Mocked final VxLanGwBroker vxGwBroker,
                                   @Mocked final VtepBroker vtepBroker,
                                   @Mocked final MidoVxLanPeer midoPeer)
        throws Exception {

        final org.opendaylight.ovsdb.lib.notation.UUID lsUuid =
            new org.opendaylight.ovsdb.lib.notation.UUID("meh");

        final VtepBinding binding =
            new VtepBinding("vtepPort", (short)666, UUID.randomUUID());

        new Expectations() {{

            // Per vtep
            vtepDataClientProvider.get(); result = vtepClient; times = 1;
            VtepBroker vB = new VtepBroker(vtepClient); times = 1;
            MidoVxLanPeer mP = new MidoVxLanPeer(dataClient); times = 1;
            new VxLanGwBroker(vB, mP); result = vxGwBroker; times = 1;
            vxGwBroker.start(); times = 1;
            vtepClient.connect(vtepMgmtIp, vtepMgmntPort); times = 1;

            // Consolidation of the vtep, name unknown until makeBoundBridge
            vB.ensureLogicalSwitchExists(anyString, vni);
                result = lsUuid; times = 1;
            vB.renewBindings(lsUuid, (Collection<VtepBinding>)any);
                times = 1;

            // The flooding proxy should be set
            vB.setFloodingProxy(anyString, tunnelZoneHostIp); times = 1;

            // Bridge addition
            mP.watch((UUID)withNotNull()); result = true; times = 1;

            // Syncup macs from the VTEP
            vB.advertiseMacs(); times = 1;

            // Shutdown
            vxGwBroker.shutdown(); times = 1;
            vtepClient.disconnect(); times = 1;
        }};

        VxLanGatewayService gwsrv = new VxLanGatewayService(
            dataClient, vtepDataClientProvider, zkConnWatcher);
        gwsrv.startAndWait();

        // add a new bridge with a binding
        UUID bId = makeBoundBridge("bridge1", "vtepPort", (short)666);

        gwsrv.stopAndWait();
    }

    /**
     * Test the update of a bridge.
     */
    @Test
    public void testBridgeUpdate(@Mocked final VxLanGwBroker vxGwBroker,
                                 @Mocked final VtepBroker vtepBroker,
                                 @Mocked final MidoVxLanPeer midoPeer)
        throws Exception {

        final org.opendaylight.ovsdb.lib.notation.UUID lsUuid =
            new org.opendaylight.ovsdb.lib.notation.UUID("meh");

        new Expectations() {{
            // Per vtep
            vtepDataClientProvider.get(); result = vtepClient; times = 1;
            VtepBroker vB = new VtepBroker(vtepClient); times = 1;
            MidoVxLanPeer mP = new MidoVxLanPeer(dataClient); times = 1;
            new VxLanGwBroker(vB, mP); result = vxGwBroker; times = 1;
            vxGwBroker.start(); times = 1;
            vtepClient.connect(vtepMgmtIp, vtepMgmntPort); times = 1;

            // Consolidation of the vtep, name unknown until makeBoundBridge
            vB.ensureLogicalSwitchExists(anyString, vni);
            result = lsUuid; times = 1;
            vB.renewBindings(lsUuid, (Collection<VtepBinding>)any);
            times = 1;

            // The flooding proxy should be set
            vB.setFloodingProxy(anyString, tunnelZoneHostIp); times = 1;

            // Bridge update (vxlanport addition)
            mP.watch((UUID)withNotNull()); result = true; times = 1;
            vB.advertiseMacs(); times = 1;

            // Shutdown
            vxGwBroker.shutdown(); times = 1;
            vtepClient.disconnect(); times = 1;
        }};

        VxLanGatewayService gwsrv = new VxLanGatewayService(
            dataClient, vtepDataClientProvider, zkConnWatcher);
        gwsrv.startAndWait();

        // add a new bridge without a binding
        UUID bridgeId = makeUnboundBridge("bridge1");

        // add a binding to the bridge
        dataClient.bridgeCreateVxLanPort(bridgeId, vtepMgmtIp, vtepMgmntPort,
                                         vni);

        // remove binding from the bridge
        dataClient.bridgeDeleteVxLanPort(bridgeId);

        gwsrv.stopAndWait();
    }
}
