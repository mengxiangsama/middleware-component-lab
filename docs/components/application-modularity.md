# 应用模块化

## Spring Modulith

Spring Modulith 以 Java 库形式运行在 Spring Boot 应用内。仓库的[实验 POM](../../labs/spring-modulith/pom.xml)固定 Spring Modulith 1.4.14 和 Spring Boot 3.5.16，使用 JDBC starter 和 Boot 管理的 H2 2.3.232。框架没有独立服务端或客户端，也不需要容器镜像。结构校验只分析编译后的类，订单积分的投递实验使用内嵌 H2。 [兼容矩阵](https://docs.spring.io/spring-modulith/reference/1.4/appendix.html) · [1.4.14 发布记录](https://github.com/spring-projects/spring-modulith/releases/tag/1.4.14)

| 1.4.14 支持的主要功能 | 教程状态 |
| --- | --- |
| 模块发现、依赖验证和显式依赖白名单 | 已有教程 · [在测试中校验订单与积分模块边界](#在测试中校验订单与积分模块边界) |
| 应用事件监听、JDBC 投递登记、失败重投与幂等消费 | 已有教程 · [订单积分实验](../../labs/spring-modulith/README.md#运行实验) |
| 已完成投递记录清理 | 已有教程 · [按完成时间清理](../../labs/spring-modulith/README.md#清理已完成的投递记录) |
| 命名接口、嵌套模块和自定义模块发现 | 待补 · [模块模型](https://docs.spring.io/spring-modulith/reference/1.4/fundamentals.html) |
| 向外部消息系统转发应用事件 | 待补 · [应用事件](https://docs.spring.io/spring-modulith/reference/1.4/events.html) |
| 模块集成测试与 `Scenario` | 待补 · [模块测试](https://docs.spring.io/spring-modulith/reference/1.4/testing.html) |
| 生成模块关系图与模块画布 | 待补 · [模块文档](https://docs.spring.io/spring-modulith/reference/1.4/documentation.html) |
| 运行时模块端点与可观测性 | 待补 · [生产功能](https://docs.spring.io/spring-modulith/reference/1.4/production-ready.html) |

### 在测试中校验订单与积分模块边界

同一个 Spring Boot 应用按业务包划分模块时，可以在测试阶段检查静态依赖。订单模块在根包暴露 `OrderPaid`，积分模块监听它来发积分。积分只允许依赖订单模块的公开接口。默认发现策略以应用根包的直接子包识别模块。对默认的封闭模块，`verify()` 检查模块间循环依赖、访问其他模块内部类型和违反显式依赖约束的引用。事件是否投递成功由订单积分集成测试验证。

准备 Java 17+，从仓库根目录运行。沿用实验的 JDBC starter，其中已包含用于结构校验的 `spring-modulith-core`。在积分模块的 [package-info.java](../../labs/spring-modulith/src/main/java/io/github/modulithlab/loyalty/package-info.java) 中声明允许的模块标识。

```java
@ApplicationModule(allowedDependencies = "orders")
package io.github.modulithlab.loyalty;

import org.springframework.modulith.ApplicationModule;
```

在 [ModuleStructureTest](../../labs/spring-modulith/src/test/java/io/github/modulithlab/ModuleStructureTest.java) 的 JUnit 测试方法中构建并校验模块模型。

```java
ApplicationModules.of(ModulithEventLabApplication.class).verify();
```

```bash
./mvnw -f labs/spring-modulith/pom.xml -Dtest=ModuleStructureTest test
```

预期一项测试通过。要观察拦截效果，把积分模块的参数临时改成 `allowedDependencies = {}` 并重跑，预期构建失败，`Violations` 指出 `OrderPaidListener` 引用了 `orders.OrderPaid`，并显示 `Allowed targets: none.`。恢复 `"orders"` 后运行 `./mvnw -f labs/spring-modulith/pom.xml clean verify`，确认结构测试和订单积分集成测试一起通过。

本次在 macOS arm64、OpenJDK 17.0.16、Maven Wrapper 3.9.11 上验证。Maven Central 连接失败后，复用本地缓存。执行上述两条命令时均附加 `-o -B -Dmaven.repo.local="$PWD/target/maven-repository"`。单项测试通过，空白名单使测试报告预期的依赖违规，恢复后 `clean verify` 共通过 5 项测试并完成打包。

- [Spring Modulith 1.4 模块结构验证](https://docs.spring.io/spring-modulith/reference/1.4/verification.html)
- [Spring Modulith 1.4 模块发现与显式依赖配置](https://docs.spring.io/spring-modulith/reference/1.4/fundamentals.html)
