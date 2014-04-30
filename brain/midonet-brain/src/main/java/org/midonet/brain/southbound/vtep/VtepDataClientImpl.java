/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import org.midonet.brain.southbound.vtep.model.LogicalSwitch;
import org.midonet.brain.southbound.vtep.model.McastMac;
import org.midonet.brain.southbound.vtep.model.PhysicalPort;
import org.midonet.brain.southbound.vtep.model.PhysicalSwitch;
import org.midonet.brain.southbound.vtep.model.UcastMac;
import org.midonet.brain.southbound.vtep.model.VtepModelTranslator;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import org.opendaylight.controller.sal.connection.ConnectionConstants;
import org.opendaylight.controller.sal.core.Node;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.table.internal.Table;
import org.opendaylight.ovsdb.lib.table.vtep.Logical_Switch;
import org.opendaylight.ovsdb.lib.table.vtep.Mcast_Macs_Local;
import org.opendaylight.ovsdb.lib.table.vtep.Mcast_Macs_Remote;
import org.opendaylight.ovsdb.lib.table.vtep.Physical_Port;
import org.opendaylight.ovsdb.lib.table.vtep.Physical_Switch;
import org.opendaylight.ovsdb.lib.table.vtep.Ucast_Macs_Local;
import org.opendaylight.ovsdb.lib.table.vtep.Ucast_Macs_Remote;
import org.opendaylight.ovsdb.plugin.ConfigurationService;
import org.opendaylight.ovsdb.plugin.ConnectionService;
import org.opendaylight.ovsdb.plugin.InventoryService;
import org.opendaylight.ovsdb.plugin.InventoryServiceInternal;
import org.opendaylight.ovsdb.plugin.StatusWithUuid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VtepDataClientImpl implements VtepDataClient {

    private static final Logger log = LoggerFactory.getLogger(VtepDataClientImpl.class);
    private static final String VTEP_NODE_NAME= "vtep";

    private ConnectionService cnxnSrv = null;
    private ConfigurationService cfgSrv = null;
    private Node node = null;

    private static final int CNXN_TIMEOUT_MILLIS = 5000;

    @Override
    public void connect(final IPv4Addr mgmtIp, final int port) {

        if (cnxnSrv == null)
            cnxnSrv = new ConnectionService();

        cnxnSrv.init();

        Map<ConnectionConstants, String> params = new HashMap<>();
        params.put(ConnectionConstants.ADDRESS, mgmtIp.toString());
        params.put(ConnectionConstants.PORT, Integer.toString(port));

        InventoryService is = new InventoryService();
        is.init();

        cnxnSrv.setInventoryServiceInternal(is);
        log.info("NODES: {}", cnxnSrv.getNodes());
        node = cnxnSrv.connect(VTEP_NODE_NAME, params);
        log.info("Connecting to VTEP, node: {}", node.getID());
        cfgSrv = new ConfigurationService();
        cfgSrv.setInventoryServiceInternal(is);
        cfgSrv.setConnectionServiceInternal(cnxnSrv);
        cfgSrv.setDefaultNode(node);

        long timeoutAt = System.currentTimeMillis() + CNXN_TIMEOUT_MILLIS;
        while (!this.isReady() && System.currentTimeMillis() < timeoutAt) {
            log.info("Waiting for inventory service initialization");
            try {
                Thread.sleep(CNXN_TIMEOUT_MILLIS / 10);
            } catch (InterruptedException e) {
                log.error("Interrupted while waiting for service init.");
                Thread.interrupted();
            }
        }
        if (!this.isReady()) {
            throw new IllegalStateException("Could not complete connection");
        }
    }

    public boolean isReady() {
        ConcurrentMap<String, ConcurrentMap<String, Table<?>>> cache =
            this.cnxnSrv.getInventoryServiceInternal().getCache(node);
        if (cache == null) {
            return false;
        }
        Map<String, Table<?>> psTableCache =
            cache.get(Physical_Switch.NAME.getName());

        // This is not 100% reliable but at least verifies that we have some
        // data loaded, all the rest of the tables don't necessarily have to
        // contain data.
        return psTableCache != null && !psTableCache.isEmpty();
    }

    /**
     * Disconnects from the VTEP.
     */
    public void disconnect() {
        cnxnSrv.disconnect(node);
    }

    /**
     * Returns the ovsdb internal cache for the given table, if it doesn't
     * exist or it's empty, returns an empty map.
     * @param tableName the requested table.
     * @return the cached contents, if any.
     */
    private Map<String, Table<?>> getTableCache(String tableName) {

        InventoryServiceInternal isi = cnxnSrv.getInventoryServiceInternal();
        if (isi == null) {
            return null;
        }

        Map<String, ConcurrentMap<String, Table<?>>> cache = isi.getCache(node);
        if (cache == null) {
            return null;
        }

        Map<String, Table<?>> tableCache = cache.get(tableName);
        if (tableCache == null) {
            tableCache = new HashMap<>(0);
        }
        return tableCache;
    }

    @Override
    public List<PhysicalSwitch> listPhysicalSwitches() {
        Map<String, Table<?>> tableCache =
            getTableCache(Physical_Switch.NAME.getName());
        List<PhysicalSwitch> res = new ArrayList<>(tableCache.size());
        for (Map.Entry<String, Table<?>> e : tableCache.entrySet()) {
            log.debug("Found Physical Switch {} {}", e.getKey(), e.getValue());
            Physical_Switch ovsdbPs = (Physical_Switch)e.getValue();
            res.add(VtepModelTranslator.toMido(ovsdbPs, new UUID(e.getKey())));
        }
        return res;
    }

    /*
     * TODO replace this implementation with an actual query to the OVSDB,
     * that'll require moving the code to the ConfigurationService probably,
     * similarly as is done for the bind operation.
     *
     * Right now, this is effectively assuming that there is a single physical
     * switch.
     */
    @Override
    public List<PhysicalPort> listPhysicalPorts(UUID psUUID) {
        Map<String, Table<?>> tableCache =
            getTableCache(Physical_Port.NAME.getName());
        List<PhysicalPort> res = new ArrayList<>(tableCache.size());
        for (Map.Entry<String, Table<?>> e : tableCache.entrySet()) {
            log.debug("Found Physical Port {} {}", e.getKey(), e.getValue());
            Physical_Port ovsdbPort = (Physical_Port)e.getValue();
            res.add(VtepModelTranslator.toMido(ovsdbPort));
        }
        return res;
    }

    @Override
    public List<LogicalSwitch> listLogicalSwitches() {
        Map<String, Table<?>> tableCache =
            getTableCache(Logical_Switch.NAME.getName());
        List<LogicalSwitch> res = new ArrayList<>(tableCache.size());
        for (Map.Entry<String, Table<?>> e : tableCache.entrySet()) {
            log.debug("Found Logical Switch {} {}", e.getKey(), e.getValue());
            res.add(VtepModelTranslator.toMido((Logical_Switch)e.getValue(),
                                               new UUID(e.getKey())));
        }
        return res;
    }

    @Override
    public List<McastMac> listMcastMacsLocal() {
        log.debug("Listing mcast macs local");
        String tableName = Mcast_Macs_Local.NAME.getName();
        Map<String, Table<?>> tableCache = getTableCache(tableName);
        List<McastMac> res = new ArrayList<>(tableCache.size());
        for (Map.Entry<String, Table<?>> e : tableCache.entrySet()) {
            log.debug("Found Mac {} {}", e.getKey(), e.getValue());
            res.add(VtepModelTranslator.toMido((Mcast_Macs_Local)e.getValue()));
        }
        return res;
    }

    @Override
    public List<McastMac> listMcastMacsRemote() {
        log.debug("Listing mcast macs remote");
        String tableName = Mcast_Macs_Remote.NAME.getName();
        Map<String, Table<?>> tableCache = getTableCache(tableName);
        List<McastMac> res = new ArrayList<>(tableCache.size());
        for (Map.Entry<String, Table<?>> e : tableCache.entrySet()) {
            log.debug("Found Mac {} {}", e.getKey(), e.getValue());
            res.add(VtepModelTranslator.toMido((Mcast_Macs_Remote)e.getValue()));
        }
        return res;
    }

    @Override
    public List<UcastMac> listUcastMacsLocal() {
        log.debug("Listing ucast macs local");
        String tableName = Ucast_Macs_Local.NAME.getName();
        Map<String, Table<?>> tableCache = getTableCache(tableName);
        List<UcastMac> res = new ArrayList<>(tableCache.size());
        for (Map.Entry<String, Table<?>> e : tableCache.entrySet()) {
            log.debug("Found Mac {} {}", e.getKey(), e.getValue());
            res.add(VtepModelTranslator.toMido((Ucast_Macs_Local)e.getValue()));
        }
        return res;
    }

    @Override
    public List<UcastMac> listUcastMacsRemote() {
        log.debug("Listing ucast macs remote");
        String tableName = Ucast_Macs_Remote.NAME.getName();
        Map<String, Table<?>> tableCache = getTableCache(tableName);
        List<UcastMac> res = new ArrayList<>(tableCache.size());
        for (Map.Entry<String, Table<?>> e : tableCache.entrySet()) {
            log.debug("Found Mac {} {}", e.getKey(), e.getValue());
            res.add(VtepModelTranslator.toMido((Ucast_Macs_Remote)e.getValue()));
        }
        return res;
    }

    @Override
    public StatusWithUuid addLogicalSwitch(String name, int vni) {
        log.debug("Add logical switch {} with vni {}", name, vni);
        StatusWithUuid st = cfgSrv.vtepAddLogicalSwitch(name, vni);
        if (!st.isSuccess()) {
            log.warn("Add logical switch failed: {} - {}", st.getCode(),
                                                           st.getDescription());
        }
        return st;
    }

    @Override
    public Status bindVlan(String lsName, String portName, int vlan,
                            Integer vni, List<String> floodIps) {
        log.debug("Bind vlan {} on phys. port {} to logical switch {}, vni {}, "
                + "and adding ips: {}",
                  new Object[]{lsName, portName, vlan, vni, floodIps});
        Status st = cfgSrv.vtepBindVlan(lsName, portName,
                                        vlan, vni, floodIps);
        if (!st.isSuccess()) {
            log.warn("Bind vlan failed: {} - {}", st.getCode(),
                                                  st.getDescription());
        }
        return st;
    }

    @Override
    public Status addUcastMacRemote(String lsName, String mac, String ip) {
        log.debug("Adding Ucast Mac Remote: {} {} {}",
                  new Object[]{lsName, mac, ip});
        assert(IPv4Addr.fromString(ip) != null);
        assert (UNKNOWN_DST.equals(mac) || (MAC.fromString(mac) != null));
        StatusWithUuid st = cfgSrv.vtepAddUcastRemote(lsName, mac, ip, null);
        if (!st.isSuccess()) {
            log.error("Could not add Ucast Mac Remote: {} - {}",
                      st.getCode(), st.getDescription());
        }
        return st;
    }

    @Override
    public Status addMcastMacRemote(String lsName, String mac, String ip) {
        log.debug("Adding Mcast Mac Remote: {} {} {}",
                  new Object[]{lsName, mac, ip});
        assert(IPv4Addr.fromString(ip) != null);
        assert(UNKNOWN_DST.equals(mac) || (MAC.fromString(mac) != null));
        StatusWithUuid st = cfgSrv.vtepAddMcastRemote(lsName, mac, ip);
        if (!st.isSuccess()) {
            log.error("Could not add Mcast Mac Remote: {} - {}",
                      st.getCode(), st.getDescription());
        }
        return st;
    }
}
