package com.sarab.vm.core;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class ResolvConfTest {
    @Test
    public void phoneServersFirstThenFallbacks() {
        String out = ResolvConf.render(Arrays.asList("/192.168.1.1", "2001:4860:4860::8888", "fe80::1%wlan0", "127.0.0.1", "8.8.8.8"));
        assertEquals("# written by Sarab before each start\n"
                + "nameserver 192.168.1.1\n"
                + "nameserver 8.8.8.8\n"
                + "nameserver 1.1.1.1\n"
                + "nameserver 2001:4860:4860::8888\n", out);
    }

    @Test
    public void noPhoneServersUsesPublicOnes() {
        assertEquals("# written by Sarab before each start\nnameserver 8.8.8.8\nnameserver 1.1.1.1\n",
                ResolvConf.render(Collections.<String>emptyList()));
    }
}
