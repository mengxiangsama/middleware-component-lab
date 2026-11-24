# Kustomize + kubeconform 校验环境清单

订单服务的预发和生产部署共用端口、容器和标签，分别设置命名空间、副本数和镜像版本。将公用的 Deployment 放在 Kustomize base，具体差异放在 overlays。CI 先渲染对应环境，再用 kubeconform 校验交付给 Kubernetes 的 YAML，提前发现环境补丁写入的字段类型错误。

| 组件 | 分工 |
| --- | --- |
| [Kustomize](../../docs/components/delivery-tools.md#kustomize) | 合并 base 和当前环境的覆盖项，生成完整的 Deployment。 |
| [kubeconform](../../docs/components/delivery-tools.md#kubeconform) | 用目标 Kubernetes 版本的 JSON Schema 校验渲染结果，非法字段由退出码和错误路径反馈。 |

## 接入步骤

1. 在 [base](base/) 声明订单 Deployment。预发和生产的 `kustomization.yaml` 都在 `resources` 引用 base。
2. 分别在 [预发 overlay](overlays/staging/kustomization.yaml) 和 [生产 overlay](overlays/production/kustomization.yaml) 配置 `namespace`、`replicas` 和 `images.newTag`。检查生产使用正式镜像和 3 个副本，预发使用候选镜像和 1 个副本。
3. 将 `kustomize build` 的成功输出交给 kubeconform，开启 `-strict`，并用 `-kubernetes-version` 对齐目标集群。本实验使用 Kubernetes 1.35.0 的 Deployment Schema。CI 在渲染或校验失败时终止，便于回到出错的 overlay 修复。

## 验证方法

将官方发行的 Kustomize 5.8.1 和 kubeconform 0.8.0 加入 `PATH`。需要 Shell、curl 和到 GitHub 原始文件的网络连接。脚本将固定提交的 Deployment Schema 下载到临时目录，两个校验过程均在本机运行，无需 Kubernetes 集群或 Docker。

```sh
sh labs/kustomize-overlays/verify.sh
```

| 输入 | 应观察到的结果 |
| --- | --- |
| `overlays/staging` | `orders-staging`、1 个副本、镜像标签 `1.4.0-rc.1`，1 个 Deployment 校验通过 |
| `overlays/production` | `orders-production`、3 个副本、镜像标签 `1.4.0`，1 个 Deployment 校验通过 |
| `overlays/invalid` | 在生产配置上用 JSON Patch 把 `replicas` 改成字符串 `"3"`。Kustomize 渲染成功，kubeconform 以非零状态退出并定位到 `/spec/replicas` |

该 Schema 只用于示例中的 Deployment。真实发布校验应覆盖渲染出的所有资源，包括应用使用的 CRD，并按集群环境验证准入规则、命名空间和镜像等运行条件。示例中的镜像地址仅用于核对渲染。

## 实测记录

2026-11-24 在 macOS 26.5 ARM64 上运行 `sh labs/kustomize-overlays/verify.sh`，使用官方二进制 Kustomize v5.8.1、kubeconform v0.8.0，以及 Kubernetes v1.35.0 的严格 Deployment Schema。预发和生产各通过 1 个资源的校验，命名空间、副本数和镜像标签均匹配预期。错误 overlay 正常渲染，kubeconform 退出码为 1，报告 `/spec/replicas` 的实际类型为字符串。整个实验无需连接 Kubernetes 集群。

## 官方资料

- [Kustomize v5.8.1](https://github.com/kubernetes-sigs/kustomize/releases/tag/kustomize%2Fv5.8.1) · [Kubernetes 文档中的 base、overlay 和 patches](https://kubernetes.io/docs/tasks/manage-kubernetes-objects/kustomization/#bases-and-overlays)
- [kubeconform v0.8.0 使用手册](https://github.com/yannh/kubeconform/blob/v0.8.0/Readme.md) · [实验所用 Deployment Schema](https://github.com/yannh/kubernetes-json-schema/blob/0c4dd99d9873f7b4c2da24c008476f5841adbe2d/v1.35.0-standalone-strict/deployment-apps-v1.json)
