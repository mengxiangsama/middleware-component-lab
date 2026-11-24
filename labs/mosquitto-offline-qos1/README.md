# Mosquitto + Paho MQTT 客户端离线后接收 QoS 1 消息

设备短时断网时，控制服务仍可能发出指令。Mosquitto 作为 MQTT broker 保存设备已订阅的主题和符合条件的离线消息。设备侧的 Python Paho 客户端用相同的客户端 ID 恢复会话后，可以接到这段时间发布的消息。本例用 `mode=eco` 模拟一条设备指令，使用 MQTT 3.1.1。

## 接入步骤

1. 订阅端分配稳定且唯一的 Client ID，以 `clean_session=False` 连接 Mosquitto。首次连接建立新会话，以 QoS 1 订阅设备主题，并等 `SUBACK` 确认 QoS 1。
2. 设备断开后，发送端向同一主题发布 QoS 1 消息，传入 `retain=False`，等 broker 返回 `PUBACK`。Mosquitto 把匹配的消息排入设备会话。
3. 设备重连时复用 Client ID 和 `clean_session=False`。当 `CONNACK` 的 `session_present=True` 时，之前的订阅仍在，Paho 通过 `on_message` 收到积压消息。如果返回 `False`，应用需要重新订阅。

## 运行验证

需要运行中的 Docker 和 Python 3。从仓库根目录执行以下命令。

```bash
docker pull eclipse-mosquitto:2.0.22@sha256:81a6193ae693942a3231cd3ac52875b02b7e3a19da671b2fb4b55cc874c5b279
venv_dir=$(mktemp -d)
python3 -m venv "$venv_dir"
"$venv_dir/bin/python" -m pip install -r labs/mosquitto-offline-qos1/requirements.txt
"$venv_dir/bin/python" labs/mosquitto-offline-qos1/verify.py
```

验证脚本在 `127.0.0.1` 的随机端口启动临时 Mosquitto 容器，退出时删除该容器。匿名连接用于本地示例，容器中的配置开启日志以核对离线顺序。脚本先等待 broker 确认订阅端已断开，再发布消息。重连时创建全新的 Paho 客户端，沿用同一个 Client ID，直接等待离线消息。最后以同一个 Client ID 清理会话，检查 broker 返回 `session_present=False`。

也可以使用本机 Mosquitto 2.0.22。准备同一个 Python 虚拟环境后，传入可执行文件。脚本为本机实例生成临时配置，只监听回环地址，并在验证后关闭该进程。

```bash
"$venv_dir/bin/python" labs/mosquitto-offline-qos1/verify.py --mosquitto /path/to/mosquitto
```

在 macOS ARM64 上可用官方 `v2.0.22` 源码编译这一只用明文回环连接的实验。

```bash
mosquitto_source=$(mktemp -d)
git clone --depth 1 --branch v2.0.22 https://github.com/eclipse-mosquitto/mosquitto.git "$mosquitto_source"
make -C "$mosquitto_source/src" WITH_TLS=no WITH_WEBSOCKETS=no WITH_CJSON=no WITH_WRAP=no WITH_DOCS=no
"$venv_dir/bin/python" labs/mosquitto-offline-qos1/verify.py --mosquitto "$mosquitto_source/src/mosquitto"
```

在 macOS 26.5 ARM64、Python 3.13.7 和 Paho MQTT 2.1.0 上，以本机编译的 Mosquitto 2.0.22 两次复测，均得到以下输出。此前同一场景也在 Docker Engine 27.3.1（Linux arm64）的官方 2.0.22 镜像上跑通。

```text
First connect: session_present=False, SUBACK granted QoS 1
Mosquitto confirmed subscriber disconnected
Offline publish: PUBACK received, QoS 1, retain=False
Reconnect: session_present=True, lab/devices/demo/command b'mode=eco', QoS 1, retain=False
Clean session control: session_present=False
```

此配置使用 `persistence false`，离线队列保存在运行中的 broker 内存中，验证范围覆盖客户端断开和重连。需要验证 broker 重启后的恢复时，要配置磁盘持久化并挂载数据目录。部署时还要按设备离线时长和消息量设置 `max_queued_messages` 与 `persistent_client_expiration` 等队列和会话参数。QoS 1 可能重复投递，应用处理设备指令时可以用指令 ID 去重。

## 官方资料

- [Eclipse Mosquitto 2.0.22 发布说明](https://mosquitto.org/blog/2025/07/version-2-0-22-released/)
- [Eclipse Mosquitto GitHub 仓库](https://github.com/eclipse-mosquitto/mosquitto)
- [Eclipse Mosquitto 官方镜像](https://hub.docker.com/_/eclipse-mosquitto/)
- [Mosquitto 配置手册](https://mosquitto.org/man/mosquitto-conf-5.html)
- [Paho Python 2.1.0 订阅示例](https://github.com/eclipse-paho/paho.mqtt.python/blob/v2.1.0/examples/client_sub.py)
- [Paho Python 客户端 API](https://eclipse.dev/paho/files/paho.mqtt.python/html/client.html)
- [MQTT 3.1.1 规范](https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/mqtt-v3.1.1.html)
