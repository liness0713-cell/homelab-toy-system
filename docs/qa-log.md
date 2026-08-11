# 问答记录

跟Claude Code聊项目设计/踩坑细节时问过的问题，按时间顺序整理，方便以后回归复习。不是实施
文档（实施细节、改动记录看各自的README和`docs/policy-service-sharding-troubleshooting.md`），
这里只记"问了什么、答案要点是什么"。以后每次问到类似"这是为什么/这样对不对"的问题，都追加
到这个文件末尾。

---

## 2026-08-11

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
