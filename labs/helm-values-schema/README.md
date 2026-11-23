# 用 Helm Schema 校验环境覆盖参数

## 适用场景

同一个 Chart 部署到多个环境时，环境文件里的端口可能被填成字符串，也可能把 `port` 拼成 `prt`。后一种情况会让模板继续读取默认端口，渲染看起来正常，实际使用的却是旧配置。可以在 Chart 根目录加上 `values.schema.json`，在提交发布前用 Helm 检出这类问题。

## 接入步骤

1. 在 Chart 根目录保留 [`values.yaml`](chart/values.yaml) 作为默认配置，旁边放 [`values.schema.json`](chart/values.schema.json)。示例要求 `replicaCount` 是至少为 1 的整数，`service.port` 是 1 到 65535 的整数。
2. 对键集合固定的 `service` 对象设置 `additionalProperties: false`，使新增的拼错键和保留的默认键同时存在时也能报错。若字段本身是允许自定义键的映射，例如注解，在那个映射上保留扩展能力。
3. 环境覆盖文件放在 Chart 外。示例 [`values-staging.yaml`](values-staging.yaml) 通过 `-f` 把副本数改为 2、Service 端口改为 8080。Service 通过名为 `http` 的目标端口转发到容器的 80 端口。

Helm 用 Schema 检查合并后的 `.Values`。有默认值的必填字段可以在环境覆盖文件里省略，默认值仍由 `values.yaml` 提供。模板和 Schema 新增或修改字段时一起更新。

## 验证方法

安装 [Helm 4.3.2](https://github.com/helm/helm/releases/tag/v4.3.2)，将 `helm` 所在目录加入 `PATH`。命令只做本地检查和渲染，无需 Kubernetes 集群。

在仓库根目录运行以下命令，查看生成的 Deployment 和 Service。

```sh
lab=labs/helm-values-schema
helm lint --strict "$lab/chart" -f "$lab/values-staging.yaml"
helm template orders-lab "$lab/chart" -f "$lab/values-staging.yaml"
```

运行 [验证脚本](verify.sh) 可一次复现默认配置、环境覆盖和各类错误。全部符合预期时脚本退出码为 0。

```sh
sh labs/helm-values-schema/verify.sh
```

| 输入 | 预期结果 |
| --- | --- |
| 默认配置 | `lint` 通过，渲染 1 个副本和 Service 端口 80 |
| `-f values-staging.yaml` | `lint` 通过，渲染 2 个副本和 Service 端口 8080 |
| `--set service.port=http` | 渲染失败，`/service/port` 类型错误 |
| `--set service.port=65536` | 渲染失败，`/service/port` 超过最大值 |
| `--set service.prt=8080` | 渲染失败，`/service` 下存在未知键 `prt` |
| `--set replicaCount=0` | 渲染失败，`/replicaCount` 小于最小值 |

迁移到现有 Chart 时，用每个实际环境的 `-f` 文件和发布时的 `--set` 参数运行同样的检查。目标集群的 API 兼容性、准入和 Pod 就绪情况仍要在部署验证中检查。

## 官方资料

- [Helm 官方仓库](https://github.com/helm/helm)与 [Helm 4.3.2 官方 Chart Schema 示例](https://github.com/helm/helm/blob/v4.3.2/pkg/cmd/testdata/testcharts/chart-with-schema/values.schema.json)
- [Chart Schema 与合并后的 values](https://helm.sh/docs/topics/charts/#schema-files)和 [values 覆盖顺序](https://helm.sh/docs/chart_template_guide/values_files/)
- [`helm lint`](https://helm.sh/docs/helm/helm_lint/)和 [`helm template`](https://helm.sh/docs/helm/helm_template/)
