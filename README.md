# homelab-toy-system

迷你保单管理系统（Mini Policy System）—— 用来把 [`docs/大型分布式系统_全景模块图.md`](docs/大型分布式系统_全景模块图.md)
里的中间件逐一练到的玩具工程。落地规划见 [`docs/homelab-toy-system-plan.md`](docs/homelab-toy-system-plan.md)。
设计/踩坑相关的问答记录（方便回归复习）见 [`docs/qa-log.md`](docs/qa-log.md)。

## 当前进度

- [x] P0 — k3s 单节点 + Docker
- [x] P1 — `policy-service`（MySQL + Redis + Liquibase + MyBatis），本地CRUD跑通
- [x] P2 — `gateway-service`（JWT鉴权）+ `frontend`（登录+列表页），本地全链路跑通；
      k3s里卸载Traefik、换装 `ingress-nginx`（业务服务还没接进k3s，见 `infra/k8s/ingress-nginx-demo/README.md`）
- [x] P3 — Kafka（KRaft单节点）+ `policy-service` 发 `policy-events` + `notification-service` 消费，验证过"同一个事件、独立消费者"的解耦
- [x] P3修订 — 改用父子Maven module + 共享`event-contracts`模块（根治"两份类不一致"的坑1）；
      `notification-service`补上死信Topic容错（`ErrorHandlingDeserializer` + `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`，坑2的完整方案）
- [x] Kafka运维可视化 — 本地加了 `kafka-ui`（`http://localhost:9090`），顺带修复了一个真实的
      持久化bug：Kafka数据卷路径一直挂错了地方，之前"重启数据还在"只是因为容器没被真正recreate过
- [x] P4 — Elasticsearch + Kibana + `search-service`（消费者组`search-service`，独立于
      `notification-service`）+ 前端搜索页，CQRS读写分离链路跑通
- [ ] P5 及以后 —— 见规划文档第5节

## 目录结构

```
homelab-toy-system/
├── pom.xml                           # 聚合父pom（只聚合下面这几个需要共享契约的Java服务）
├── event-contracts/                  # 共享Maven模块：Kafka事件的Java类定义（PolicyEvent等）
├── infra/
│   ├── docker-compose.dev.yml       # 本地开发中间件（MySQL/Redis/Kafka/Elasticsearch/Kibana）
│   └── k8s/
│       └── ingress-nginx-demo/      # P2: 验证ingress-nginx本身能工作的demo，非业务
├── policy-service/                   # 核心写服务（发 policy-events，依赖event-contracts）
├── gateway-service/                  # API网关（Spring Cloud Gateway + JWT，独立pom，不依赖event-contracts）
├── notification-service/             # policy-events 消费者A（模拟通知，无DB，依赖event-contracts）
├── search-service/                   # policy-events 消费者B + 只读搜索API（Elasticsearch，依赖event-contracts）
├── frontend/                         # React SPA（登录 + 保单列表 + 搜索）
├── docs/
│   ├── homelab-toy-system-plan.md
│   └── kafka-event-schema.md         # policy-events / report-events 的事件格式契约
└── README.md
```

各服务的运行方式见其自己的 `README.md`。

## 本地开发环境启动

```bash
docker compose -f infra/docker-compose.dev.yml up -d
```

`policy-service`/`notification-service`/`search-service`依赖共享模块`event-contracts`，
第一次跑、或者改了`event-contracts`之后，需要先在**仓库根目录**装一次：

```bash
mvn -q -DskipTests install   # 把 event-contracts 装进本地 ~/.m2 仓库
```

然后按顺序启动业务服务（都不进容器，方便调试）：

```bash
# 1. policy-service（默认8081）
cd policy-service && mvn spring-boot:run

# 2. gateway-service（默认8080，代理到policy-service；独立Maven项目，不受上面mvn install影响）
cd gateway-service && mvn spring-boot:run

# 3. frontend（默认5173）
cd frontend && npm install && npm run dev

# 4. notification-service（默认8082，消费policy-events，纯打日志）
cd notification-service && mvn spring-boot:run

# 5. search-service（默认8083，消费policy-events写入ES，暴露只读搜索API）
cd search-service && mvn spring-boot:run
```

打开 `http://localhost:5173`，用 demo 账号 `admin / admin123` 登录，顶部可以切换"保单列表"/"搜索"。

## Kafka 可视化：kafka-ui

`docker compose up -d` 会顺带拉起 `kafka-ui`（`http://localhost:9090`），可以在浏览器里看
topic列表、分区、消息内容、consumer group消费进度——比一堆 `kafka-topics.sh`/`kafka-console-consumer.sh`
命令行直观很多，排查"消息发了没、消费到哪了、死信topic里堆了什么"这类问题很好用。

端口选的`9090`，不是紧挨着业务服务的`8080`往后数（那段留给gateway-service/policy-service/
notification-service/以后的search-service等，避免以后新服务撞端口）。

**踩过的坑**：`apache/kafka`镜像默认把数据写到镜像自带的`/tmp/kraft-combined-logs`，跟
`docker-compose.dev.yml`里挂载的卷路径`/var/lib/kafka/data`完全对不上。这意味着从P3到现在，
Kafka数据其实一直没有真正持久化到卷里——只是因为容器一直没有被`docker compose up`重新创建过
（改配置触发recreate才会暴露这个问题），"数据还在"只是**容器没被换掉**的假象，不是持久化生效。
加`kafka-ui`这次因为要改监听器配置、必然触发容器recreate，直接暴露了这个问题，测试数据也因此
丢了一次（都是本地测试数据，无所谓，但教训要记住）。现在已经显式加了`KAFKA_LOG_DIRS`环境变量
把两边对齐，并且用"真的删掉容器再重建"验证过数据确实能在卷里存活。

## Elasticsearch + Kibana（P4）

`docker compose up -d` 会一并拉起 `elasticsearch`（`http://localhost:9200`）和
`kibana`（`http://localhost:5601`）。本地开发简化：`xpack.security.enabled: false`，
不用先搞证书/账号密码才能起一个ES。`search-service`把`policy-events`投影成`policies`索引，
可以在Kibana的Dev Tools里直接 `GET policies/_search` 查看。

装的时候顺手把Kafka那次踩过的"卷路径挂错地方"坑复查了一遍——这次直接用"创建文档→强制删掉
容器重建→确认文档还在"验证过persistence是真的生效，不是又一次"容器没被换掉"的假象。

## k3s集群现状

- 已用 `ingress-nginx` 替换k3s默认的Traefik（详见 `docs/homelab-toy-system-plan.md` 3.5节的决策背景）
- 目前只部署了一个验证用的demo（`infra/k8s/ingress-nginx-demo/`），业务服务（gateway-service/frontend等）
  要到P5阶段才会真正部署进k3s（届时才需要解决镜像分发问题，即Harbor，P7阶段）
