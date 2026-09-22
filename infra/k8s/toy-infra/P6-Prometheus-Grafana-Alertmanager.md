我已经翻了几次相关会话，拼出了目前的进度拼图。整理如下，你确认一下有没有出入，或者我漏掉的部分，确认完就可以直接进 P6 了。

目前掌握的状态

基础设施决策（已在多次会话中定案，写进过 homelab-toy-system-plan.md）：

MySQL/Redis 常驻宿主机（Docker），k3s 内通过"无 selector Service + EndpointSlice"访问
Kafka（Strimzi Operator）、Elasticsearch（ECK Operator）部署在 k3s 内 toy-infra namespace
私有镜像仓库 registry:2 跑在宿主机 192.168.40.23:5000
3 节点集群：ziqiao-asm100（控制面，裸机）+ k3s-node2（2018 MBP，Multipass/VirtualBox）+ k3s-node3（旧 iMac，同上）

P5 进度（你刚补充的）：

5 个业务镜像（gateway-service、policy-service、notification-service、search-service、frontend）已 push 到自建 registry
Kafka 从 1 broker 扩到 3 broker（min.insync.replicas: 2），ES 从 1 节点扩到 3 节点，都用 local PV + nodeAffinity 绑定到具体主机名——这个原本是"P5.5 插入式练习"，看起来你已经提前完成了

P6 阶段已经预先定好的方案（早前会话里讨论过，还没实际动手）：

Prometheus/Grafana 装集群内（toy-infra），理由：只负责观察不负责修复，且要频繁 scrape 集群内 Pod 的 /actuator/prometheus
宿主机 MySQL/Redis 监控：额外装 mysqld_exporter/redis_exporter，复用 P5 学过的"无 selector Service"技巧从集群内抓取
可观测性三支柱：Metrics(Prometheus)+可视化(Grafana)+告警(Alertmanager)，Tracing(Jaeger/Zipkin)算进阶项，先不做
有没有我漏掉/记错的地方？

比如：这 5 个镜像目前是否已经在 k3s 里 kubectl apply 跑起来了（也就是 P5 主线本身是否完成），还是只完成了"扩容练习"这个插曲？如果 P5 主线还没跑完，我们可能要先确认服务都跑起来产生真实流量，再进 P6（因为 P6 的价值就是"观察真实流量"）。



## P5应用阶段已经完成，可以进p6。应用是否需要改造？
需要：给 4 个 Java 服务加一个依赖 + 一行配置，不算"改造"

中文：

好消息是改动很小，因为你的服务已经在用 Spring Boot Actuator（/actuator/health 之前就有），Prometheus 集成只是多暴露一个端点，不涉及业务逻辑改动。

对 gateway-service、policy-service、notification-service、search-service 这 4 个 Java 服务，各加：

1. Maven 依赖（pom.xml）：

xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>

2. application.yml 里补一行，把 prometheus 加进已暴露的端点列表：

yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus

加完后访问 /actuator/prometheus，就能看到 Prometheus 格式的指标文本（JVM 内存、GC、HTTP 请求延迟/QPS 等，micrometer 自动埋点，不用手写）。

frontend（Node+nginx）不用改——它没有 JVM，业务侧价值有限，先跳过，之后想练可以加 nginx-prometheus-exporter 作为 sidecar。

Kafka/ES 不用你改代码——Strimzi 和 ECK 这两个 Operator 都自带 Prometheus 指标暴露的开关（Strimzi 的 spec.kafka.metricsConfig、ECK 的 monitoring 配置），是 YAML 层面开启，不是应用代码层面。

MySQL/Redis 在宿主机，跟应用代码完全无关——那边是装 mysqld_exporter/redis_exporter 这两个独立进程，纯基础设施操作。

所以严格说，"改造"这个词有点重了——更准确地说是"给已有的 Actuator 多打开一扇窗"。