/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.dp.flows;

import com.midokura.util.netlink.NetlinkMessage;
import com.midokura.util.netlink.messages.BaseBuilder;

public class FlowActionUserspace implements FlowAction<FlowActionUserspace> {

    int uplinkPid;
    Long userData;

    @Override
    public void serialize(BaseBuilder builder) {
        builder.addAttr(Attr.OVS_USERSPACE_ATTR_PID, uplinkPid);
        if (userData != null) {
            builder.addAttr(Attr.OVS_USERSPACE_ATTR_USERDATA, userData);
        }
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            uplinkPid = message.getAttrValue(Attr.OVS_USERSPACE_ATTR_PID);
            userData = message.getAttrValue(Attr.OVS_USERSPACE_ATTR_USERDATA);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public NetlinkMessage.AttrKey<FlowActionUserspace> getKey() {
        return FlowActionKey.USERSPACE;
    }

    @Override
    public FlowActionUserspace getValue() {
        return this;
    }

    public static class Attr<T> extends NetlinkMessage.AttrKey<T> {

        /* u32 Netlink PID to receive upcalls. */
        public static final Attr<Integer> OVS_USERSPACE_ATTR_PID
            = attr(1);

        /* u64 optional user-specified cookie. */
        public static final Attr<Long> OVS_USERSPACE_ATTR_USERDATA
            = attr(2);

        public Attr(int id) {
            super(id);
        }

        static <T> Attr<T> attr(int id) {
            return new Attr<T>(id);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowActionUserspace that = (FlowActionUserspace) o;

        if (uplinkPid != that.uplinkPid) return false;
        if (userData != null ? !userData.equals(
            that.userData) : that.userData != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = uplinkPid;
        result = 31 * result + (userData != null ? userData.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "FlowActionUserspace{" +
            "uplinkPid=" + uplinkPid +
            ", userData=" + userData +
            '}';
    }

    public int getUplinkPid() {
        return uplinkPid;
    }

    public FlowActionUserspace setUplinkPid(int uplinkPid) {
        this.uplinkPid = uplinkPid;
        return this;
    }

    public Long getUserData() {
        return userData;
    }

    public FlowActionUserspace setUserData(Long userData) {
        this.userData = userData;
        return this;
    }
}
