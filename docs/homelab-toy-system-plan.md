# Homelab 玩具微服务系统 —— 落地实施规划

> 交付对象：Claude Code（在 VSCode 里按此文档编码）
> 使用者背景：SRE/BSD在职学习者，目标是通过一套"麻雀虽小五脏俱全"的玩具系统，
> 把 `大型分布式系统_全景模块图.md` 里列出的中间件逐一练到，尤其是CDC/CQRS/CI-CD这条主线。
> 本文档是那份全景图的**具体落地版**：把抽象模块清单，转成一套真实可跑、互相依赖的代码工程。

> **当前进度**：P1（policy-service + MySQL/Redis）、P2（gateway-service + frontend + JWT + ingress-nginx替换Traefik）、P3（Kafka + notification-service，双消费链路的第一条已跑通，含2.1/6.1/7.9节修订：父子Maven module + 共享`event-contracts`模块、消费者死信Topic容错）、P4（Elasticsearch + Kibana + search-service，双消费链路第二条打通，CQRS读写分离链路跑通，前端加了搜索页）均已完成并commit。P5起用户计划自己动手，不再交给Claude Code自动化实现（详见第5节表格备注）。
>
> **P4.5（已实现并验证通过）**：`policy-service`分库分表练习，插在P4和P5之间。ShardingSphere-JDBC集成、分片号编码进ID、`shardAware`对比开关均已实现；创建分布到全部4个分片、两条查询路径数据一致、更新/取消/列表回归、P1~P4全链路（Kafka+notification-service、ES+search-service）均已跑通验证。方案详见1.5节、5节表格、7节第10条、8节；ShardingSphere依赖集成过程中的试错记录见`docs/policy-service-sharding-troubleshooting.md`。

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

### 1.5 分库分表练习：policy表分片（P4.5，插入在P4和P5之间）

**背景**：`policy-service`目前是单库单表，没有练到`大型分布式系统_全景模块图.md`第四节（分库分表/ShardingSphere）。趁`policy`表结构还简单、正式上k3s（P5）之前的这个窗口把它补上——越往后拖，表结构被P9的Canal/CDC锁定之后再改造成本越高。

**技术选型：ShardingSphere-JDBC（不是Proxy）**。以JDBC驱动包的形式接在MyBatis下面，应用代码基本不用感知底层是几张物理表，改造成本低，直接对应全景图里"ShardingSphere对应Graphene的Sharding Ops"这个选型。

**只分表，不分库**：`policy`表拆成`policy_0`~`policy_3`四张物理表，都还在同一个MySQL实例、同一个schema（`toy_policy_db`）里，不引入第二个数据库实例。理由：这次练习的目的是体验分片路由/跨分片查询这些机制，不是真的有数据量压力；分库解决的是"连接数/物理资源隔离"这个不同维度的问题，留作以后独立的练习项，这次不做，避免范围膨胀。

**分片键：`holder_name`的哈希（不是`policyNo`，不是`productType`）**

- 生产系统一般用客户ID分片——同一个客户的数据落在同一个分片，查"这个客户名下所有保单"不用跨分片。我们的玩具系统没有独立的客户/用户服务，`holderName`只是`policy`表上的一个字符串字段，没有稳定的客户身份可用。
- **取舍决定**：直接对`holder_name`取哈希（`INLINE`算法，表达式大致是`policy_${Math.abs(holder_name.hashCode()) % 4}`），把姓名字符串本身当成客户身份的替身，而不是为了这一个练习单独建一个客户服务/客户表——那是另一个维度的范围膨胀，会喧宾夺主。
- **明确接受的代价**（写在代码注释里，别几个月后忘了当初为什么这么选）：
  - 两个不同的人恰好同名 → 被分到同一个分片，无伤大雅（不是唯一性索引，不影响正确性）。
  - 同一个人的姓名在不同保单上录入方式不完全一致（有无空格、简繁体等）→ 会被分到不同分片，丢失了"本该属于同一个客户、查询本该落在同一个分片"这个真实收益。这是刻意的简化，不是生产做法。

**主键：应用层自己生成雪花ID，分片号编码进ID里（不是ShardingSphere内置SNOWFLAKE）**

- 分片之后每张物理表的`AUTO_INCREMENT`各算各的，`policy_0`和`policy_1`都会生成`id=1`，必须换成全局唯一的分布式ID。
- **修订决定（比最初方案更进一步）**：不用ShardingSphere声明式配置的`SNOWFLAKE`主键生成算法，改成`policy-service`自己实现一个精简版雪花ID生成器，**把分片号直接编码进ID的bit位里**。原因：ShardingSphere的`KeyGenerateAlgorithm`在生成ID时拿不到这一行其他列的值（比如`holder_name`），没法感知"这一行最终会落到哪个分片"，没法从声明式配置里让ID的分片位和实际路由结果保持一致；而应用层自己生成ID时，可以先算好`shardIndex = Math.abs(holderName.hashCode()) % 4`（跟ShardingSphere的INLINE表达式用同一个公式），把这个值提前编进ID，再插入——两边算的是同一个公式、同一个输入，结果天然一致，不存在"先有鸡还是先有蛋"的顺序问题。
- ID位布局（64位`long`，从低位到高位）：12位序列号（同一毫秒内自增，最多4096个）+ 2位分片号（0~3）+ 41位时间戳（自定义纪元起的毫秒数）。解码时反过来：`(id >> 12) & 0b11` 直接拿到分片号，不用查表、不用广播。
- `id`列不再自增，`PolicyMapper.xml`的INSERT语句直接把应用层算好的`id`当成普通列插入，不依赖`useGeneratedKeys`。
- **正确性依赖一个隐藏的一致性约束**：Java代码里的分片公式（`ShardKeyUtil.shardIndexFor`）和ShardingSphere配置里INLINE算法的表达式必须永远保持一致，这两处是分开维护的，改一处忘了改另一处就会导致"ID说这行在分片2，实际却插到了分片1"这种静默错误——上线前会专门写验证步骤亲眼确认两边算出来的分片号对得上，不能只凭代码审查断言它是对的。

**新增：`shardAware`对比开关，同一个查询接口两种实现并排对比**

- `GET /api/policies/{id}?shardAware=false`（默认，不传等价于false）：走原来的逻辑表`policy`查询，ShardingSphere广播查询全部4张`policy_N`表再合并——这是"分片键选错了、只有id可用"时的真实成本。
- `GET /api/policies/{id}?shardAware=true`：从`id`直接解码出分片号，绕开ShardingSphere的逻辑表路由，直接对解出来的那一张物理表（比如`policy_2`）发查询——单分片命中，不广播。
- 两条路径返回的数据应该完全一致（同一行数据），差异只在"底层发了几条SQL、扫了几张表"——配合`sql-show: true`的日志，这个对比清晰可见。`shardAware=true`这条路径是专门用来做对比/教学的诊断路径，不接Redis缓存（缓存会掩盖两条路径每次都是"真查了数据库"这件事，干扰对比）。
- **全局二级索引（`id → 分片`的外部字典）这个方案本次依然不实现**——跟"分片号编码进ID"是两种互斥的解法（后者让ID自解释，前者靠额外维护一份索引数据），选了后者就不需要前者，两个都做没有必要。

**受影响范围**：

| 服务/组件 | 改动 |
|---|---|
| `policy-service` pom.xml | 加 `shardingsphere-jdbc` 依赖（ShardingSphere 5.5.x起`shardingsphere-jdbc-core`改名成了`shardingsphere-jdbc`） |
| `policy-service` 新增分片配置 | 声明实际数据源、`policy`逻辑表→`policy_0~3`实际表的映射、分片算法（INLINE，对`holder_name`取哈希）；**不配置**ShardingSphere的主键生成算法，`id`完全由应用层生成 |
| `policy-service` 新增代码 | `ShardKeyUtil`（分片号计算，和ShardingSphere的INLINE表达式保持同一个公式）、`SnowflakeIdGenerator`（生成/解码带分片号的ID） |
| Liquibase changelog | 新增变更集：建`policy_0`~`policy_3`四张表（`id`列普通`BIGINT`主键，不再`AUTO_INCREMENT`），废弃旧的单表`policy`。本地开发数据是练习数据，直接随新changelog推倒重建，不写迁移脚本——迁移工具本身不是这次练习的重点 |
| `PolicyMapper.xml` | INSERT语句把`id`当成普通列显式插入；新增一个按"物理表名+id"直接查询的方法，供`shardAware=true`路径用 |
| `PolicyController`/`PolicyService` | `getById`新增`shardAware`参数，两条实现并存对比 |
| `gateway-service` / `notification-service` / `search-service` / `frontend` | **不受影响**——它们只认Kafka事件契约（`PolicyEvent`）和REST API，不关心`policy-service`底层是几张物理表 |
| Redis缓存（`policy-detail`） | `shardAware=false`路径保持原有缓存不变；`shardAware=true`路径不走缓存（见上） |

**验证方式**（对齐项目一贯"亲眼验证，不假设"的习惯）：

- 打开ShardingSphere的`sql-show: true`，对比`shardAware=false`（4条SELECT广播）和`shardAware=true`（1条SELECT）的实际下发SQL。
- 造几条holderName明显不同的保单，确认：① 数据分散落在不同的`policy_N`表里；② 从返回的`id`解码出的分片号，和这行数据实际所在的物理表编号一致。
- 回归P1→P4整条链路（Redis缓存、Kafka事件发布/消费、ES搜索）确认分片改造没有破坏已经跑通的功能。

**分库（数据库级别的拆分）单独作为以后的练习，不在这次范围内**：这次只做分表，跟StatefulSet demo（3.4节）一样，安排成一个独立、不阻塞主线、不碰真实业务数据的插入式练习，具体时间待定。原因：分表已经能拿到这次练习真正想要的东西（路由机制、分布式ID、广播查询代价）；分库额外教的是"多数据源连接池管理、跨库事务"，但`policy-service`目前压根没有跨库写场景能体现分库的价值，勉强做容易沦为"配两个数据源"这种偏机械的工作。真要做，值得配真正独立的第二个MySQL实例（而不是同一实例开两个schema糊弄自己），但那样P5"宿主机托管MySQL"那块的"无selector Service"配置也要跟着变成两份，等这次分表练习稳定之后再单独规划。

---

## 2. 目录结构（Monorepo，全部放一个文件夹，VSCode统一管理）

```
homelab-toy-system/
├── pom.xml                          # 父pom（聚合各Java模块，定义共享依赖版本）
├── event-contracts/                 # 共享Maven模块：Kafka事件的Java类定义（PolicyEvent等）
│   └── src/main/java/.../event/PolicyEvent.java
├── frontend/                      # React SPA
├── gateway-service/                # Spring Cloud Gateway
├── policy-service/                 # 核心写服务（依赖event-contracts）
├── notification-service/           # Kafka消费者A（依赖event-contracts）
├── search-service/                  # Kafka消费者B + ES读服务（依赖event-contracts）
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

### 2.1 修订：Java服务改用父子Maven module，共享事件契约类（`event-contracts`）

**背景**：P3阶段暴露了一个真实问题——`policy-service`和`notification-service`各自维护一份独立的`PolicyEvent.java`（包名不同、内容靠人工保持一致），这正是"坑1"（`not in the trusted packages`）的根源：两边对同一份JSON契约各写各的类，类名注定对不上，只能靠`spring.json.use.type.headers=false`绕过去，但"两份类容易失步"这个风险还在，没有从根上解决。

**修订决定**：新建一个独立的Maven模块 `event-contracts`，专门存放Kafka事件相关的Java类（`PolicyEvent`及后续`ReportEvent`等），`policy-service`（生产者）、`notification-service`/`search-service`（消费者）都以Maven依赖的方式引用**同一个类**，而不是各自维护一份。

- 根目录新增一个聚合用的父`pom.xml`（`<packaging>pom</packaging>`，`<modules>`里列出`event-contracts`和各个Java服务），管理公共依赖版本（Spring Boot版本、Kafka client版本等），避免各服务各自锁不同版本导致序列化行为不一致。
- 各服务的`pom.xml`改为以这个父pom为`<parent>`，并添加对`event-contracts`的依赖。
- **这个决定的取舍**：好处是彻底消灭"两份类不一致"这类问题，且更贴近真实企业项目里"公共契约包"的常见做法；代价是引入了Maven多模块的一点复杂度（构建顺序、`mvn install`共享模块到本地仓库这些概念）——权衡下来，既然坑1已经真实发生过，用父子module从根上解决比反复靠配置绕开更值得，所以在此处调整早前"不做父子module"的决定。
- 需要注意：`gateway-service`如果不直接处理`PolicyEvent`对象（只做HTTP层转发），可以不依赖`event-contracts`，不必强行让所有服务都挂上这个依赖。

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
    image: apache/kafka:latest       # KRaft模式，不需要额外装Zookeeper；bitnami/kafka的免费latest标签已下架，改用官方镜像
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

### 3.3 Namespace 规划：业务与中间件分离

**修订**：不再把所有东西塞进同一个 `toy-system` namespace，改为按职责拆分：

| Namespace | 内容 |
|---|---|
| `toy-system` | 业务服务本体：gateway-service、policy-service、notification-service、search-service、frontend |
| `toy-infra` | 集群内跑的中间件：Kafka、Elasticsearch，以及P6引入的Prometheus/Grafana |
| `ingress-nginx` | P2阶段已建立，装ingress-nginx本身，不用改动 |

好处：权限/网络策略(NetworkPolicy)可以按namespace划分粒度（比如限制"业务服务只能访问toy-infra里的Kafka，不能互相访问对方的DB连接"，为以后想练零信任网络打基础）；清理/重建某一层（比如整个重装Kafka）不会牵连业务服务；`kubectl get pods -n toy-system` 一眼看到的都是业务相关的，不会和中间件Pod混在一起看花眼。

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

### 3.6 监控归属：Prometheus/Grafana 装在集群内，而非集群外

**决策：Prometheus/Grafana 部署在k3s集群内部**（`toy-infra` namespace），而不是像Jenkins/Harbor那样放在宿主机外部。原因和CI/CD工具正好相反：

- Jenkins/Harbor放集群外，是因为它们的职责是"修复/部署集群"——放集群内会有循环依赖（集群坏了，修复工具也跟着坏）。
- **Prometheus只负责"观察"，不负责"修复"**，而且它需要频繁抓取(scrape)集群内每个Pod暴露的`/actuator/prometheus`指标端点——放在集群内部离目标更近，还能用K8s原生的Service Discovery自动发现新Pod，不用手动维护一堆外部IP。这也是`kube-prometheus-stack`这类Helm chart默认的部署方式。

**这个决策有个真实的取舍要知道**：集群内监控有个明显缺陷——如果k3s本身挂了，Prometheus也跟着挂，恰恰在最需要知道"为什么挂了"的时候看不到任何东西。大公司的解法是"remote write"：集群内Prometheus只负责就近抓取，但把关键指标实时推到一个**集群外部、独立的时序数据库**（如Thanos/Mimir/Grafana Cloud）长期存，即使集群挂了，外部这份数据和告警依然可用。对单机homelab来说，这套"集群外远程存储"暂时不用搭（投入产出比不划算），只需要知道这是完整逻辑的一部分。

**监控宿主机上的MySQL/Redis：** 这两个中间件跑在宿主机、不在k3s里（3.2节决策），集群内的Prometheus没法直接"看到"它们，需要额外在宿主机上装 **exporter**（`mysqld_exporter`、`redis_exporter`），暴露一个metrics端口，再用P5已经用过的"无selector Service"技巧，让集群内Prometheus通过Service DNS抓取它——跟连接宿主机MySQL是同一套模式，正好复用已经学过的机制，不用引入新概念。

---

## 4. CI/CD 流水线设计

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
| **P4.5（插入式练习，`policy-service`分库分表，已完成）** | 引入ShardingSphere-JDBC，`policy`表分片成`policy_0~3`（只分表不分库）；分片键为`holder_name`哈希；主键改用应用层自研的雪花ID、把分片号编码进ID里；`GET /api/policies/{id}`加`shardAware`开关，广播查询 vs 直接命中单分片两条路径并排对比（详见1.5节） | 体验分片路由、分布式主键、"分片号编码进ID"这个优化手段，以及它相对广播查询的真实提升；安排在P5之前，因为表结构定下来之后改造成本会越来越高 |
| **P5（用户计划亲自动手实现，Claude Code在此阶段暂停自动化）** | 宿主机上用Docker常驻起MySQL/Redis（模拟云托管）；k3s里建`toy-system`（业务）和`toy-infra`（中间件）两个namespace；通过"无selector Service"让业务服务连上宿主机MySQL/Redis；Kafka/ES用Helm单副本部署进`toy-infra`；所有业务服务部署到`toy-system`（先`kubectl apply`手动部署，不接CI/CD） | "生产"环境跑通一次全链路，体验k3s连接外部依赖的方式；这一阶段由用户自己手写YAML/逐步调试，不交给Claude Code自动生成，目的是扎实练习K8s原生操作 |
| **P5.5（插入式练习，不阻塞主线）** | 额外部署一个独立的MySQL StatefulSet+PV demo（不接业务数据） | 体验StatefulSet机制本身、Pod重建后数据还在 |
| **P6** | **这时候再装Prometheus/Grafana**（部署进`toy-infra` namespace），因为已经有真实的服务和流量可以观察；详见"6.5 监控归属"一节 | 看到真实的CPU/内存/QPS曲线，可以故意kill掉一个Pod观察自愈 |
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

topic名：`policy-events`（单topic多事件类型，通过 `eventType` 字段分流，符合大部分Kafka事件设计的实践）。类定义统一放在共享模块 `event-contracts` 里（见2.1节），生产者/消费者引用同一个`PolicyEvent`类，不再各自维护一份。

### 6.1 消费者容错：反序列化错误处理 + 死信Topic（Dead Letter Topic）

P3实测中遇到过消费者被一条"毒药消息"（poison pill，格式错误、反序列化失败的消息）卡死在同一个offset无限重试的问题。修订决定：**这次直接做完整，不再只做最小修复**，具体两层：

1. **反序列化层**：消费者的value-deserializer统一包一层`ErrorHandlingDeserializer`（委托给真正干活的`JsonDeserializer`），确保反序列化失败时异常能被正常交给listener容器的错误处理器,而不是直接从Kafka客户端库内部爆出来导致死循环。
2. **业务处理层 + 死信Topic**：消费者的错误处理器统一配置为`DefaultErrorHandler`搭配`FixedBackOff`（重试固定次数，比如3次，每次间隔1秒）和`DeadLetterPublishingRecoverer`——重试用尽后，这条消息（包括反序列化失败、以及业务代码抛异常两种情况）会被自动发布到一个专门的死信topic（约定命名`<原topic名>.DLT`，即`policy-events.DLT`），而不是无限卡在原topic的这个offset上，让流水线能继续往下处理后面的正常消息。
3. **死信topic目前只做到"消息不再堵塞主流程、进了死信队列"这一步**，不强制要求做人工重放/告警通知（那属于更进阶的运维能力，可以留到以后想深入时再加），但`report-service`/`search-service`引入类似消费者时也要照此模式配置，保持一致。

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

1. **Java服务采用父子Maven module**：根目录一个聚合父pom，`event-contracts`作为共享模块存放Kafka事件类，`policy-service`/`notification-service`/`search-service`都以Maven依赖方式引用同一个类，不再各自维护一份重复定义（详见2.1节，这是P3踩坑后的修订，原先"每个服务独立pom"的决定在此处调整）。`frontend`（Node生态）和不直接处理事件对象的服务不受影响。
2. 所有服务的配置（数据库连接串、Kafka地址、Redis地址）通过环境变量注入，不要硬编码——本地跑用 `.env` 或 IDE 的 run configuration，k3s里用ConfigMap/Secret。
3. 每个服务提供一个 `/actuator/health`（Spring Boot Actuator）,为后续接Prometheus做准备（P6阶段直接能用，不用返工）。
4. `policy-service` 发Kafka事件的代码要单独封装成一个 `EventPublisher` 类，P9阶段替换成Canal时，只需要把"谁来触发发送事件"这一层换掉，`EventPublisher`本身和下游消费者不用动——这是为了让P9那次"改造对比"有意义,而不是重写一遍。
5. 先按 P1→P4 顺序实现，**每完成一个P就应该能跑起来看到效果**，不要一次性把所有服务代码都写完再联调。
6. `policy-service` 的表结构变更一律通过Liquibase changelog管理，不允许在Java代码里手写"检测表是否存在、不存在就CREATE TABLE"的逻辑。
7. P5阶段k3s里指向宿主机MySQL/Redis的"无selector Service + Endpoints"配置，单独写清楚在 `infra/k8s/middleware/external-services.yaml`，并在该文件顶部用注释说明"这模拟的是云托管数据库，指向宿主机IP"，方便回头复习时一眼看懂意图。
8. P2阶段部署Ingress前，先确认并卸载k3s自带的Traefik（k3s安装参数或`kubectl -n kube-system delete`处理，具体以Claude Code实测为准），再用Helm装`ingress-nginx`，避免两个Ingress Controller同时抢80/443端口冲突。
9. **消费者统一配置死信Topic容错**（详见6.1节）：`ErrorHandlingDeserializer` + `DefaultErrorHandler`(`FixedBackOff` + `DeadLetterPublishingRecoverer`)，死信topic命名约定`<原topic名>.DLT`。这是对已完成的`notification-service`的补充修订，请针对现有代码改造，而不是只在后续新服务里应用。
10. **`policy-service`分库分表（P4.5，详见1.5节）**：用ShardingSphere-JDBC把`policy`表分片成`policy_0~3`（只分表不分库），分片键是`holder_name`的哈希（INLINE算法）。主键**不用**ShardingSphere内置的`SNOWFLAKE`算法，改成`policy-service`自己实现的雪花ID生成器，把分片号编码进ID的bit位里（分片公式必须和ShardingSphere的INLINE表达式保持一致，这是正确性的隐藏前提）。`GET /api/policies/{id}`加一个`shardAware`查询参数，`false`（默认）走原来的广播查询，`true`从`id`解码分片号后直接查单张物理表，两条路径并排存在方便对比，`shardAware=true`这条不接Redis缓存。全局二级索引方案不实现（跟"分片号编码进ID"互斥，选一个就够）。这是对已完成的`policy-service`代码做改造（新增分片配置、Liquibase changelog、`PolicyMapper.xml`、`PolicyController`/`PolicyService`），安排在P5之前实施。

---

## 8. 已确认的决定（本文档已按此定稿）

- **代码仓库**：先用 **Gitea**（资源更省，流程一致）；虽然公司实际用的Git平台带MR功能，Gitea同样支持PR/MR流程，不影响后续对照体验。
- **Harbor**：单独用docker-compose跑在宿主机上，不塞进k3s（Harbor本身依赖较重，先专注业务服务的部署闭环）。
- **有状态中间件归属**：MySQL/Redis跑在宿主机（Docker常驻+systemd），模拟云托管服务；k3s业务集群通过"无selector Service"访问；StatefulSet+PV机制作为独立练习模块，不承载真实业务数据（详见3.2/3.4节）。
- **表结构迁移工具**：Liquibase（对齐公司实践）。
- **报表库**：ClickHouse（而非继续镜像到MySQL），体验OLAP列式存储优势（详见P9）。
- **集群入口**：用 `ingress-nginx` 替换k3s默认自带的Traefik，对齐实际生产用nginx做集群入口的经验（详见3.5节，P2引入）。
- **Java模块结构**：改用父子Maven module，共享`event-contracts`模块存放Kafka事件类，替代早前"每个服务独立pom"的决定（详见2.1节，P3踩坑后修订）。
- **消费者容错**：统一加上死信Topic（`<topic>.DLT`），重试用尽后不再无限阻塞主流程（详见6.1节）。
- **分库分表**：`policy-service`用ShardingSphere-JDBC，只分表（`policy_0~3`）不分库（分库留作独立练习，不在这次范围），分片键是`holder_name`的哈希（取舍：没有独立客户服务，用姓名字符串当客户身份的替身，接受同名合并/同人不同拼写分散这个代价）。主键改用应用层自研雪花ID、把分片号编码进ID里（不是ShardingSphere内置SNOWFLAKE），配一个`shardAware`开关对比"广播查询全部分片" vs "从ID解码直接命中单分片"两条路径（详见1.5节，P4.5，安排在P4和P5之间）。