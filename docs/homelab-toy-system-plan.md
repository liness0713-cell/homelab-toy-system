# Homelab 玩具微服务系统 —— 落地实施规划

> 交付对象：Claude Code（在 VSCode 里按此文档编码）
> 使用者背景：SRE/BSD在职学习者，目标是通过一套"麻雀虽小五脏俱全"的玩具系统，
> 把 `大型分布式系统_全景模块图.md` 里列出的中间件逐一练到，尤其是CDC/CQRS/CI-CD这条主线。
> 本文档是那份全景图的**具体落地版**：把抽象模块清单，转成一套真实可跑、互相依赖的代码工程。

---

## 0. 设计原则

1. **业务域统一，避免"为了用中间件而用中间件"**：全部服务围绕同一个简化业务域——一个"迷你保单管理系统"（呼应你的BSD学习背景，命名/字段可以用你已经熟悉的テンガン/イングラム式术语，练起来更有代入感）。
2. **两套环境，同一份代码**：
   - **本地开发环境**：`docker-compose` 一键拉起 MySQL/Redis/Kafka/ES 等中间件，服务本身用 IDE/Claude Code 直接跑（不进容器），方便调试。
   - **"生产"环境**：所有服务和中间件都跑在 k3s 里，通过 Harbor 镜像仓库 + CI/CD 流水线部署，模拟真实发布流程。
3. **消费者/生产者关系必须是真实的**，不是摆设：至少两条独立的 Kafka 消费链路（对应全景图 11.1 的"同一份事件，两种消费策略"）。
4. **每个阶段都要能跑通、能看见效果**，不堆一堆装了但没用上的空壳中间件（呼应你之前的判断：监控要等有波动的系统才装）。
5. **为后续大数据/AI阶段预留钩子**，但不在第一期实现——只在设计上不给自己埋雷（比如Kafka topic设计、事件schema要考虑到未来会被多个消费者复用）。

---

## 1. 业务域设计：迷你保单系统（Mini Policy System）

一句话描述：用户登录 → 创建/查询保单 → 保单创建后异步触发通知 + 异步写入搜索索引 → 可以搜索保单。

### 1.1 核心实体

```
Policy（保单）
- id
- policyNo        保单号
- holderName      投保人姓名
- productType     产品类型（如 TENGAN / INGURAMU，对应你熟悉的两个产品线）
- premium         保费
- status          状态（DRAFT / ACTIVE / CANCELLED）
- createdAt / updatedAt
```

### 1.2 服务清单

| 服务名 | 角色 | 技术栈 | 职责 |
|---|---|---|---|
| `frontend` | 前端 SPA | React + Vite | 登录、保单列表/创建表单、搜索页 |
| `gateway-service` | API网关 | Spring Cloud Gateway | 路由、JWT鉴权、限流（对应全景图第二节） |
| `policy-service` | 核心写服务 | Spring Boot + MyBatis + Liquibase + MySQL + Redis | 保单CRUD（写路径）、Redis缓存详情查询、创建/更新时发Kafka事件、启动时Liquibase自动建表/迁移 |
| `notification-service` | 消费者A | Spring Boot（纯消费者，无DB） | 订阅 `policy-events` topic，模拟"发通知"（打日志/写一条本地文件即可，不用真发邮件） |
| `search-service` | 消费者B + 读服务 | Spring Boot + Elasticsearch | 订阅同一个 `policy-events` topic，写入ES；对外暴露"保单搜索"只读API（CQRS读路径） |

> **对应全景图哪几节**：二(网关)、三(微服务框架/ORM)、四(MySQL/Redis)、五(Kafka)、六(ES，为11.2 CQRS铺垫)、九(JWT鉴权)。
> 后续 11.1 的 Zorro/Canal CDC 会作为**第二期改造**：把 `policy-service` 里"业务代码手动发Kafka事件"这种做法，换成"Canal监听MySQL binlog自动产生事件"，体验两种模式的差异（详见第5节）。

### 1.3 表结构管理：Liquibase（对齐公司实践，而非手写建表逻辑）

`policy-service` 不采用"应用代码启动时检测并手动建表"的土办法，而是用 **Liquibase**（和公司实际使用的工具保持一致）。变更集(changelog)放在 `policy-service/src/main/resources/db/changelog/` 下，按顺序编号（如 `001-create-policy-table.xml`、`002-add-index.xml`），应用启动时 Liquibase 自动检查数据库当前版本、执行未应用的变更集——这样"建库建表"本身也是受版本控制、可追溯的过程，不是一段外人看不见的初始化代码。

### 1.4 数据流一句话

```
外部用户 → nginx-ingress-controller（域名路由/TLS终止，P2引入）
              → gateway-service(JWT校验) → policy-service
                                                 ├─ 写 MySQL（保单主数据）
                                                 ├─ 读/写 Redis（详情缓存）
                                                 └─ 发 Kafka topic: policy-events
                                                         ├─ notification-service 消费 → 模拟通知
                                                         └─ search-service 消费 → 写入 ES → 对外提供搜索
              → frontend（静态资源）
```

---

## 2. 目录结构（Monorepo，全部放一个文件夹，VSCode统一管理）

```
homelab-toy-system/
├── frontend/                      # React SPA
├── gateway-service/                # Spring Cloud Gateway
├── policy-service/                 # 核心写服务
├── notification-service/           # Kafka消费者A
├── search-service/                  # Kafka消费者B + ES读服务
├── infra/
│   ├── docker-compose.dev.yml     # 本地开发中间件：MySQL/Redis/Kafka/ES/Kibana
│   ├── k8s/                        # k3s部署清单（每服务一个子目录）
│   │   ├── policy-service/
│   │   │   ├── deployment.yaml
│   │   │   ├── service.yaml
│   │   │   └── configmap.yaml
│   │   ├── gateway-service/
│   │   ├── notification-service/
│   │   ├── search-service/
│   │   ├── frontend/
│   │   └── middleware/             # k3s里"生产用"的MySQL/Redis/Kafka/ES（后期阶段才切过去）
│   └── jenkins/
│       └── Jenkinsfile.template    # 各服务共用的流水线模板
├── docs/
│   └── kafka-event-schema.md       # policy-events 的事件格式定义（各服务共享契约）
└── README.md                       # 项目总览 + 本地启动方式
```

**给Claude Code的约定**：每个服务子目录下必须有自己的 `README.md`，写清楚：这个服务是干什么的、怎么本地单独跑、依赖哪些中间件、暴露哪些端口/API。

---

## 3. 环境三元制：本地开发 vs 宿主机"云托管" vs k3s业务集群

> 这一节是本次修订的核心调整：不把有状态中间件塞进k3s当作"生产"，而是刻意区分"业务集群"和"外部托管依赖"，更贴近真实架构，也避免过早陷入StatefulSet的复杂度。

### 3.1 本地开发（Docker Compose）

`infra/docker-compose.dev.yml` 只装中间件，不装业务服务本身（业务服务用 `mvn spring-boot:run` / `npm run dev` 直接跑，方便打断点调试）：

```yaml
services:
  mysql:
    image: mysql:8
    ports: ["3306:3306"]
  redis:
    image: redis:7
    ports: ["6379:6379"]
  kafka:
    image: bitnami/kafka:latest      # KRaft模式，不需要额外装Zookeeper
    ports: ["9092:9092"]
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.x
    ports: ["9200:9200"]
  kibana:
    image: docker.elastic.co/kibana/kibana:8.x
    ports: ["5601:5601"]
```

（具体版本号/环境变量交给Claude Code去查最新可用版本填，这里只定结构）

### 3.2 "生产"环境的中间件归属：谁进k3s，谁不进

**核心决策：有状态中间件（MySQL、Redis）不进k3s，直接跑在宿主机上（Docker常驻容器，systemd管理），扮演"云厂商托管服务"（RDS/ElastiCache）的角色。** 原因：

- 生产环境本来就不会自己在业务集群里管理有状态DB的HA，模拟这个外部依赖关系，比在k3s里搭一套简化版HA更贴近真实架构
- MySQL跑在宿主机上，反而让binlog复制（后续P9的Canal CDC）更直接——Canal连接宿主机MySQL和连接任何外部托管MySQL没有区别

**k3s业务集群如何访问宿主机上的MySQL/Redis？** 这里引入一个新知识点：K8s的 **"无selector Service"**（Service without selector）。正常的Service会通过selector自动关联同namespace里匹配标签的Pod；而"无selector Service"专门用来指向**集群外部**的资源——手动写一个Endpoints对象指向宿主机IP+端口，业务代码里看到的还是标准的Service DNS名字（如 `mysql-external.toy-system.svc.cluster.local`），完全不用关心"数据库其实在集群外面"，这跟真实云环境里"应用不关心RDS具体部署在哪"是同一种解耦思路。这一步会作为P5阶段的一个具体练习点。

**Kafka/Elasticsearch怎么处理？** 这两个是无状态/自带分布式冗余设计的中间件（Kafka本身就是为分布式设计的，ES的分片机制也类似），跑在k3s里问题不大，可以用Bitnami Helm chart单副本部署，练手写YAML/Helm values,不纠结生产级多副本配置。

**镜像流转：**
- 业务服务的镜像统一推到 **Harbor**，k3s 从 Harbor 拉镜像
- 本地Docker build的镜像 ≠ k3s能直接用的镜像，需要 `docker push` 到 Harbor，k3s侧配置好 imagePullSecrets 指向Harbor（这一步会遇到"私有仓库鉴权"的实操，正好也是练习点）

### 3.3 Namespace 规划

给这套玩具系统单独开一个 `toy-system` namespace，跟k3s自带系统组件（`kube-system`）分开，方便管理和后续整体清理。所有业务服务、Kafka/ES的Helm release都部署进这个namespace。

### 3.4 独立练习模块：StatefulSet + PersistentVolume（不在关键路径上）

StatefulSet+PV是K8s管理有状态服务的原生机制，虽然3.2节决定不用它来扛玩具系统的真实数据，但机制本身值得亲手体验一次。安排成一个**独立、不阻塞主线**的练习：额外部署一个单节点MySQL StatefulSet（可以叫 `mysql-statefulset-demo`），单独建一个测试库，不接任何业务服务，纯粹用来体验：Pod删除重建后数据还在、PVC和PV的绑定关系、以及它与"无selector Service指向宿主机MySQL"这种方式的本质差异。这个练习可以安排在P5之后、P6之前，随时插入，不影响主线进度。

---

### 3.5 集群入口层：Nginx Ingress Controller（替换k3s默认的Traefik）

这一层之前的规划里漏掉了，补上——对应你实际项目里"所有集群内业务都走nginx-proxy访问外部"这个真实经验。

**先厘清两个容易混的概念：**
- **`gateway-service`（Spring Cloud Gateway）**：应用层网关，做的是"业务相关"的判断——JWT鉴权、按业务规则路由到具体微服务、限流。本质是业务代码，只是长在网络入口位置。
- **Ingress层（nginx）**：集群边缘的反向代理，纯L7流量入口——TLS终止(HTTPS证书)、按域名/路径做最基础转发，不涉及业务逻辑判断。

两层职责不同、可以共存，实际流量路径是：

```
外部用户 → nginx-ingress-controller（域名路由/TLS终止）
              → gateway-service（JWT鉴权/业务路由）→ policy-service / search-service 等
              → frontend（静态资源）
```

**k3s默认自带的Ingress Controller其实是Traefik**（k3s安装时自动装好，不用额外操作就有）。但既然实际生产用的是nginx,为了让homelab经验更贴近真实工作场景，这里明确决定：**卸载/禁用k3s默认的Traefik，改装 `ingress-nginx`**（用Helm chart部署,官方chart名为 `ingress-nginx/ingress-nginx`）,让你写的Ingress YAML、遇到的注解(annotation)写法、调试思路,都能跟公司技术栈对上。

这一步安排进 **P2阶段**，和 `gateway-service`/`frontend` 一起搭起来，因为这时候才第一次需要"外部怎么访问到集群里的东西"这个问题的答案。

对应全景图第一节 + 十二节2.2（灰度发布可作为后续加练）。

### 4.1 组件选择

| 环节 | 选型 | 备注 |
|---|---|---|
| 代码仓库 | **Gitea**（自建） | 比GitLab CE轻量很多，8核15G内存机器更合适；如果之后想练更接近企业环境的功能（MR审批流、CI变量管理UI）可以后期换成GitLab CE，但先用Gitea把流程跑通 |
| CI/CD引擎 | **Jenkins** | 你已计划要装的 |
| 镜像仓库 | **Harbor** | 复用你已有的Harbor同步经验 |
| 部署方式（第一期） | Jenkins流水线里直接 `kubectl set image` / `kubectl apply` | 简单直接，先把闭环跑通 |
| 部署方式（进阶，第二期再加） | **Argo CD**（GitOps） | 对应全景图12.2灰度发布方向，是更现代的部署范式，值得在闭环跑通后加练 |

### 4.2 流水线阶段（以 policy-service 为例）

```
1. 开发者 git push 到 Gitea
2. Gitea webhook 触发 Jenkins
3. Jenkins Pipeline:
   Stage 1: Checkout 代码
   Stage 2: mvn test（跑单元测试）
   Stage 3: mvn package（打Jar）
   Stage 4: docker build -t harbor.local/policy-service:${BUILD_NUMBER}
   Stage 5: docker push 到 Harbor
   Stage 6: kubectl set image deployment/policy-service policy-service=harbor.local/policy-service:${BUILD_NUMBER} -n toy-system
             （这一步触发k8s的滚动发布/rolling update，可以顺手观察Deployment的滚动更新过程）
```

**给Claude Code的任务**：`infra/jenkins/Jenkinsfile.template` 写成参数化模板，各服务的 `Jenkinsfile` 引用它，只传服务名/端口等差异化参数,减少重复。

---

## 5. 分阶段实施顺序（对齐全景图，但按这套玩具系统重新排布）

> 原全景图的"阶段1先装监控"已调整——监控放到"有服务在跑、有真实流量"之后再装。

| 阶段 | 内容 | 产出 |
|---|---|---|
| **P0（已完成）** | k3s单节点 + Docker | ✅ |
| **P1** | 本地docker-compose拉起MySQL/Redis；写 `policy-service`（含Redis缓存），本地直接跑通CRUD | 能在本地curl测试保单CRUD |
| **P2** | 加 `gateway-service`，接JWT鉴权；加 `frontend` 登录+列表页；k3s里卸载默认Traefik、装 `ingress-nginx`，配好Ingress规则让外部能访问到gateway-service/frontend（详见3.5节） | 完整走一遍"外部→nginx-ingress→网关鉴权→后端"链路 |
| **P3** | 本地docker-compose加Kafka；`policy-service`创建保单时发事件；写 `notification-service` 消费 | 体验"同一个事件，独立消费者"的解耦 |
| **P4** | 本地加ES；写 `search-service` 消费同一事件写入ES，暴露搜索API；前端加搜索页 | 完整CQRS读写分离链路跑通 |
| **P5** | 宿主机上用Docker常驻起MySQL/Redis（模拟云托管）；k3s里建`toy-system` namespace，通过"无selector Service"让业务服务连上宿主机MySQL/Redis；Kafka/ES用Helm单副本部署进k3s；所有业务服务部署到k3s（先`kubectl apply`手动部署，不接CI/CD） | "生产"环境跑通一次全链路，体验k3s连接外部依赖的方式 |
| **P5.5（插入式练习，不阻塞主线）** | 额外部署一个独立的MySQL StatefulSet+PV demo（不接业务数据） | 体验StatefulSet机制本身、Pod重建后数据还在 |
| **P6** | **这时候再装Prometheus/Grafana**，因为已经有真实的服务和流量可以观察 | 看到真实的CPU/内存/QPS曲线，可以故意kill掉一个Pod观察自愈 |
| **P7** | Gitea + Jenkins + Harbor，把P5的手动部署变成自动化流水线 | git push即部署，滚动发布跑通 |
| **P8** | EFK日志链路接入这几个服务 | 排查问题时能查日志而不是`kubectl logs`挨个看 |
| **P9（进阶）** | 引入Canal监听宿主机MySQL的binlog，**新增独立的 `report-events` topic + `report-service`**：Canal发到`report-events`，`report-service`消费后把事件还原成SQL、镜像写入**ClickHouse**（而不是MySQL，体验OLAP列式存储的聚合查询优势）。原有`policy-service`手动发`policy-events`（给notification/search用）保留不变，两条链路并存对比 | 完整复刻Zorro BLCS"同一份binlog、两条独立消费管道"的设计思想（全景图11.1），顺带体验ClickHouse vs MySQL的聚合查询差异（12.5/13.1） |
| **P10（进阶）** | Argo CD做GitOps式部署 + 简单灰度发布demo | 对应全景图12.2 |
| **P11（进阶）** | k6对`policy-service`压测 + Chaos Mesh随机杀Pod | 对应全景图12.3 |

第13节（大数据）和第14节（AI/RAG）作为**独立的后续扩展**，不塞进这套玩具系统的第一期范围，但设计上已经兼容：
- Kafka的 `policy-events` topic 未来可以再加一个消费者，写入MinIO/触发Flink，不影响现有两个消费者
- ES里的保单数据后续也可以作为"给pgvector/embedding做实验"的数据源（虽然更自然的做法是用你的BSD学习文档，两条线互不冲突）

---

## 6. Kafka 事件契约（写在 `docs/kafka-event-schema.md`，Claude Code需要严格遵守）

```json
{
  "eventId": "uuid",
  "eventType": "POLICY_CREATED | POLICY_UPDATED | POLICY_CANCELLED",
  "occurredAt": "ISO8601时间戳",
  "policy": {
    "id": "...",
    "policyNo": "...",
    "holderName": "...",
    "productType": "TENGAN | INGURAMU",
    "premium": 0,
    "status": "..."
  }
}
```

topic名：`policy-events`（单topic多事件类型，通过 `eventType` 字段分流，符合大部分Kafka事件设计的实践）。

**P9阶段新增 `report-events` topic**（由Canal监听宿主机MySQL binlog产生，格式对齐ZorroEvent的思路——用统一事件协议封装binlog变更，而不是照搬上面`policy-events`这套应用层事件schema）：

```json
{
  "gtid": "server-uuid:事务序号",
  "database": "toy_policy_db",
  "table": "policy",
  "eventType": "INSERT | UPDATE | DELETE",
  "data": { "...变更后的行数据..." },
  "occurredAt": "ISO8601时间戳"
}
```

`report-service` 消费这个topic，把事件还原成SQL并在ClickHouse里重放（对应Alphad的"镜像执行"模式）。这条链路和`policy-events`是两条独立的Kafka topic，互不影响,完整对应全景图11.1里"report和cdc是两条独立配置的BLCS任务"的设计。

---

## 7. 给 Claude Code 的落地要求（编码前须知）

1. 每个服务独立的 `pom.xml` / `package.json`，不做成父子module（保持简单，避免Maven多模块的复杂度干扰主线学习目标）。
2. 所有服务的配置（数据库连接串、Kafka地址、Redis地址）通过环境变量注入，不要硬编码——本地跑用 `.env` 或 IDE 的 run configuration，k3s里用ConfigMap/Secret。
3. 每个服务提供一个 `/actuator/health`（Spring Boot Actuator）,为后续接Prometheus做准备（P6阶段直接能用，不用返工）。
4. `policy-service` 发Kafka事件的代码要单独封装成一个 `EventPublisher` 类，P9阶段替换成Canal时，只需要把"谁来触发发送事件"这一层换掉，`EventPublisher`本身和下游消费者不用动——这是为了让P9那次"改造对比"有意义,而不是重写一遍。
5. 先按 P1→P4 顺序实现，**每完成一个P就应该能跑起来看到效果**，不要一次性把所有服务代码都写完再联调。
6. `policy-service` 的表结构变更一律通过Liquibase changelog管理，不允许在Java代码里手写"检测表是否存在、不存在就CREATE TABLE"的逻辑。
7. P5阶段k3s里指向宿主机MySQL/Redis的"无selector Service + Endpoints"配置，单独写清楚在 `infra/k8s/middleware/external-services.yaml`，并在该文件顶部用注释说明"这模拟的是云托管数据库，指向宿主机IP"，方便回头复习时一眼看懂意图。
8. P2阶段部署Ingress前，先确认并卸载k3s自带的Traefik（k3s安装参数或`kubectl -n kube-system delete`处理，具体以Claude Code实测为准），再用Helm装`ingress-nginx`，避免两个Ingress Controller同时抢80/443端口冲突。

---

## 8. 已确认的决定（本文档已按此定稿）

- **代码仓库**：先用 **Gitea**（资源更省，流程一致）；虽然公司实际用的Git平台带MR功能，Gitea同样支持PR/MR流程，不影响后续对照体验。
- **Harbor**：单独用docker-compose跑在宿主机上，不塞进k3s（Harbor本身依赖较重，先专注业务服务的部署闭环）。
- **有状态中间件归属**：MySQL/Redis跑在宿主机（Docker常驻+systemd），模拟云托管服务；k3s业务集群通过"无selector Service"访问；StatefulSet+PV机制作为独立练习模块，不承载真实业务数据（详见3.2/3.4节）。
- **表结构迁移工具**：Liquibase（对齐公司实践）。
- **报表库**：ClickHouse（而非继续镜像到MySQL），体验OLAP列式存储优势（详见P9）。
- **集群入口**：用 `ingress-nginx` 替换k3s默认自带的Traefik，对齐实际生产用nginx做集群入口的经验（详见3.5节，P2引入）。
