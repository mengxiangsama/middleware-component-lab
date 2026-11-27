# Hazelcast IMap + Java 同步备份与分区接管

多个发货服务实例需要查询同一笔订单的最新状态。Hazelcast 的分布式 `IMap` 把订单键分配给分区，分区主成员处理读写请求，其他成员可以保存副本。一个主成员退出时，存活成员可把已有副本提升为主分区，继续提供查询。

## 接入步骤

1. Java 项目使用 `com.hazelcast:hazelcast:5.7.0`。成员使用相同的 `clusterName`，设置 TCP/IP 发现地址，并启动 `Hazelcast.newHazelcastInstance(config)`。实验的两个成员运行在同一 JVM，通过不同的 `127.0.0.1` TCP 端口组成集群。
2. 两个成员都为 `shipment-status` 配置一份同步备份。集群稳定时，正常完成的写入会等待同步副本更新并确认。 [官方 Map 备份文档](https://docs.hazelcast.com/hazelcast/5.7/data-structures/backing-up-maps)

   ```java
   config.addMapConfig(new MapConfig("shipment-status")
           .setBackupCount(1)
           .setAsyncBackupCount(0));
   ```

3. 用 `getPartitionService().isClusterSafe()` 等待分区稳定，再用 `getPartition(orderId).getOwner()` 选出由成员 A 持有的订单键。用 `getMap("shipment-status")` 在 A 写订单状态，在 B 查同一个键，并用 `getLocalMapStats()` 核对 B 有一份备份。
4. 通过 `A.getLifecycleService().terminate()` 强制终止 A，等待 B 的成员列表只剩自己且该键的分区 owner 变为 B，再查询最新订单状态。 [分区服务](https://docs.hazelcast.org/docs/5.7.0/javadoc/com/hazelcast/partition/PartitionService.html#isClusterSafe()) · [成员生命周期](https://docs.hazelcast.org/docs/5.7.0/javadoc/com/hazelcast/core/LifecycleService.html#terminate())

## 运行与结果

准备 JDK 17 或 21。从仓库根目录运行，Maven Wrapper 会下载依赖并启动两个临时成员，结束时关闭成员。

```sh
./mvnw -B -f labs/hazelcast-imap-backup/pom.xml clean verify
```

2026-11-27 在 macOS 26.5 ARM64 和 OpenJDK 17.0.16 上实测 Hazelcast 5.7.0 Community Edition。两成员分别监听 `127.0.0.1:61223` 和 `127.0.0.1:61224`。A 对订单依次写入 `PACKED` 和 `SHIPPED`，从 B 发起的两次查询都返回对应值。B 在接手前有一份备份条目，主条目数为零。A 强制终止后，B 成为集群唯一成员和该分区的主成员，仍读到 `SHIPPED`。

```text
order-5 partition 4: A primary, B one backup, latest=SHIPPED
A terminated; B now owns partition 4 and reads order-5=SHIPPED
```

键和端口由每次运行选定。实验覆盖两份内存副本中一份存活时的接管。B 接管后集群只剩一份数据，集群分裂保护和全部成员退出后的恢复需要按部署验证。测试等待成员视图和分区归属收敛，20 秒是各项等待的测试上限。

## 官方资料

- [Hazelcast 5.7.0 发布](https://github.com/hazelcast/hazelcast/releases/tag/v5.7.0) · [Java 内嵌集群示例](https://docs.hazelcast.com/hazelcast/5.7/getting-started/get-started-java)
- [TCP/IP 和网卡配置](https://docs.hazelcast.com/hazelcast/5.7/clusters/network-configuration) · [IMap 本地指标](https://docs.hazelcast.com/hazelcast/5.7/data-structures/reading-map-metrics)
