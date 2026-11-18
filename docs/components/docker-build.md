# Dockerfile 构建与本地验证

## Docker BuildKit

从仓库根目录构建 RocketMQ 事务实验。现有 Dockerfile 使用 Java 17 运行时，从 Maven 的 `target/` 复制可执行 JAR，最后以 exec 形式启动 Java。传给构建命令的 `.` 是构建上下文，`.dockerignore` 只把所需的 JAR 从 `target/` 纳入上下文。

```bash
./mvnw --batch-mode clean package
docker build -t rocketmq-transaction-lab:local .
docker image inspect rocketmq-transaction-lab:local --format '{{.Id}} {{.Os}}/{{.Architecture}}'
```

常用选项包括 `-f path/to/Dockerfile` 指定 Dockerfile、`-t` 指定镜像名和标签、`--platform` 指定目标平台。Buildx 使用独立 builder 时，单平台构建可加 `--load` 把结果载入本机镜像库。需要多平台镜像时，使用同时支持目标平台的基础镜像并选择能保存多平台结果的输出。 [Dockerfile 指令](https://docs.docker.com/reference/dockerfile/) · [构建上下文](https://docs.docker.com/build/concepts/context/) · [Buildx 选项](https://docs.docker.com/reference/cli/docker/buildx/build/)

**多阶段用法**。在第一个 `FROM` stage 内装入 JDK 和构建工具，执行 Maven 打包。第二个 stage 只放 JRE 与 `COPY --from=build` 得到的制品。构建失败时可以通过 `--target build` 检查构建阶段。用构建密钥时，使用 BuildKit secret mount 供特定的 `RUN` 指令读取，避免把凭证放进 `ARG`、`ENV` 或镜像层。 [多阶段构建](https://docs.docker.com/build/building/multi-stage/) · [Build secrets](https://docs.docker.com/build/building/secrets/)

**镜像验证**。下面两条命令检查镜像里的 Java 版本和 JAR 是否存在。应用启动还需要可达的 NameServer、Broker 广播地址和已创建的事务 Topic。容器内的 `127.0.0.1` 属于该容器，应用从宿主机启动时可按仓库实验连接 Compose。 [运行事务消息实验](../../README.md#run-locally)

```bash
docker run --rm --entrypoint java rocketmq-transaction-lab:local -version
docker run --rm --entrypoint sh rocketmq-transaction-lab:local -c 'test -s /app/app.jar'
```

## Docker Compose

- **常用功能**。用同一份 YAML 启动多个容器，声明环境变量、端口、网络、卷、健康检查与启动条件。先用 `docker compose config --quiet` 校验配置，再用 `docker compose up -d` 启动，通过 `docker compose ps -a` 与 `docker compose logs` 核对长运行服务和一次性初始化任务。 [Compose CLI](https://docs.docker.com/reference/cli/docker/compose/) · [启动顺序](https://docs.docker.com/compose/how-tos/startup-order/)
- **本仓库用法**。现有 `compose.yaml` 启动 RocketMQ NameServer、Broker 和 Topic 初始化任务。依赖项通过健康检查或成功退出后再启动。应用可按 README 从宿主机启动，检查 NameServer 和 Broker 广播地址。 [实验步骤](../../README.md#run-locally)
- **验证**。检查容器端口是否被占用、依赖是否 healthy、一次性初始化容器的退出码及应用连接日志。Compose 健康检查反映容器当前状态，Kubernetes 的就绪与存活探针分别由集群处理。 [Kubernetes Probes](release-tools.md#kubernetes-probes)
