# 发布、探针与 SQL 变更工具

## Kubernetes Deployment

- **常用功能**。Deployment 的 `RollingUpdate` 分批创建新 Pod、缩减旧 Pod。`maxSurge` 控制可超出期望副本数的额外副本，`maxUnavailable` 控制滚动期间允许不可用的副本数。`minReadySeconds` 控制新 Pod 持续就绪多久后才计为可用。 [Deployment 滚动更新](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/#rolling-update-deployment)
- **最小接入**。在测试命名空间给应用设好 Service 和 readiness，更新 Pod 模板的镜像标签后用 `kubectl rollout status deployment/demo` 观察进度。发布异常时用 `kubectl describe deployment demo` 和 Pod 事件排查。保留有用的修订时，可用 `kubectl rollout undo deployment/demo` 回到上一修订。 [查看发布状态与回滚](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/)
- **验证**。保持连续请求，发布慢启动版本和无法就绪的版本。检查 ready 副本、请求错误率以及回滚完成后的实际镜像。Pod 退出期间还应核对负载均衡停止送流与应用完成在途请求的时间。 [Pod 停止流程](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#pod-termination)

## Kubernetes Probes

下面的字段放在容器条目下，示例假定应用在名为 `http` 的容器端口提供 `/livez` 和 `/readyz`。按应用的实际冷启动时间、调用时延和重试阈值调整示例数值。探针也支持 TCP、exec 或 gRPC 检查，按集群版本及应用协议选择。 [官方探针配置](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/)

```yaml
startupProbe:
  httpGet: {path: /livez, port: http}
  periodSeconds: 5
  failureThreshold: 24
readinessProbe:
  httpGet: {path: /readyz, port: http}
  periodSeconds: 5
livenessProbe:
  httpGet: {path: /livez, port: http}
  periodSeconds: 10
```

| 探针 | 检查什么 | 连续失败达到阈值 |
| --- | --- | --- |
| `startupProbe` | 应用是否完成启动。成功前 Kubernetes 暂不执行其他两类探针 | 重启容器 |
| `readinessProbe` | 应用当前能否接收 Service 流量 | Pod 标为未就绪，退出正常的 Service 路由，进程继续运行 |
| `livenessProbe` | 进程是否进入只能重启来恢复的状态 | 重启容器 |

**验证**。延长冷启动，检查 startup 探针期间容器没有被过早重启。分别让 readiness 与 liveness 失败，核对 EndpointSlice 就绪状态和重启计数。liveness 使用应用自身状态，避免共享数据库或缓存抖动同时重启所有实例。 [探针触发行为](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/)

## Spring Boot Actuator

- **常用功能**。Actuator 可暴露 `/actuator/health/liveness` 与 `/actuator/health/readiness`。在 Kubernetes 之外验证时可用 `management.endpoint.health.probes.enabled=true` 启用。若管理端口独立，设置 `management.endpoint.health.probes.add-additional-paths=true` 可在主服务端口加 `/livez` 与 `/readyz`，用实际承载请求的端口做检查。 [Actuator 探针](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes)
- **优雅停机**。按版本确认 `server.shutdown=graceful`，用 `spring.lifecycle.timeout-per-shutdown-phase` 设置生命周期阶段的等待上限。容器需把 SIGTERM 送到 Java 进程，终止宽限期包含 `preStop` 和应用退出所需时间。 [Spring Boot 优雅停机](https://docs.spring.io/spring-boot/3.5/reference/web/graceful-shutdown.html) · [Pod 停止流程](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#pod-termination)
- **验证**。测试环境发起一个耗时请求后发送 SIGTERM。观察就绪状态、后续请求、在途请求和实际退出时间。消息消费者、后台执行器及长连接分别核对其关闭行为。

## Argo Rollouts

- **灰度发布**。安装控制器与 CRD，使用 `Rollout` 的 `strategy.canary` 设置分步发布。下例在 10% 步骤暂停，检查后执行 `kubectl argo rollouts promote demo`，或用 `kubectl argo rollouts abort demo` 停止此次灰度。仅用副本数时，权重按副本比例近似。需要请求级流量控制时配置 stable/canary Service 和受支持的 `trafficRouting`。 [Canary 策略](https://argoproj.github.io/argo-rollouts/features/canary/) · [快速开始与操作](https://argoproj.github.io/argo-rollouts/getting-started/)

```yaml
strategy:
  canary:
    steps:
      - setWeight: 10
      - pause: {}
```

- **指标验证**。先通过响应中的版本标识抽查目标权重是否反映到实际请求。需要自动判定时，用 `AnalysisTemplate` 查询新版本错误率、延迟或其他指标，在测试中分别触发通过、失败和数据缺失，并核对暂停、晋级、中止后的真实流量。中止后核对稳定版本路由，另行检查镜像、配置和数据库实际状态。 [AnalysisRun 与判定条件](https://argoproj.github.io/argo-rollouts/features/analysis/)

## Flyway

- **常用功能**。用版本化 SQL 文件管理数据库结构与数据脚本，例如 `V2__add_order_status.sql`。通过 `info` 检查待执行脚本，在一次性测试库运行 `migrate`，随后用 `validate` 核对已应用脚本的校验和。 [迁移规则](https://documentation.red-gate.com/flyway/flyway-concepts/migrations) · [校验命令](https://documentation.red-gate.com/flyway/reference/commands/validate)
- **验证**。以旧结构和样本数据执行一次升级，检查列、索引、默认值及既有数据，再重复执行并核对 schema history。数据库的 DDL 事务能力决定失败时可否自动回滚。已应用脚本的修订通常写入新版本。

## Liquibase

- **常用功能**。在 changelog 中用 changeset 管理变更。配置测试数据库和 changelog 后运行 `validate` 校验文件，`update-sql` 查看将执行的 SQL，`update` 应用并在 `DATABASECHANGELOG` 记录。changeset 通过 ID、作者和路径标识。 [update 命令](https://docs.liquibase.com/community/reference-guide-5-0/init-update-and-rollback-commands/update) · [validate 命令](https://docs.liquibase.com/community/reference-guide-5-0-3/database-inspection-change-tracking-and-utility-commands/validate)
- **验证**。在接近目标库版本的环境试跑迁移、核对数据与 changeset 记录，并对能回滚的 changeset 单独执行回滚演练。检查非事务 DDL 失败时数据库的实际结构。跨库持续同步的组件见 [SQL 数据同步](data-pipelines.md#sql-数据同步)。
