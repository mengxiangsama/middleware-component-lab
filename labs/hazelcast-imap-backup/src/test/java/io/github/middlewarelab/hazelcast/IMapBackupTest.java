package io.github.middlewarelab.hazelcast;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IMapBackupTest {
    private static final String MAP = "shipment-status";
    private static final String LOOPBACK = "127.0.0.1";

    @Test
    @Timeout(75)
    void remainingMemberPromotesBackupWithLatestValueAfterOwnerTerminates() throws Exception {
        int[] ports = loopbackPorts();
        String cluster = "imap-backup-" + UUID.randomUUID();
        var memberA = Hazelcast.newHazelcastInstance(memberConfig(cluster, "member-a", ports[0], ports[0]));
        HazelcastInstance memberB = null;
        try {
            var survivor = Hazelcast.newHazelcastInstance(memberConfig(cluster, "member-b", ports[1], ports[0]));
            memberB = survivor;
            var localA = memberA.getCluster().getLocalMember();
            var localB = survivor.getCluster().getLocalMember();
            assertEquals(LOOPBACK, localA.getAddress().getHost());
            assertEquals(LOOPBACK, localB.getAddress().getHost());
            assertEquals(ports[0], localA.getAddress().getPort());
            assertEquals(ports[1], localB.getAddress().getPort());

            var members = Set.of(localA, localB);
            var partitionsA = memberA.getPartitionService();
            var partitionsB = survivor.getPartitionService();
            await("Both members did not converge to a safe two-member cluster", () ->
                    memberA.getCluster().getMembers().equals(members)
                            && survivor.getCluster().getMembers().equals(members)
                            && partitionsA.isClusterSafe());

            String key = IntStream.rangeClosed(1, 1024).mapToObj(id -> "order-" + id)
                    .filter(candidate -> localA.equals(partitionsA.getPartition(candidate).getOwner()))
                    .findFirst().orElseThrow();
            int partition = partitionsA.getPartition(key).getPartitionId();
            var mapA = memberA.<String, String>getMap(MAP);
            var mapB = survivor.<String, String>getMap(MAP);

            mapA.put(key, "PACKED");
            assertEquals("PACKED", mapB.get(key));
            assertEquals("PACKED", mapA.put(key, "SHIPPED"));
            assertEquals("SHIPPED", mapB.get(key));
            assertEquals(localA, partitionsB.getPartition(key).getOwner());
            await("B did not report the backup entry", () -> mapB.getLocalMapStats().getBackupEntryCount() == 1);
            assertEquals(0, mapB.getLocalMapStats().getOwnedEntryCount());
            System.out.printf("%s partition %d: A primary, B one backup, latest=%s%n", key, partition, mapB.get(key));

            memberA.getLifecycleService().terminate();
            assertFalse(memberA.getLifecycleService().isRunning());
            await("B did not become the sole member and partition owner", () ->
                    survivor.getCluster().getMembers().equals(Set.of(localB))
                            && localB.equals(partitionsB.getPartition(key).getOwner()));
            assertEquals("SHIPPED", mapB.get(key));
            System.out.printf("A terminated; B now owns partition %d and reads %s=%s%n", partition, key, mapB.get(key));
        } finally {
            if (memberB != null) {
                memberB.shutdown();
            }
            if (memberA.getLifecycleService().isRunning()) {
                memberA.shutdown();
            }
        }
    }

    private static Config memberConfig(String cluster, String name, int port, int seedPort) {
        var config = new Config().setClusterName(cluster).setInstanceName(name);
        config.setProperty("hazelcast.socket.bind.any", "false");
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.getJetConfig().setEnabled(false);
        config.addMapConfig(new MapConfig(MAP).setBackupCount(1).setAsyncBackupCount(0));

        var network = config.getNetworkConfig();
        network.setPort(port).setPortAutoIncrement(false);
        network.getInterfaces().setEnabled(true).addInterface(LOOPBACK);
        var join = network.getJoin();
        join.getMulticastConfig().setEnabled(false);
        join.getAutoDetectionConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(true).addMember(LOOPBACK + ":" + seedPort);
        return config;
    }

    private static int[] loopbackPorts() throws IOException {
        var loopback = InetAddress.getByName(LOOPBACK);
        try (var socketA = new ServerSocket(0, 1, loopback);
             var socketB = new ServerSocket(0, 1, loopback)) {
            return new int[] {socketA.getLocalPort(), socketB.getLocalPort()};
        }
    }

    private static void await(String message, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), message);
    }
}
