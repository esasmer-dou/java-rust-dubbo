package com.reactor.rust.dubbo.provider;

import org.apache.zookeeper.ZooDefs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ZookeeperRegistryConfigTest {

    @Test
    void autoAclUsesCreatorAclWhenAuthenticationIsConfigured() {
        ZookeeperDubboProviderRegistration.RegistryConfig config = config(
                "digest",
                "provider:secret",
                ZookeeperDubboProviderRegistration.AclMode.AUTO);

        assertEquals(ZooDefs.Ids.CREATOR_ALL_ACL, config.acl());
    }

    @Test
    void autoAclKeepsBackwardCompatibleOpenAclWithoutAuthentication() {
        ZookeeperDubboProviderRegistration.RegistryConfig config = config(
                "",
                "",
                ZookeeperDubboProviderRegistration.AclMode.AUTO);

        assertEquals(ZooDefs.Ids.OPEN_ACL_UNSAFE, config.acl());
    }

    @Test
    void creatorAclRequiresAuthentication() {
        assertThrows(IllegalArgumentException.class, () -> config(
                "",
                "",
                ZookeeperDubboProviderRegistration.AclMode.CREATOR));
    }

    private static ZookeeperDubboProviderRegistration.RegistryConfig config(
            String authScheme,
            String authData,
            ZookeeperDubboProviderRegistration.AclMode aclMode) {
        return new ZookeeperDubboProviderRegistration.RegistryConfig(
                "zookeeper://127.0.0.1:2181",
                "dubbo",
                "provider",
                3_000,
                30_000,
                250,
                10_000,
                authScheme,
                authData,
                aclMode);
    }
}
