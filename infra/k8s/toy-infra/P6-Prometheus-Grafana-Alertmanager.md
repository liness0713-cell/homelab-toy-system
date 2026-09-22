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

## 4个java应用外
1. Frontend — nginx-prometheus-exporter sidecar

中文：

nginx 本身不会主动暴露 Prometheus 格式指标，需要两步：① nginx 开一个 stub_status 内部页面（只报请求数/连接数这类基础指标，不是 QPS 细分）；② 用官方 nginx-prometheus-exporter 镜像作为 sidecar，去抓这个内部页面再转成 Prometheus 格式对外暴露。

Step 1 — nginx.conf 里加一段（只监听 localhost，不对外网开放）：

nginx
server {
    listen 127.0.0.1:8080;
    location /nginx_status {
        stub_status on;
        access_log off;
    }
}

这段要打进你 frontend 镜像里（改 Dockerfile 里 COPY 的 nginx.conf）。

Step 2 — Deployment 加一个 sidecar 容器：

yaml
containers:
  - name: frontend
    image: your-registry:5000/frontend:latest
    ports:
      - containerPort: 80
  - name: nginx-exporter
    image: nginx/nginx-prometheus-exporter:1.1.0
    args:
      - -nginx.scrape-uri=http://127.0.0.1:8080/nginx_status
    ports:
      - containerPort: 9113

两个容器共享同一个 Pod 网络命名空间，sidecar 才能直接访问 127.0.0.1:8080——这是理解 sidecar 模式的关键点：同 Pod 内容器互相可见。之后 Prometheus 就去 scrape 这个 Pod 的 9113 端口。

2. Kafka（Strimzi）—— JMX Exporter 配置

中文：

这里要纠正我之前说法里一个不够精确的地方：Strimzi 不是"自带一个开关就能用"，而是自带对接 JMX Exporter 的机制，规则本身要你自己写一份 ConfigMap（因为具体要暴露哪些 JMX 指标因人而异）。社区已经有现成的推荐规则可以直接抄。

Step 1 — 建 ConfigMap（放常见的 JMX 指标映射规则）：

yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: kafka-metrics-config
  namespace: toy-infra
data:
  kafka-metrics-config.yml: |
    lowercaseOutputName: true
    rules:
      - pattern: "kafka.server<type=(.+), name=(.+)PerSec\\w*, topic=(.+)><>Count"
        name: "kafka_server_$1_$2_total"
        labels:
          topic: "$3"
      - pattern: "kafka.server<type=(.+), name=(.+)PerSec\\w*><>Count"
        name: "kafka_server_$1_$2_total"

（完整版规则可以直接抄 Strimzi 官方仓库 examples/metrics/kafka-metrics.yaml，比自己写全面）

Step 2 — 在 Kafka CR 里引用：

yaml
apiVersion: kafka.strimzi.io/v1beta2
kind: Kafka
metadata:
  name: my-cluster
spec:
  kafka:
    metricsConfig:
      type: jmxPrometheusExporter
      valueFrom:
        configMapKeyRef:
          name: kafka-metrics-config
          key: kafka-metrics-config.yml

Strimzi Operator 看到这段配置后，会自动往每个 Kafka broker Pod 里注入 JMX Exporter agent，并在 9404 端口暴露 /metrics。你可以直接 kubectl apply 更新已有的 Kafka CR，Operator 会做滚动重启。

3. Elasticsearch（ECK）—— 用 elasticsearch_exporter，而不是"内置开关"

中文：

这里也要纠正一下：ES 本身没有内置的 Prometheus 端点开关，之前的说法不够准确。ECK 的 monitoring 字段对接的是 Elastic 自家的 Stack Monitoring（把指标写进另一个 ES 索引，给 Kibana 用），不是 Prometheus 格式。

想要真正的 Prometheus 格式，业界通用做法是部署社区维护的独立组件 elasticsearch_exporter（prometheus-community 维护），原理和你即将装的 mysqld_exporter 完全一样——一个独立进程，调用 ES 的 REST API 拉数据，转成 Prometheus 格式暴露出来。

yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: elasticsearch-exporter
  namespace: toy-infra
spec:
  replicas: 1
  selector:
    matchLabels: {app: es-exporter}
  template:
    metadata:
      labels: {app: es-exporter}
    spec:
      containers:
        - name: es-exporter
          image: quay.io/prometheuscommunity/elasticsearch-exporter:v1.7.0
          args:
            - --es.uri=http://elasticsearch-es-http:9200
          ports:
            - containerPort: 9114
---
apiVersion: v1
kind: Service
metadata:
  name: elasticsearch-exporter
  namespace: toy-infra
spec:
  selector: {app: es-exporter}
  ports:
    - port: 9114

--es.uri 填 ECK 自动创建的 ES Service 名字（一般是 <集群名>-es-http，用 kubectl get svc -n toy-infra 确认一下实际名字）。

4. 宿主机 MySQL/Redis exporter —— 复用你已经会的"无 selector Service"

中文：

这步纯基础设施操作，跟应用代码无关，步骤和你连接宿主机 MySQL/Redis 本身是同一套模式，只是这次连的是 exporter 进程而不是数据库本身。

Step 1 — 宿主机上跑 exporter（Docker 常驻，和 MySQL/Redis 同级）：

bash
docker run -d --name mysqld-exporter --restart unless-stopped \
  -p 9104:9104 \
  -e DATA_SOURCE_NAME="exporter:密码@(127.0.0.1:3306)/" \
  prom/mysqld-exporter

docker run -d --name redis-exporter --restart unless-stopped \
  -p 9121:9121 \
  -e REDIS_ADDR="redis://127.0.0.1:6379" \
  oliver006/redis_exporter

（exporter 这个 MySQL 账号需要单独建，只给 PROCESS、REPLICATION CLIENT 等只读监控权限，不要用 root）

Step 2 — k3s 里建"无 selector Service + EndpointSlice"（跟你连宿主机 MySQL 时那套 YAML 长得几乎一样，port 换成 9104/9121）：

yaml
apiVersion: v1
kind: Service
metadata:
  name: mysqld-exporter
  namespace: toy-infra
spec:
  ports:
    - port: 9104
---
apiVersion: discovery.k8s.io/v1
kind: EndpointSlice
metadata:
  name: mysqld-exporter-eps
  namespace: toy-infra
  labels:
    kubernetes.io/service-name: mysqld-exporter
addressType: IPv4
ports:
  - port: 9104
endpoints:
  - addresses: ["192.168.40.23"]

Redis 那份照抄一遍，改名字和端口即可。

## 先从nginx开始，还能暴露更多不，能暴尽暴
能暴尽暴：stub_status 只是基础款，VTS 才是"全家桶"

中文：

你现在这份 nginx.conf 什么监控相关配置都还没加。要说"暴尽暴"，实际上有两个档位，先说清楚再选：

档位	模块	能看到什么	代价
基础	内置 stub_status	活跃连接数、总请求数、accepts/handled	零改动，官方镜像自带
拉满	第三方 nginx-module-vts	按 server/location 拆分的：请求数、按状态码分类计数（2xx/3xx/4xx/5xx）、请求/响应字节数、响应时间直方图（P50/P90/P99级别的分布数据）	官方 nginx 镜像没编译进这个模块，得自己从源码编译一个镜像

既然你说"能暴尽暴"，那就上 VTS——这也是业界如果想要 nginx 层面细粒度可观测性的标准做法（比自己解析 access log 划算很多）。

Step 1：改 Dockerfile，编译带 VTS 模块的 nginx

你 frontend 的 Dockerfile 目前应该是类似 FROM nginx:alpine 直接 COPY 静态文件。换成多阶段构建，先编译一个带 VTS 的 nginx：

dockerfile
# ---- Stage 1: 编译带VTS模块的nginx ----
FROM alpine:3.19 AS nginx-builder
ARG NGINX_VERSION=1.25.4
RUN apk add --no-cache build-base pcre-dev zlib-dev openssl-dev git wget linux-headers

RUN git clone --depth 1 https://github.com/vozlt/nginx-module-vts.git /tmp/nginx-module-vts && \
    wget -O /tmp/nginx.tar.gz https://nginx.org/download/nginx-${NGINX_VERSION}.tar.gz && \
    tar zxf /tmp/nginx.tar.gz -C /tmp

WORKDIR /tmp/nginx-${NGINX_VERSION}
RUN ./configure \
      --prefix=/usr/local/nginx \
      --add-module=/tmp/nginx-module-vts \
      --with-http_ssl_module \
      --with-http_v2_module \
      --with-http_stub_status_module && \
    make -j$(nproc) && make install

# ---- Stage 2: 运行阶段，只拷编译产物 ----
FROM alpine:3.19
RUN apk add --no-cache pcre zlib openssl
COPY --from=nginx-builder /usr/local/nginx /usr/local/nginx
ENV PATH="/usr/local/nginx/sbin:${PATH}"

COPY --from=frontend-build /app/dist /usr/share/nginx/html   # 你原来COPY静态文件那行，保留
COPY nginx.conf /usr/local/nginx/conf/nginx.conf

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]

（--with-http_stub_status_module 顺手也编进去，两种都能用，不冲突）

Step 2：nginx.conf 加 VTS 配置
nginx
http {
    # 声明一块共享内存区，VTS用它存所有统计数据
    vhost_traffic_status_zone shared:vts_zone:10m;

    server {
        listen 80;

        location / {
            root /usr/share/nginx/html;
            try_files $uri $uri/ /index.html;
        }

        # VTS的JSON状态页——只在集群内网访问，不对外暴露
        location /vts_status {
            vhost_traffic_status_display;
            vhost_traffic_status_display_format json;
            allow 127.0.0.1;
            allow 10.0.0.0/8;      # k3s Pod网段，按你实际CIDR调整
            deny all;
        }
    }
}

/vts_status 返回的 JSON 里已经包含了：总请求数、按状态码分类的计数、上传/下载字节数、请求处理时间的累计值和直方图分桶——这就是"暴尽暴"里能拿到的最细粒度数据了。

Step 3：sidecar 把 JSON 转成 Prometheus 格式

VTS 原生吐出来的是 JSON，不是 Prometheus text format，所以还需要一个转换器 sidecar（跟之前 nginx-prometheus-exporter 的思路一样，只是换一个专门认 VTS JSON 格式的 exporter）：

yaml
containers:
  - name: frontend
    image: your-registry:5000/frontend:latest
    ports: [{containerPort: 80}]
  - name: vts-exporter
    image: sysdiglabs/nginx-vts-exporter:v0.10.3
    args:
      - -nginx.scrape_uri=http://127.0.0.1/vts_status/format/json
    ports: [{containerPort: 9913}]

Prometheus 之后 scrape 这个 Pod 的 9913 端口，就能拿到按状态码分类的请求速率、延迟分布这些原本 stub_status 完全没有的细节。

老实说一句权衡：这一套比单纯 stub_status 复杂不少——多了一次自定义编译，镜像体积也会变大。如果你的目标纯粹是"体验 sidecar + exporter 模式"，stub_status 版本已经够练手；如果你是想体验"真实生产级 nginx 可观测性长什么样"，VTS 这条路才是对的，值得投入。你说"能暴尽暴"，我判断你是后者，所以按拉满给了。


## 太复杂，先不暴了，server { listen 127.0.0.1:8080; location /nginx_status { stub_status on; access_log off; } }，这个access_log能改成on吗？现有编译版本最大化呢？
access_log 可以开，但没必要；stock 版 stub_status 的天花板就是 6 个数字

中文：

关于 access_log on/off：

技术上完全可以改成 on，不会报错，nginx 会照常把每次访问 /nginx_status 的记录写进 access log。但这里有个实际影响你要知道：

Prometheus 默认每 15 秒 scrape 一次（具体看你之后配的 scrape_interval），也就是说这个 /nginx_status 端点每 15 秒会被访问一次
开了 access_log 之后，日志文件里会每 15 秒多一行类似 127.0.0.1 - - [...] "GET /nginx_status HTTP/1.1" 200 ... 的记录，纯粹是监控系统自己访问自己产生的噪音，不包含任何有价值信息
长期跑下去，这些噪音行会占日志空间、也会干扰你之后如果想用 access log 做别的分析（比如排查真实用户请求）时的信噪比

所以业界标准做法就是保持 access_log off——这不是为了省事，而是"给监控探针自己的访问，没有记录的价值"这个判断。你想开也不影响功能正确性，只是没有收益、有轻微副作用。

关于 stock 版 stub_status 的天花板：

不用自定义编译的话，stub_status 返回的内容格式固定，就这 6 个数字，没有配置项能让它吐出更多：

Active connections: 3
server accepts handled requests
 1156 1156 4482
Reading: 0 Writing: 1 Waiting: 2

对应含义：

Active connections — 当前活跃连接数
accepts / handled — 累计接受/处理的连接总数（两者不等说明有连接被拒绝，比如超过 worker_connections）
requests — 累计处理的 HTTP 请求总数
Reading / Writing / Waiting — 当前处于"读请求头"、"写响应"、"keepalive 空闲"三种状态的连接数各多少

这就是官方 stub_status 模块的全部字段，没有隐藏参数能扩展它——没有按 URL 路径拆分、没有状态码分类、没有延迟数据，这些都是只有 VTS 模块（编译级别）才能给的，纯配置层面无解。

如果不想编译又想要更多一点：折中方案是保持 stub_status 不变，另外单独用一个 exporter 去解析 access log 文本（比如 access_log 打开、格式里带上状态码和响应时间，再用类似 mtail 或者社区的 nginx log exporter 去 tail 这份日志、统计出状态码分布）。这条路不用重新编译镜像，纯粹是"多起一个 sidecar 读日志文件"，比 VTS 简单很多，但功能介于两者之间。不过这已经是"要不要再加"的下一步了，你现在这套 stub_status 版本本身没问题，可以先跑起来看效果。

## 那在加一个，这个访问记录是用户的访问，不是探针的哈
明白，两块分开配置——探针那块保持 off，用户流量那块开 on

中文：

这个区分是对的，也是标准做法：探针自己访问自己没有记录价值，但真实用户访问的记录，是你之后想做访问分析、排查问题时的第一手数据源。

改完整版 nginx.conf：

nginx
http {
    # 定义日志格式（放 http 块级别，两个 server 都能用）
    log_format main '$remote_addr - $remote_user [$time_local] "$request" '
                     '$status $body_bytes_sent "$http_referer" '
                     '"$http_user_agent" $request_time';

    server {
        listen 80;

        access_log /var/log/nginx/access.log main;   # 用户流量，记录

        location / {
            root /usr/share/nginx/html;
            try_files $uri $uri/ /index.html;
        }
    }

    server {
        listen 127.0.0.1:8080;

        access_log off;   # 探针流量，不记录

        location /nginx_status {
            stub_status on;
        }
    }
}

几个细节说明：

log_format 我顺手加了 $request_time（记录这次请求处理耗时，单位秒），这是为你之后万一想做"日志转 exporter"这条路预留的字段——没有它的话，光靠 access log 你连自己请求慢不慢都看不出来，加上几乎零成本，建议保留。
两个 server 块分别用不同端口区分职责：80 对外服务用户，8080 只监听 127.0.0.1（不对外，Pod 内部/sidecar 才能访问）专门给探针用——这个隔离你原来的写法里已经有了，我只是在这基础上给两边分别配 access_log。
/var/log/nginx/access.log 这个路径是容器内部路径，如果你想让日志能在容器外看到（比如之后接 EFK 那条链路时），需要在 Deployment 里挂一个 emptyDir volume 到这个目录，或者干脆让 nginx 输出到 stdout（access_log /dev/stdout main;），这样 kubectl logs 或者 Fluent Bit 采集起来更方便——这个选择留给你，看你 P8 打算怎么接。

## 所以access_log只是放在本地了，还没开放给Prometheus，是为以后采集日志用的
完全正确，你抓到了两条链路的本质区别

中文：

这个理解没有偏差，而且抓住了一个很关键的架构分野，值得展开确认一下：

access_log 和 stub_status 走的是两条完全不同的链路，服务两种不同的可观测性需求：

	access_log（你刚配的）	stub_status（Prometheus 抓的）
本质	一份不断追加的文本文件，存在容器本地磁盘	一个HTTP 端点，被动等待抓取
谁来消费	需要额外的"日志采集器"主动去读（Fluent Bit / Filebeat / Promtail 这类）	Prometheus 主动定时 scrape，直接读进时序数据库
对应你的哪个 Phase	P8（EFK 日志链路）	P6（当前正在做的）
回答什么问题	"10:32 这个具体请求发生了什么"（单条事件的细节）	"过去 5 分钟请求量/错误率的趋势"（聚合后的数字）

所以现在这份 access_log 确实是"写了但没人读"的状态——你把它记下来了，但还没有任何组件去消费它。这不是配置漏了，是刻意为之：P6 只管指标(metrics)这一条线，日志(logs)这条线要等 P8 EFK 接进来才会真正用上它。两者互不依赖，谁先跑通都不影响对方。

一个提前预告，方便你到 P8 的时候少走弯路：EFK 里的 Fluent Bit 采集容器日志，标准做法是读 stdout（K8s 原生的日志机制，kubectl logs 也是读这个），而不是去读容器内部的日志文件路径。所以等你到 P8，大概率会把 access_log /var/log/nginx/access.log main; 改成 access_log /dev/stdout main;——现在先按你这版跑，不用现在就改，等做 P8 时再回来动这一行就行。

## 现在就改吧，不如本地也存一份，两个输出路径都要
两行 access_log 就够了，nginx 原生支持同时输出多路径

中文：

好消息是这个需求 nicnx 原生支持,不用引入任何额外工具——access_log 指令可以在同一个 server 块里写两次,两条日志会各自独立、完整地写一份,不是"分流"关系:

nginx
http {
    log_format main '$remote_addr - $remote_user [$time_local] "$request" '
                     '$status $body_bytes_sent "$http_referer" '
                     '"$http_user_agent" $request_time';

    server {
        listen 80;

        access_log /var/log/nginx/access.log main;   # 本地存一份
        access_log /dev/stdout main;                  # 同时输出到 stdout，供 Fluent Bit 采集

        location / {
            root /usr/share/nginx/html;
            try_files $uri $uri/ /index.html;
        }
    }

    server {
        listen 127.0.0.1:8080;

        access_log off;

        location /nginx_status {
            stub_status on;
        }
    }
}

有一点需要你现在就决定,不然会有个坑： 容器内部的 /var/log/nginx/access.log 默认写在容器的可写层（ephemeral），Pod 重启或重新调度后这份本地文件会直接消失，不会保留。如果你要的"本地存一份"是希望它能扛过 Pod 重启、真正留存下来，需要在 Deployment 里加一个 volume：

yaml
spec:
  template:
    spec:
      containers:
        - name: frontend
          image: your-registry:5000/frontend:latest
          volumeMounts:
            - name: nginx-logs
              mountPath: /var/log/nginx
      volumes:
        - name: nginx-logs
          emptyDir: {}

emptyDir 也只是"Pod 存活期间"持久,Pod 被删掉了还是会没——真正想跨 Pod 重建都留存,得用 PV（跟你 Kafka/ES 那套一样的机制）。但如果你这份本地日志纯粹是"临时看看、调试用",emptyDir 就够了,不用上到 PV 这么重。

你现在的场景（本地看 + stdout 给 Fluent Bit）比较像调试/双保险用途，我建议先用 emptyDir，如果之后发现真的需要日志跨 Pod 重启还在，再升级成 PV 也不迟。

## emptyDir也不用，就是先这么放着
那就更简单——原样保留在容器可写层，不加任何 volume

中文：

明白，那就是刚才那份 nginx.conf 直接用，spec.containers 部分什么都不用改，不加 volumeMounts，也不加 volumes：

nginx
http {
    log_format main '$remote_addr - $remote_user [$time_local] "$request" '
                     '$status $body_bytes_sent "$http_referer" '
                     '"$http_user_agent" $request_time';

    server {
        listen 80;

        access_log /var/log/nginx/access.log main;   # 本地存一份（Pod活着的时候在）
        access_log /dev/stdout main;                  # 同时输出到stdout，给Fluent Bit用

        location / {
            root /usr/share/nginx/html;
            try_files $uri $uri/ /index.html;
        }
    }

    server {
        listen 127.0.0.1:8080;

        access_log off;

        location /nginx_status {
            stub_status on;
        }
    }
}

这样的实际效果，跟你确认一下心理预期，避免以后误以为丢日志是 bug：

容器正常运行期间，/var/log/nginx/access.log 就在里面，需要看的话 kubectl exec 进去 tail 一下就行
Pod 重启、Pod 被删掉重建、镜像升级触发滚动更新——任何一种情况，这份本地文件都会跟着旧容器一起消失，新 Pod 里是一份空文件重新开始记
/dev/stdout 这条不受影响，kubectl logs 永远能看到（虽然 Pod 重建后 kubectl logs 默认也只看当前 Pod 的，历史的还是要靠 Fluent Bit 之类真正采集走了才能跨 Pod 生命周期查）

这个取舍现在完全没问题——你自己说了是"先这么放着"，等到 P8 真的接 EFK 的时候，/dev/stdout 这条线自然会被采集走、长期化，本地这份文件到时候甚至可以选择直接删掉这行 access_log 配置，因为已经不需要了。现在不用为这份"临时"文件的持久性纠结，先跑起来看效果就好。

## nginx启动报错
  Normal   Pulling    5m42s                  kubelet            spec.containers{nginx-exporter}: Pulling image "nginx/nginx-prometheus-exporter:1.1.0"
  Normal   Pulled     5m37s                  kubelet            spec.containers{nginx-exporter}: Successfully pulled image "nginx/nginx-prometheus-exporter:1.1.0" in 5.709s (5.709s including waiting). Image size: 4386436 bytes.
  Normal   Created    5m37s                  kubelet            spec.containers{nginx-exporter}: Container created
  Normal   Started    5m36s                  kubelet            spec.containers{nginx-exporter}: Container started
  Normal   Created    2m33s (x6 over 5m42s)  kubelet            spec.containers{frontend}: Container created
  Normal   Started    2m33s (x6 over 5m42s)  kubelet            spec.containers{frontend}: Container started
  Normal   Pulled     2m33s (x5 over 5m36s)  kubelet            spec.containers{frontend}: Container image "localhost:5000/frontend:202609221118-2693079" already present on machine and can be accessed by the pod
  Warning  BackOff    22s (x8 over 5m35s)    kubelet            spec.containers{frontend}: Back-off restarting failed container frontend in pod frontend-657cf58574-2ndkh_toy-system(487cbb56-3db1-4b7e-8889-bc4671dbbf4b)
ziqiao@ziqiao-ASM100:~/Documents/homelab-toy-system/frontend$ kubectl logs frontend-657cf58574-2ndkh -n toy-system --tail 20
Defaulted container "frontend" out of: frontend, nginx-exporter
/docker-entrypoint.sh: /docker-entrypoint.d/ is not empty, will attempt to perform configuration
/docker-entrypoint.sh: Looking for shell scripts in /docker-entrypoint.d/
/docker-entrypoint.sh: Launching /docker-entrypoint.d/10-listen-on-ipv6-by-default.sh
10-listen-on-ipv6-by-default.sh: info: Getting the checksum of /etc/nginx/conf.d/default.conf
10-listen-on-ipv6-by-default.sh: info: /etc/nginx/conf.d/default.conf differs from the packaged version
/docker-entrypoint.sh: Sourcing /docker-entrypoint.d/15-local-resolvers.envsh
/docker-entrypoint.sh: Launching /docker-entrypoint.d/20-envsubst-on-templates.sh
/docker-entrypoint.sh: Launching /docker-entrypoint.d/30-tune-worker-processes.sh
/docker-entrypoint.sh: Configuration complete; ready for start up
2026/09/22 02:26:05 [emerg] 1#1: "http" directive is not allowed here in /etc/nginx/conf.d/default.conf:1
nginx: [emerg] "http" directive is not allowed here in /etc/nginx/conf.d/default.conf:1

一句话诊断：官方 nginx 镜像的 default.conf 本身已经在 http{} 里面了，你又包了一层

中文：

官方 nginx:alpine（或类似基础镜像）自带的主配置文件 /etc/nginx/nginx.conf 长这样（简化版）：

nginx
http {
    ...
    include /etc/nginx/conf.d/*.conf;   # 关键在这里
}

也就是说，你自己写的那份配置文件，本身就已经处于 http{} 上下文里了——因为它是被 include 进 http{} 块内部的。你在自己的文件里又套了一层 http { ... }，等于 http{} 套 http{}，nginx 直接报错 "http" directive is not allowed here（因为 http 指令只能出现在最外层/main 上下文，不能嵌套在另一个 http 里面）。

修法：把外层的 http { } 去掉，只留里面的内容——因为 log_format 指令本身合法的上下文正好就是 http{}，你的文件既然已经天然处在 http{} 里，直接把 log_format 和两个 server{} 块平铺写就行，不用再包一层：

nginx
log_format main '$remote_addr - $remote_user [$time_local] "$request" '
                 '$status $body_bytes_sent "$http_referer" '
                 '"$http_user_agent" $request_time';

server {
    listen 80;

    access_log /var/log/nginx/access.log main;
    access_log /dev/stdout main;

    location / {
        root /usr/share/nginx/html;
        try_files $uri $uri/ /index.html;
    }
}

server {
    listen 127.0.0.1:8080;

    access_log off;

    location /nginx_status {
        stub_status on;
    }
}

改完重新打包镜像、重新部署就行。这个坑很典型：从零写一份独立的 nginx.conf（替换整个主配置文件）和只写一份放进 conf.d/ 目录（被主配置 include 进去）是两种完全不同的写法，前者必须自己包 http{}（还得包 events{} 等），后者不能包，因为外层已经帮你包好了。你的 Dockerfile 里 COPY 的目标路径是 /etc/nginx/conf.d/default.conf（从报错路径能看出来），所以属于第二种情况。