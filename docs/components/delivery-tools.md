# Kubernetes 交付与镜像仓库

以下示例使用测试命名空间和测试项目。开始前确认当前 Kubernetes context、命名空间和镜像仓库地址。

## Helm

Helm 用 Chart 定义模板和默认值，以 release 为单位安装、升级和回滚。下面在空目录生成一个示例 Chart，先跑 `lint` 和本地渲染，再安装到测试命名空间。[Helm 快速开始](https://helm.sh/docs/intro/quickstart/) · [Helm 升级选项](https://helm.sh/docs/helm/helm_upgrade/)

```bash
helm create demo
helm lint ./demo
helm template demo ./demo -n helm-lab
helm upgrade --install demo ./demo -n helm-lab --create-namespace --wait --timeout 2m
helm history demo -n helm-lab
```

- **配置与升级**。将测试环境的覆盖项写入 `values-lab.yaml`，渲染和升级时均加 `-f values-lab.yaml`。本地渲染可核对镜像、端口和资源名称，实际 Kubernetes API 与准入检查取决于目标集群。[模板命令](https://helm.sh/docs/helm/helm_template/)
- **故障演练**。安装成功后，在测试环境将 `image.repository` 改为无法拉取的仓库并带 `--wait --timeout 60s` 升级。检查 Pod 事件和 `helm history demo -n helm-lab`。如果历史中健康版本号为 `1`，执行 `helm rollback demo 1 -n helm-lab --wait --timeout 2m`，确认 Pod 恢复就绪且镜像回到预期版本。[历史](https://helm.sh/docs/helm/helm_history/) · [回滚](https://helm.sh/docs/helm/helm_rollback/)
- **CRD 与 Hook**。`crds/` 中的定义在安装时优先注册，已有 CRD 会跳过。CRD 更新和删除需要独立处理。Hook 可以放置 `pre-upgrade` 等 Job，配置 `helm.sh/hook-delete-policy` 或 Job TTL 清理资源。回滚 release 后仍需检查 Hook 执行结果和它写入的外部数据。[CRD](https://helm.sh/docs/chart_best_practices/custom_resource_definitions/) · [Chart Hook](https://helm.sh/docs/topics/charts_hooks/)

## Harbor

Harbor 按项目管理镜像和权限。先在 Harbor 界面创建私有测试项目 `lab`，将 `registry.example.invalid` 换成自己的测试地址，使用允许推送该项目的账号。以下示例假定已有本地镜像 `demo:local`。[Docker 客户端推拉镜像](https://goharbor.io/docs/2.14.0/working-with-projects/working-with-images/pulling-pushing-images/)

```bash
REGISTRY=registry.example.invalid
IMAGE="$REGISTRY/lab/demo"
docker login "$REGISTRY"
docker tag demo:local "$IMAGE:demo-1"
docker push "$IMAGE:demo-1"
docker pull "$IMAGE:demo-1"
```

- **标识与授权**。在 Artifacts 页面或推送输出记录 digest，需要固定镜像内容时按 `仓库地址/项目/镜像名@sha256:实际摘要` 拉取。给 CI 创建有期限的项目 Robot Account，按用途授予权限。推送权限须配套拉取权限，仅部署时可授予拉取权限。把 Robot Secret 注入 CI 密钥变量，登录时通过 `--password-stdin` 传入。[标签不可变规则](https://goharbor.io/docs/2.14.0/working-with-projects/working-with-images/create-tag-immutability-rules/) · [项目 Robot Account](https://goharbor.io/docs/2.14.0/working-with-projects/project-configuration/create-robot-accounts/)
- **扫描前提**。Harbor 需要一个可用且已配置的扫描器，例如安装时启用的 Trivy，或之后接入的扫描器。为项目确认扫描器和漏洞库状态，再手动扫描制品或启用推送后自动扫描，在 Artifacts 查看报告状态。[扫描器配置](https://goharbor.io/docs/2.14.0/administration/vulnerability-scanning/)
- **故障演练**。在私有测试项目对 `demo-*` 启用标签不可变规则，尝试将内容不同的测试镜像推到已存在的 `demo-1`。确认推送被拒绝、原标签的 digest 未变。另用仅拉取的 Robot Account 验证拉取成功且推送被拒绝。[标签不可变规则](https://goharbor.io/docs/2.14.0/working-with-projects/working-with-images/create-tag-immutability-rules/)

## Argo CD

Argo CD 从 Git 的指定版本渲染并同步 Kubernetes 清单，通过 `Synced` 查看期望与实际配置是否一致，通过 `Healthy` 查看资源健康状态。下面保存为 `application.yaml`，假定 Argo CD 已安装在 `argocd`，本集群的 `delivery-lab` 测试命名空间已存在且允许同步。运行 `argocd app` 命令前先用 CLI 登录测试 Argo CD。[Application 字段](https://argo-cd.readthedocs.io/en/stable/user-guide/application-specification/) · [快速开始](https://argo-cd.readthedocs.io/en/stable/getting_started/)

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: delivery-demo
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/argoproj/argocd-example-apps.git
    targetRevision: HEAD
    path: guestbook
  destination:
    server: https://kubernetes.default.svc
    namespace: delivery-lab
```

```bash
kubectl apply -f application.yaml
argocd app diff delivery-demo
argocd app sync delivery-demo
argocd app wait delivery-demo --sync --health --timeout 120
```

- **同步选项**。先用手动同步验证。需要自动同步时，启用 `spec.syncPolicy.automated`。`prune: true` 允许自动删除已从期望清单移除的受管资源，`selfHeal: true` 用于纠正集群内的配置漂移。启用自动同步时，通常通过 Git 回退变更再同步，使用 Argo CD 的历史回滚前需关闭自动同步。[自动同步选项](https://argo-cd.readthedocs.io/en/stable/user-guide/auto_sync/) · [差异命令](https://argo-cd.readthedocs.io/en/stable/user-guide/commands/argocd_app_diff/) · [等待同步与健康](https://argo-cd.readthedocs.io/en/stable/user-guide/commands/argocd_app_wait/)
- **故障演练**。在测试 Application 中把 `source.path` 改为不存在的目录，检查应用条件中的清单生成错误。恢复正确路径并再次同步后，核对 `Synced` 和 `Healthy`。还可以在测试环境手动改副本数，比较启用 `selfHeal` 前后的差异和同步行为。[资源健康](https://argo-cd.readthedocs.io/en/stable/operator-manual/health/) · [自动同步选项](https://argo-cd.readthedocs.io/en/stable/user-guide/auto_sync/)
- **关联组件**。Argo CD 负责清单同步，Argo Rollouts 负责金丝雀、流量权重和分析步骤。[Argo Rollouts 用法](release-tools.md#argo-rollouts)
