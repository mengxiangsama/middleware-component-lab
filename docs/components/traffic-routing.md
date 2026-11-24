# 金丝雀与全链路灰度组件

以下用 `stable` 和 `canary` 标识两个版本。金丝雀可以按权重抽样，也可以按 Header 或 RPC 标签选定请求。验证时让两个版本在日志或测试响应中报告实际命中的版本。

## Istio

在测试网格中准备同一个 Service 下的两个版本，给 Pod 分别标记 `version: stable` 和 `version: canary`。用 `DestinationRule` 将标签声明为同名的 subset，用 `VirtualService` 按请求选择 subset。下面是一跳的规则，将测试命名空间和 Service 名替换为实际值。Header 键使用小写，HTTP 路由从上到下选第一条命中的规则。可先用官方 Bookinfo 跑同类操作。[请求路由](https://istio.io/latest/docs/tasks/traffic-management/request-routing/) · [VirtualService](https://istio.io/latest/docs/reference/config/networking/virtual-service/) · [DestinationRule](https://istio.io/latest/docs/reference/config/networking/destination-rule/)

```yaml
apiVersion: networking.istio.io/v1
kind: DestinationRule
metadata:
  name: catalog
  namespace: demo
spec:
  host: catalog.demo.svc.cluster.local
  subsets:
    - name: stable
      labels: {version: stable}
    - name: canary
      labels: {version: canary}
---
apiVersion: networking.istio.io/v1
kind: VirtualService
metadata:
  name: catalog
  namespace: demo
spec:
  hosts: [catalog.demo.svc.cluster.local]
  http:
    - name: marked-canary
      match:
        - headers:
            x-release-track:
              exact: canary
      route:
        - destination:
            host: catalog.demo.svc.cluster.local
            subset: canary
    - name: default-stable
      route:
        - destination:
            host: catalog.demo.svc.cluster.local
            subset: stable
```

- 从网格内发起请求，带 `x-release-track: canary` 时命中 canary，未带或值不同则命中 stable。按比例测试可在同一条 route 中给两个 destination 设置权重。请求命中 canary 规则后，subset 缺少可用端点时不会因为下面还有 stable 规则而重新匹配。[权重切流](https://istio.io/latest/docs/tasks/traffic-management/traffic-shifting/) · [流量问题排查](https://istio.io/latest/docs/ops/common-problems/network-issues/)
- 第二跳的出站 HTTP 请求需要携带标记，第二个目标也需要已经生效的匹配规则。用实际代理的路由、集群和端点视图核对规则下发，另查目标版本的日志。回退测试时将匹配规则指向 stable 或移除灰度规则并保留显式 stable 路由，再重测两跳。[Istioctl 排查](https://istio.io/latest/docs/ops/diagnostic-tools/istioctl/)

## Apache Dubbo

Tag Router 按每次 RPC 的 `dubbo.tag` 从候选 Provider 地址中筛选实例。启动测试 Provider 时给灰度实例配置 `-Ddubbo.provider.tag=canary`，stable Provider 保持无标签。也可在支持相应规则版本的治理配置中下发动态 TagRule。动态规则以 Provider 应用为粒度，匹配的是 Dubbo 实例地址携带的属性，需确认客户端确实看到了规则及属性。[Tag Routing Rules](https://dubbo.apache.org/en/overview/what/core-features/traffic/tag-rule/) · [静态与动态标签](https://dubbo.apache.org/en/overview/what/core-features/traffic/introduction/)

- Dubbo 3 可在 Consumer 调用前用 `RpcContext.getClientAttachment().setAttachment("dubbo.tag", "canary")`。Provider 通过 `RpcContext.getServerAttachment()` 读取本次入站标签。需要继续调用下游时，在该次出站调用前设置 `ClientAttachment`，并核对实际标签。Dubbo 3 默认清理调用上下文，不自动将上游附件带入下一跳，可用 `PenetrateAttachmentSelector` 显式选出需透传的键。老版本的附件 API 和透传行为不同。[Dubbo 3 附件传递](https://dubbo.apache.org/en/overview/mannual/java-sdk/tasks/framework/attachment/)
- 分别测试带标签、无标签和 canary Provider 下线。动态规则的 `force: true` 在标签子集为空时返回无 Provider 异常，`force: false` 可回退至无标签子集。停用动态规则前先确认是否仍有静态标签，并复测 Consumer 实际命中的版本。[TagRule 字段](https://dubbo.apache.org/en/overview/what/core-features/traffic/tag-rule/) · [流量隔离示例](https://dubbo.apache.org/en/overview/mannual/java-sdk/tasks/traffic-management/isolation/)

## Spring Cloud Gateway

运行 [WebFlux Header 路由实验](../../labs/spring-cloud-gateway/README.md)，用两个本地 HTTP 上游验证精确匹配和 Header 转发，再停止灰度上游核对响应以及稳定版是否收到请求。

在 Server WebFlux 的路由里用 `Header` 谓词匹配入站请求。下面的两个本地端口应分别运行 canary 和 stable 测试服务，并能处理 `/catalog/**`。匹配灰度的路由顺序在通用路由之前。此 YAML 使用新版 WebFlux 前缀，4.2 参考文档使用 `spring.cloud.gateway.routes`，升级时按对应版本核对配置键。Header 的值使用 Java 正则表达式。[新版 Header 谓词](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/request-predicates-factories.html) · [4.2 路由谓词](https://docs.spring.io/spring-cloud-gateway/reference/4.2/spring-cloud-gateway/request-predicates-factories.html)

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: catalog-canary
              uri: http://127.0.0.1:8082
              order: -1
              predicates:
                - Path=/catalog/**
                - Header=X-Release-Track, ^canary$
            - id: catalog-stable
              uri: http://127.0.0.1:8081
              order: 0
              predicates:
                - Path=/catalog/**
```

- 发同一路径的两次请求，分别带和不带 `X-Release-Track: canary`。在测试环境可用只读 `/actuator/gateway/routes` 查看路由 ID、匹配条件、顺序和 URI，再用应用日志确认实际命中的版本。若灰度服务不可达，Header 谓词本身没有失败回退能力。回退时从配置源撤下灰度路由并确认路由已经重新加载。[Actuator 路由查询](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/actuator-api.html)
- Header 谓词只匹配 Gateway 收到的这一跳。核对第一跳收到的 Header，因为 `RemoveRequestHeader` 等过滤器可以移除它。继续向第二跳发起请求时，由该 HTTP 客户端明确携带标记，并核对第二跳的实际路由条件。[RemoveRequestHeader](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/removerequestheader-factory.html)

## 逐跳验证

1. 在测试环境对同一路径发带标记和不带标记两种请求。按同一个请求 ID 查看入口及连续两个 HTTP 或 RPC 跳，分别记录收到的标记、命中的规则和目标实例版本。
2. 让其中一跳漏传标记，再将灰度目标下线，最后应用回退配置。分别验证无标记请求、灰度目标缺失时的处理和回退后的实际路由。
3. 测试入口只接受可信来源设置的灰度标记。再发一条外部伪造标记的请求，核对它是否按入口规则被清除或忽略。HTTP Header 和 RPC 附件属于对应调用边界，进入消息或调度任务时检查实际消息或任务参数。
