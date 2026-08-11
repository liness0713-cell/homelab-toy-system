# 问答记录

跟Claude Code聊项目设计/踩坑细节时问过的问题，按时间顺序整理，方便以后回归复习。不是实施
文档（实施细节、改动记录看各自的README和`docs/policy-service-sharding-troubleshooting.md`），
这里只记"问了什么、答案要点是什么"。以后每次问到类似"这是为什么/这样对不对"的问题，都追加
到这个文件末尾。

---

## 2026-08-11

### Homelab P5

Since we're just talking, here's my take — no file edits, just the game plan for you to execute.



1. Redis/MySQL: keep on host via Docker, don't move into k3s

Your plan doc already commits to this and I think it's right. Two reasons:



It genuinely mirrors reality — nobody runs their own RDS/ElastiCache inside their app cluster; you consume it as an external managed endpoint. Practicing "reach an external stateful service from k8s" is a more valuable, more common skill than "run MySQL in a StatefulSet" (that's explicitly P5.5's job instead, as a deliberately separate, non-blocking exercise).

It sidesteps PV/StorageClass complexity this early, which would otherwise dominate the P5 lesson instead of the actual target: namespaces, no-selector Services, and Service/Endpoint mechanics.

Mechanics: create a Service with no selector in toy-infra, plus a matching Endpoints/EndpointSlice object pointing at the host's real routable IP (not 127.0.0.1 — a pod's netns can't see that). Business pods then just talk to mysql.toy-infra.svc.cluster.local:3306 like it's any other in-cluster service — the "managed DB" illusion is transparent from the app's point of view, which is the whole point.



I checked your current infra/docker-compose.dev.yml — MySQL/Redis already bind 0.0.0.0, so this should just work. Sanity-check reachability with a throwaway pod (kubectl run tmp --rm -it --image=busybox -- sh, then nc -zv <host-ip> 3306) before wiring the real Service — much faster feedback than debugging through a full app.



One landmine you haven't hit yet: Kafka. Unlike MySQL/Redis (dumb TCP), Kafka's protocol has brokers tell clients "here's my advertised address, reconnect there" as part of the handshake. Your compose file currently advertises PLAINTEXT_HOST://localhost:9092 — that's fine for host-run services, but a pod that connects to the no-selector Service will get the initial connection through, then Kafka will hand back localhost:9092 as the address to actually produce/consume on, and inside a pod that resolves to the pod itself. Classic confusing half-working failure. Fix: change that advertised listener from localhost to the host's real IP — works for both host processes and k3s pods uniformly. ES doesn't have this problem (plain HTTP, no address-handback), so it's Kafka-specific.



2. Env vars: ConfigMap + Secret, imperative for now

Your services already default cleanly (${SPRING_DATASOURCE_URL:...} pattern everywhere) — that design already sets you up well for k8s. Natural split:



Non-secret stuff (URLs, ports, feature toggles) → ConfigMap, injected via envFrom

Passwords/JWT secret → Secret, created imperatively (kubectl create secret generic ...) since P5 explicitly stays pre-CI/CD — no need to reach for sealed-secrets/external-secrets yet, that's a GitOps-phase concern.

One thing to note: ConfigMap changes don't auto-propagate to running pods (no restart = stale env vars) unless you roll manually or use something like Reloader. That's fine to just know and manually restart when you tweak config, don't over-engineer it now.



3. Nacos — good idea, but don't fold it into P5

k8s already gives you service discovery (Service + CoreDNS) and static config (ConfigMap). What Nacos actually adds on top is dynamic config push without redeploy (change a value, app picks it up via long-poll) and, if you want it, a Spring-Cloud-style registry layered over k8s's own. That's a distinct, well-scoped lesson — mixing it into P5 would blur "bare k8s YAML mechanics" with "config-center client integration" and you'd learn both less cleanly. I'd insert it as its own phase later (something like P5.6 or post-P6, once monitoring exists so you can actually watch a config hot-reload take effect) — matches the .5-insertion pattern you've already been using for practice detours.



Things you probably haven't hit yet

No Dockerfiles exist yet for any of the 5 services (checked — only an nginx-ingress demo manifest exists under infra/k8s/). Writing those, and figuring out how images get into k3s's containerd (no registry currently configured) is real work before any Deployment YAML matters. For homelab scale, either docker save | sudo k3s ctr images import - per build, or stand up a tiny local registry:2 container — the latter is more annoying now but pays off once you're iterating fast.

Pin image tags (not latest) even without CI/CD — imagePullPolicy: IfNotPresent + latest is a classic "why is it still running the old code" trap.

Wire your existing /actuator/health endpoints into readinessProbe/livenessProbe — you already built these, free win.

Cross-namespace DNS: toy-system pods reaching toy-infra Services need the full <svc>.<ns>.svc.cluster.local form, not the short name.

Check host firewall (ufw/firewalld) isn't blocking the k3s pod CIDR from reaching your host-bound MySQL/Redis/Kafka ports — easy to forget, only shows up as pods timing out.

Loosely set resource requests/limits even if the numbers are guesses — worth the muscle memory since P6 (Prometheus/Grafana) will make the consequences of not setting them visible.

That covers your three questions plus the gaps I'd flag. Shout if you want me to go deeper on any one piece (e.g. the exact no-selector Service/Endpoints shape, or the Kafka listener fix) once you're actually in it.


### Q: P5要上k3s了，给个行动手册。MySQL/Redis是用现在docker-compose拉起来的这套，还是在k3s里另外拉？环境变量怎么处理？能不能引入Nacos？

- **MySQL/Redis**：维持现状，继续用宿主机Docker常驻（`infra/docker-compose.dev.yml`），不搬进
  k3s。用一个无`selector`的`Service` + 手动`Endpoints`指向宿主机真实IP（不是`127.0.0.1`，pod
  netns看不到）来接，业务pod用`mysql.toy-infra.svc.cluster.local`这种k8s内部域名访问，感觉
  上跟"托管数据库"一样。MySQL/Redis是StatefulSet+PV的独立练习，留给P5.5，不在P5这次做。
- **踩坑预警——Kafka不是纯TCP协议**：跟MySQL/Redis不同，Kafka客户端连上broker后，broker会
  用`advertised.listeners`里配的地址告诉客户端"真正生产/消费请重连到这个地址"。当前compose
  配置里`PLAINTEXT_HOST`广播的是`localhost:9092`——宿主机进程连没问题，但k3s pod连上去之后，
  broker返回的`localhost:9092`在pod netns里指向pod自己，会看起来"能连上但读写莫名其妙失败"。
  上k3s前得把这个广播地址从`localhost`换成宿主机真实IP。ES没有这个问题（纯HTTP，没有地址
  回传这一套）。
- **环境变量**：非敏感配置（URL、端口、开关）用`ConfigMap`；密码/JWT密钥这类用`Secret`，
  P5阶段不接CI/CD，`Secret`先用`kubectl create secret`命令式创建就行，不用上
  sealed-secrets/external-secrets那一套。注意`ConfigMap`改了不会自动让运行中的pod感知，得
  手动滚动重启。
- **Nacos**：思路可以，但建议单独开一个插入式阶段（类似P4.5/P5.5那种"不阻塞主线"的练习），
  不要跟P5混在一起做——P5的目标是练k8s原生的namespace/无selector Service这些机制本身，
  Nacos解决的是"配置热更新不用重启"这个跟k8s ConfigMap不完全重叠的问题，混着学反而学不透。
  等P6监控搭起来之后再做，正好能实际看到热更新生效的过程。
- **顺带指出的、没问到但值得注意的点**：5个业务服务目前一个Dockerfile都没有（`infra/k8s/`
  下只有个nginx-ingress的demo manifest），镜像怎么进k3s的containerd（本地没配registry）
  得先解决；镜像tag别用`latest`；已有的`/actuator/health`可以直接接
  readiness/livenessProbe；跨namespace访问k8s Service要写全`<svc>.<ns>.svc.cluster.local`；
  留意宿主机防火墙（ufw/firewalld）有没有挡住k3s pod网段访问宿主机端口；resource
  requests/limits哪怕先拍脑袋定也建议现在就开始写，P6接监控之后能看到不设置的后果。

### Q: `/home/ziqiao/Documents/homelab-toy-system/infra-common-sources.jar` 这是啥？

`org.apache.shardingsphere:shardingsphere-infra-common`模块的sources jar，是调试
`StorageUnit`那个NPE时手动从Maven Central下载到仓库根目录、拿来读源码用的，跟Maven构建流程
没关系。已被`.gitignore`的`*.jar`规则忽略（不会误提交），确认没用后已删除。

### Q: 这个jar其实没用到对吧？

分两层说清楚：`shardingsphere-infra-common`这个**库本身**是真实的编译期依赖
（`mvn dependency:tree`能查到，经`shardingsphere-jdbc`间接引入，`StorageUnit`这个类就在
里面，运行时真的会跑到）；但仓库根目录那个具体的**sources jar文件**确实没被用到——纯粹是
手动下载读源码的临时产物，Maven真正用的那份缓存在`~/.m2/repository`里，是完全独立的另
一份，从没经过仓库根目录这个文件。

### Q: 读它是为了啥？读了对你有帮助吗？

当时那个NPE（`Map.get(Object) is null` at `StorageUnit.<init>`）信息量很低，只知道哪行炸了，
不知道为什么；换了两种不同的DataSource构造方式还是同样报错，说明问题不在"怎么构造"、在
ShardingSphere内部缺东西。读源码发现它在算`standardProps.get("url")`，缺的是"连接池属性名
映射"这层能力，直接反查到`shardingsphere-infra-data-source-pool-hikari`这个模块名，加上就
解决了。这是整个ShardingSphere踩坑过程里唯一一次报错文本没有可搜索关键词、必须靠读源码才能
定位根因的情况——其余大部分坑报错里都直接带着接口名/SPI type名，能直接搜出该加哪个模块。

### Q: Hikari是ShardingSphere连接池的一个选项，用别的连接池（Druid/DBCP2等）也行吧？

架构上可行——`shardingsphere-infra-data-source-pool-core`里有个`DataSourcePoolMetaData`
接口（SPI），Hikari那个模块就是针对Hikari的一份实现。但去Maven Central查了
`org/apache/shardingsphere/`下所有`pool`开头的artifact，5.5.3这个版本官方只发布了Hikari
这一个具体实现，没有现成的`-pool-druid`/`-pool-dbcp`模块。想换的话得自己写这个SPI实现类，
或者退回`pool-core`里那个`DefaultDataSourcePoolFieldMetaData`兜底实现（没细查够不够用）。

### Q: ShardingSphere自己带了Hikari驱动，不需要显式引入Hikari依赖了；我们是不是从"mybatis直接用jdbc没连接池"变成了"ShardingSphere-Hikari-jdbc-mysql driver"这条链路？

两处纠正：
1. `mvn dependency:tree`显示`shardingsphere-infra-data-source-pool-hikari`模块下不带
   `com.zaxxer:HikariCP`本体，真正带来HikariCP的是`mybatis-spring-boot-starter →
   spring-boot-starter-jdbc`这条线，跟ShardingSphere无关。
2. 查了P1最初commit的`application.yml`，当时就是标准`spring.datasource.*`配置，会触发
   Spring Boot的`DataSourceAutoConfiguration`——这个自动配置从Spring Boot 2.x起默认就用
   HikariCP（前提是classpath上有，而它确实一直都在）。所以从P1第一天起底层就是HikariCP在
   管连接池，不是裸JDBC；这次改造用的是**同一个**HikariCP jar，只是从"Spring Boot自动配好
   看不见"变成"自己在`ShardingSphereConfig`里手动构造能看见"。

链路描述方向本身没错，只是"jdbc"不是链路上单独一跳——它是ShardingSphere、Hikari、MySQL驱动
这三层共同讲的同一套接口规范（`java.sql.Connection`/`DataSource`），不是夹在中间的另一层
东西。

### Q: MySQL Connector本身也要实现JDBC的接口吗？

要。JDBC（`java.sql`/`javax.sql`）是Java定义的一套接口规范，MySQL Connector/J是这套规范的
一份具体实现：`com.mysql.cj.jdbc.Driver`实现`java.sql.Driver`（通过
`META-INF/services/java.sql.Driver`这套SPI机制注册），还有对应的`Connection`/
`Statement`/`PreparedStatement`/`ResultSet`/`DatabaseMetaData`实现类，负责把这些接口方法
调用翻译成MySQL自己的二进制wire协议。ShardingSphere-JDBC、HikariCP、MySQL Connector/J这
三层能像搭积木一样互相包裹，正是因为它们都在讲同一套接口——上层只需要认得接口本身，不需要
知道下层具体是谁的实现。（`DatabaseMetaData`正是之前Liquibase跟ShardingSphere冲突那次的
具体接口，Liquibase靠自省这个接口判断表存不存在。）

### Q: Spring循环依赖那部分没太看懂（bean、"图"的概念不熟）；ShardingSphere为什么带了配置分发、鉴权这些"多余"的功能——如果每个pod镜像都一样、配置文件放本地，感觉根本不会冲突；鉴权那块更迷惑，数据库大家都要用，分开鉴权会不会互相冲突？

**Spring的"图"是什么**：Spring启动时要把`@Bean`声明变成真正的Java对象，并按"谁需要谁先造好"
把它们接起来——这种"A需要B先存在"的关系画成箭头，就是"依赖图"。成环就是A等B、B等C、C等A，
谁都排不到"第一个造"。Spring靠一个运行时"正在建造中"的栈发现环：递归造A时又要造B，造B时又要
拿A，而A还在栈上没建完，直接报错。

`@DependsOn`是手动在图上加一条箭头（"B必须先造完"），跟"A是否真的持有B的Java引用"无关。我们
加`dataSource → liquibase`这条箭头时，恰好Spring自己的`LiquibaseAutoConfiguration`构造函数
里有个`ObjectProvider<DataSource>`参数——Spring画图只看"构造函数声明了什么类型"，不管运行时
到底调不调用，于是自动画出一条`liquibase → dataSource`的反向箭头，两条箭头一凑就成环，即使
这个`ObjectProvider`全程没被调用过。`allow-circular-references`救不了，是因为它的原理是"先给
一个没建完的半成品引用占位，回头再补"，只对"字段/属性里存着对方引用"这种场景有效；`@DependsOn`
表达的是纯施工顺序，没有"半成品"这个概念可用。最终用`LazyDataSource`绕开：这个bean造出来时是
个空壳，真正连数据库这件事推迟到第一次`getConnection()`，而`mybatis-spring-boot-starter`
自带的`DependsOnDatabaseInitializationDetector`机制已经保证MyBatis相关bean排在Liquibase
后面，不需要我们自己再手动画一条边。

**ShardingSphere为什么这么"重"**：直觉是对的——我们确实只用到它"SQL改写+路由"这一小块能力。
但ShardingSphere不是专做分表的小工具，是个野心大得多的分布式数据库中间件平台，分表只是它众多
能力之一（还有字段加密、读写分离、影子库、在线改分片规则等），而且支持两种部署形态：JDBC模式
（嵌进应用进程，我们用的这种）和Proxy模式（独立进程，对外假装自己是一台MySQL，任何客户端都能
像连真实数据库一样连上它）。两种模式共享同一套内核。**鉴权模块是给Proxy模式准备的**——Proxy
场景下多个不同调用方带着各自账号连进来，需要像真数据库一样判断权限；JDBC模式下调用方永远只有
本进程自己，根本不存在这个问题，但内核代码是共用的，这个SPI接口必须有实现存在，我们填的是最
简单的那个（`ALL_PERMITTED`，谁都能干）——不是我们的系统需要权限控制，是框架骨架要求这块拼图
必须有东西填上。

务必分清两层，不然容易觉得"大家都连数据库会冲突"：MySQL自己的账号密码（Hikari实际拿去连真实
MySQL用的）是一层；ShardingSphere自己的鉴权层（管"谁能对ShardingSphere这个门面发SQL"）是
另一层，JDBC模式下这个"谁"永远只有本进程自己，不存在跨pod/跨服务的鉴权冲突。

**"配置分发"（governance mode）这层**存在的真正原因，是多个ShardingSphere实例需要看到一致
路由规则的场景（多个Proxy副本、或者不停机在线改分片数——用ZooKeeper/etcd同步配置，这是真实的
分布式一致性问题，处理不好会出现"实例A把这行写到shard_3、实例B以为它在shard_1"的数据错乱）。
我们的配置是打进镜像的静态YAML，每个pod各自读一份、算出来的结果保证一致，完全不做在线改规则，
这正是当初选`Standalone`+`Memory`模式的原因——这就是"我不需要跨实例同步，每个实例自己算自己
的就行"这个直觉对应的选项。上k3s之后除非要做"不重新部署镜像、在线加分片数"这种高阶操作，否则
这个模式不需要换。

一句话总结："重"不是配置得复杂，是ShardingSphere这个工具的定位比我们的需求大得多——我们只用
到它5%的能力，但骨架上为另外95%能力准备的插座（鉴权、多部署形态、分布式一致性）必须有东西
插上才能启动，这是用通用框架做窄需求要交的"骨架税"。


Part 1: Spring 的"图"到底是什么
Spring 启动时本质在做一件事：把你写的一堆 @Bean/@Component 变成真正的 Java 对象，并把它们互相接起来（PolicyService构造函数要一个PolicyMapper实例，PolicyMapper要一个SqlSessionFactory，以此类推）。

想象成"先有鸡还是先有蛋"的施工顺序问题：要造PolicyService，得先有PolicyMapper现成地摆在那儿。所以画一条箭头 PolicyService → PolicyMapper，意思是"造前者之前必须先造完后者"。把所有bean之间这种"我需要你先存在"的关系都画出来，就是这张"图"。Spring启动就是按这张图从没有依赖的bean开始，一个个往上造。

**"成环"**就是：A要B先造好，B要C先造好，C又要A先造好——三个谁都排不到"第一个造"，无解，这是真正意义上的死锁。Spring怎么发现的？它维护一个"当前正在建造中、还没建完"的栈：造A的过程中递归去造B，造B的过程中又要拿A——但A明明还在"建造中"没完工，这时候直接报错，不会真的卡死。

关键点：@DependsOn就是你手动在这张图上加一条箭头，跟"这两个bean有没有Java对象引用关系"无关，纯粹是"我要求B必须先造完，哪怕A根本不持有B的引用"。我们加@DependsOn("liquibase")就是手动画了一条dataSource → liquibase的箭头。

但坑在于：LiquibaseAutoConfiguration这个Spring官方类的构造函数里，本来就带了一个ObjectProvider<DataSource>参数（一个"如果你要用DataSource我给你，不用也没事"的兜底占位符）。Spring在画图的时候，只看"这个构造函数写了什么类型的参数"，不管你运行时到底调不调用它——所以Spring自动画了一条liquibase → dataSource的箭头。我们自己画的dataSource → liquibase，加上Spring自动画的liquibase → dataSource，两条箭头首尾相接，就是一个最小的环，即使这个ObjectProvider参数我们代码里从头到尾没调用过一次。这就是为什么"感觉没有循环引用"但Spring坚持说有环——它按"声明了什么类型"判图，不按"运行时真的用没用"判图。

spring.main.allow-circular-references救不了，是因为它的原理是"先给你一个还没建完的半成品对象引用，你先攥着，我回头再把它建完"——这只对"A的字段里存着B的引用"这种场景有效（可以先给假引用占位）。@DependsOn表达的是纯粹的施工顺序，不是"谁存着谁的引用"，没有"半成品"这个概念可用，所以这个开关对它无效。

最后用LazyDataSource绕开，本质是不画这条箭头：dataSource这个bean造出来的时候只是个空壳（连Liquibase是谁都不关心），真正需要连数据库、需要"Liquibase是不是跑完了"这件事，被推迟到第一次有人调getConnection()——而mybatis-spring-boot-starter自己已经通过另一套机制（不是@DependsOn，是Spring Boot内置的DependsOnDatabaseInitializationDetector）保证了"MyBatis相关bean一定排在Liquibase后面"。所以不需要我们自己再手动加一条边，顺序天然就对。

Part 2: ShardingSphere为什么这么"重"
你的直觉——"不就是代理请求分发到真实表吗，为什么还带配置分发、鉴权"——完全说到点子上了，而且这恰恰是问题所在：ShardingSphere不是一个"专门做分表代理"的小工具，它是一个野心大得多的"分布式数据库中间件平台"，分表只是它支持的众多能力之一。它同时还支持：字段级加密、读写分离、影子库（灰度/压测流量隔离）、DistSQL在线改分片规则……而且支持两种完全不同的部署形态：

JDBC模式（我们用的）：一个Java库，嵌进你的应用进程里，没有独立网络端口，你的应用是唯一的"客户端"
Proxy模式（我们没用）：一个独立跑起来的服务进程，对外假装自己是一台MySQL，监听3306端口，任何MySQL客户端（不同的微服务、DBA工具、mysql cli）都可以像连真实MySQL一样连上它
这两种模式共享同一套内核代码——这解释了为什么"鉴权"这种东西会出现：鉴权模块是给Proxy模式准备的。Proxy模式下，多个完全不同的调用方带着各自的账号密码连进来，ShardingSphere得像真数据库一样做"这个账号能不能查这张表"的判断，这时候鉴权是刚需。我们用的是JDBC模式，调用方只有我们自己这一个应用进程，压根不存在"多个外部客户端连进来需要区分权限"这回事——但因为内核代码是共用的，这个SPI接口必须有"某个实现"存在，哪怕是最简单的"所有人啥都能干"（ALL_PERMITTED，我们配的那个）。这不是我们的系统需要权限控制，是框架的骨架要求这块拼图必须有东西填上，我们填的是最省事的那块。

务必分清两层，不然容易觉得"大家都连数据库会冲突"：

MySQL自己的账号密码（toy_app/toy_app_pw）——这是ShardingSphere内部通过Hikari实际拿去连真实MySQL用的，跟下面这层完全无关
ShardingSphere自己的鉴权层——管的是"谁能对ShardingSphere这个门面发SQL"，JDBC模式下这个"谁"永远只有本进程自己，根本不存在跨pod、跨服务的鉴权冲突问题
Part 3：你的直觉对不对——k8s下"每个pod一样的image、配置本地放"
完全对，而且这就是我们已经选的方案。"配置分发"这层（ShardingSphere叫"governance mode"）存在的真正原因是：如果你跑的是多个ShardingSphere实例互相要看到一致的路由规则（比如多个Proxy副本，或者支持"不停机在线加分片"这种运维操作，用ZooKeeper/etcd把配置往所有实例同步），那"大家看到的规则必须一致"就是一个真实的分布式一致性问题，不这么做可能出现"实例A把这行写到shard_3、实例B以为它在shard_1"这种数据错乱。

但我们完全不做在线改规则这种事，配置文件是打进镜像里的静态YAML，每个pod启动时各自读一份、算出来的结果保证一致（因为公式和输入都一样）——这正是我们当初选Standalone + Memory模式的原因：这就是ShardingSphere给"我不需要跨实例同步，每个实例自己算自己的就行"这种场景准备的选项，等于是显式选了"不要分布式一致性这一层，我不需要"。上k3s之后除非你想做"不重新部署镜像、直接在线加分片数"这种高阶操作，否则这个模式不需要换，你的直觉是对的，我们已经站在这个直觉对应的那个选项上了。

一句话总结整个ShardingSphere的"重"：我们只用到它5%的能力（SQL改写+路由），但因为它是个通用框架，骨架上那些为其他95%能力准备的插座（鉴权、多种部署形态、分布式一致性）必须有东西插上去，才能编译通过、启动起来——这不是配置得复杂，是这个工具本身的定位比我们的需求大得多，属于"杀鸡用牛刀，但牛刀有牛刀自己的开机自检流程"。