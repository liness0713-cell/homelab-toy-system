# policy-service 分库分表（P4.5）踩坑记录：ShardingSphere集成的试错过程

这份文档记录给 `policy-service` 接入 ShardingSphere-JDBC 5.5.3 时踩的坑——主要是"要加多少个
依赖才能跑起来"和几个真实的运行时报错，按实际遇到的顺序写。设计动机、分片键选择、id编码方案
这些"为什么这么设计"的内容在 `docs/homelab-toy-system-plan.md` 的"分库分表练习"一节，这里只记
"怎么一步步跑通的"。

## 查Maven版本用到的命令

开始之前先说一下怎么查一个Maven依赖有哪些可用版本、群组下有哪些artifact——这部分之前不熟，
记一下：

**查某个artifact的所有版本**（返回一份XML，`<version>`标签列出所有发布过的版本号）：

```bash
curl https://repo1.maven.org/maven2/org/apache/shardingsphere/shardingsphere-jdbc/maven-metadata.xml
```

把路径换成对应的 `groupId`（`.`换成`/`）+ `artifactId` 就行，比如查 `com.zaxxer:HikariCP`：

```bash
curl https://repo1.maven.org/maven2/com/zaxxer/HikariCP/maven-metadata.xml
```

**浏览某个groupId下面有哪些artifact**（不知道具体模块叫什么名字的时候）：直接访问该groupId
对应的目录，Maven Central会返回一个可浏览的目录列表：

```
https://repo1.maven.org/maven2/org/apache/shardingsphere/
```

用浏览器打开这个URL，或者 `curl`它拿到HTML然后自己grep模块名关键字（比如`grep -oP
'href="\K[^"/]+(?=/")' 配合curl拿到的页面`），能看到`shardingsphere-sharding-core`、
`shardingsphere-single-core`这些一个个子模块目录。整个ShardingSphere依赖链就是这么一个个
试出来该加哪个模块的。

也可以用 [search.maven.org](https://search.maven.org) 搜索框直接搜关键字（比如搜
"shardingsphere sharding"），网页搜索比啃目录列表更快，但遇到不确定该搜什么关键字的情况
（比如某个SPI报错但不知道该找哪个模块补），还是得回到读源码/读报错堆栈+试错这条路。

## 目标

`policy-service`原来是单表`policy`，直连MySQL，MyBatis + Liquibase标准套路。这次要改成
ShardingSphere-JDBC接管，`policy`拆成`policy_0`~`policy_3`四张物理表（都在同一个MySQL实例/
schema里，只做分表不做分库），应用代码尽量不感知底层分片——MyBatis还是对着"policy"这张逻辑
表写SQL，ShardingSphere在JDBC驱动层把SQL路由/改写到实际物理表上。

## 第一步：加shardingsphere-jdbc，发现远不止一个依赖

一开始以为加一个`org.apache.shardingsphere:shardingsphere-jdbc:5.5.3`就够了（这是核心driver）。
实际每次启动都报一个新的"缺东西"的错，加一个模块，报下一个错，如此反复。最终确认：ShardingSphere
5.5.x把几乎所有功能都拆成了独立的可插拔module（通过Java SPI机制在运行时加载实现类），核心jar本身
只是一个装配框架，具体能力都在这些feature模块里。以下按实际遇到错误的顺序记录。

### 报错1：`!SHARDING`这个YAML tag不认识

```
Cannot create property=rules for JavaBean ... Invalid tag: !SHARDING
```

`shardingsphere-config.yaml`里用`!SHARDING`这个YAML tag声明分片规则，但注册这个tag的SPI实现
在一个单独的模块里，光有`shardingsphere-jdbc`不够（这个artifact是纯装配框架，具体的sharding
规则解析逻辑不在里面）。加：

```xml
<dependency>
  <groupId>org.apache.shardingsphere</groupId>
  <artifactId>shardingsphere-sharding-core</artifactId>
  <version>5.5.3</version>
</dependency>
```

（顺带一提：`shardingsphere-sharding`这个artifact名字看着像是对的，但它只是个pom聚合项目，
没有实际的jar内容，会误导人；实际起作用的是`shardingsphere-sharding-core`。）

配置里的INLINE分片算法用Groovy表达式（`policy_${Math.abs(holder_name.hashCode()) % 4}`）
求值，这块求值引擎也是独立模块：

```xml
<dependency>
  <groupId>org.apache.shardingsphere</groupId>
  <artifactId>shardingsphere-infra-expr-groovy</artifactId>
  <version>5.5.3</version>
</dependency>
```

以及MySQL方言识别/SQL解析改写（不装这个ShardingSphere连URL里的"这是MySQL"都识别不出来）：

```xml
<dependency>
  <groupId>org.apache.shardingsphere</groupId>
  <artifactId>shardingsphere-jdbc-dialect-mysql</artifactId>
  <version>5.5.3</version>
</dependency>
```

### 报错2：`StorageUnit`构造时NPE

```
Cannot invoke "Object.toString()" because ... Map.get(Object) is null
  at org.apache.shardingsphere...StorageUnit.<init>
```

第一次尝试是用`Map<String,DataSource>`代码构造DataSource再传给ShardingSphere API，同样报这个
NPE；改成纯YAML描述数据源也一样。下载`StorageUnit.java`源码读了一下，发现它内部要从
`standardProps.get("url")`取值——这需要ShardingSphere知道怎么把Hikari的`jdbcUrl`属性名映射成
它自己内部标准化的`url`属性名（不同连接池属性名不一样：Hikari叫`jdbcUrl`，Druid/DBCP可能叫别的）。
这层属性名映射也是独立SPI模块：

```xml
<dependency>
  <groupId>org.apache.shardingsphere</groupId>
  <artifactId>shardingsphere-infra-data-source-pool-hikari</artifactId>
  <version>5.5.3</version>
</dependency>
```

加上之后NPE消失。

### 报错3：`ContextManagerBuilder`的SPI找不到实现

```
SPI-00001: No implementation class load from SPI 'ContextManagerBuilder' with type 'null'
```

ShardingSphere哪怕只是单机JDBC场景，也要求显式声明一个"governance mode"（用来管理运行时元数据/
配置的持久化方式）。查了一下有几种mode实现，先试了`shardingsphere-memory-mode-core`（名字看起来
最匹配"不用外部存储"的需求），结果Maven Central上这个artifact最高版本停在5.1.2，跟当前用的
5.5.3不兼容（已废弃/被替代）。改用`Standalone`模式 + 内存repository：

```yaml
mode:
  type: Standalone
  repository:
    type: Memory
```

```xml
<dependency>
  <groupId>org.apache.shardingsphere</groupId>
  <artifactId>shardingsphere-standalone-mode-core</artifactId>
  <version>5.5.3</version>
</dependency>
<dependency>
  <groupId>org.apache.shardingsphere</groupId>
  <artifactId>shardingsphere-standalone-mode-repository-memory</artifactId>
  <version>5.5.3</version>
</dependency>
```

这个组合的语义是：不落盘持久化元数据，每次进程启动都从`shardingsphere-config.yaml`重新加载，
符合这个玩具系统"配置即代码、不需要跨重启保留额外状态"的场景（生产环境一般会用ZooKeeper/etcd
之类的repository做跨实例配置同步，这里用不上）。

### 报错4：`commons-lang3`缺类

```
NoClassDefFoundError: org/apache/commons/lang3/Strings
```

Spring Boot自己的依赖管理（dependency management BOM）锁定的`commons-lang3`版本比较老，没有
ShardingSphere 5.5.3用到的`org.apache.commons.lang3.Strings`这个类（是较新版本才加进去的）。
显式声明一个更新的版本覆盖掉Spring Boot BOM锁定的版本：

```xml
<dependency>
  <groupId>org.apache.commons</groupId>
  <artifactId>commons-lang3</artifactId>
  <version>3.20.0</version>
</dependency>
```

### 报错5：`PrivilegeProvider`的SPI找不到实现

```
SPI-00001: No implementation class load from SPI 'PrivilegeProvider' with type 'ALL_PERMITTED'
```

权限/鉴权模块，同样是独立可插拔的：

```xml
<dependency>
  <groupId>org.apache.shardingsphere</groupId>
  <artifactId>shardingsphere-authority-core</artifactId>
  <version>5.5.3</version>
</dependency>
<dependency>
  <groupId>org.apache.shardingsphere</groupId>
  <artifactId>shardingsphere-authority-simple</artifactId>
  <version>5.5.3</version>
</dependency>
```

（`shardingsphere-authority-simple`提供的就是报错里那个最简单的`ALL_PERMITTED`默认provider——
这个玩具系统里ShardingSphere连接的DB账号本来就是应用专用账号，不需要更细粒度的权限模型。）

到这一步，依赖链本身基本齐了。加完这5轮之后应用总算能正常启动、跑通一次简单的INSERT。

## 第二步：Liquibase遇到ShardingSphere，两边"打起来"了

依赖装完、应用能启动之后，跑Liquibase迁移时报错：

```
Table 'DATABASECHANGELOG' already exists [Failed SQL: CREATE TABLE DATABASECHANGELOG...]
```

Liquibase自己的"这张表存在吗"检测逻辑，是通过`DatabaseMetaData`自省实现的。ShardingSphere-JDBC
包装过的`Connection`返回的`DatabaseMetaData`跟Liquibase的检测逻辑对不上，导致Liquibase误判
`DATABASECHANGELOG`不存在，重复尝试`CREATE TABLE`，撞到已存在的表上报错。

第一次尝试：给ShardingSphere加`!SINGLE`规则（`shardingsphere-single-core`模块），让它正确识别
"没配分片规则的普通表"（比如`DATABASECHANGELOG`本身就不该被分片）：

```yaml
rules:
  - !SINGLE
    tables:
      - "*.*"
```

```xml
<dependency>
  <groupId>org.apache.shardingsphere</groupId>
  <artifactId>shardingsphere-single-core</artifactId>
  <version>5.5.3</version>
</dependency>
```

加了之后报错变了（说明这一步确实有作用，ShardingSphere自己现在正确识别出表已存在了）：

```
org.apache.shardingsphere...TableExistsException: null
```

但Liquibase自己的检测逻辑还是没对上，还是会走到CREATE TABLE这一步。最终采用的是标准做法：
**Liquibase单独直连真实MySQL，完全不经过ShardingSphere这一层**——DDL操作的对象是实际物理表名
（`policy_0`~`policy_3`），本来就不需要走分片路由，直连反而更简单可靠。改法是在
`application.yml`里单独配置：

```yaml
spring:
  liquibase:
    url: ${SPRING_DATASOURCE_URL:...}
    user: ${SPRING_DATASOURCE_USERNAME:toy_app}
    password: ${SPRING_DATASOURCE_PASSWORD:toy_app_pw}
```

（`shardingsphere-single-core`这个依赖后来还是留着了，MyBatis正常查询路径下ShardingSphere仍然
需要正确识别哪些表是"未分片的普通表"。）

## 第三步：Liquibase和ShardingSphere各连各的之后，启动顺序又炸了

Liquibase改成直连之后，出现一个新错误：

```
TableNotFoundException: Table or view 'policy' does not exist
```

这个错误发生在应用刚启动、第一次真正查询数据库的时候。根因是启动顺序问题：ShardingSphere的
DataSource在被Spring**构造**的那一刻，会扫描一次"actualDataNodes里配的物理表实际存不存在"，
并且**只扫这一次，永久缓存结果**，之后即使表被建出来了也不会重新扫描。而现在Liquibase用的是
完全独立、跟ShardingSphere的DataSource bean没有任何声明关系的连接——Spring没有理由保证
"先跑完Liquibase迁移、再构造ShardingSphere的DataSource"这个顺序，实际观察到的是ShardingSphere
的扫描发生在Liquibase建表之前，于是永远记住了"这些表不存在"。

**第一次尝试**：`@DependsOn("liquibase")`加在`dataSource()`这个`@Bean`方法上，强制Spring先
初始化Liquibase相关bean。结果触发了循环依赖检测：

```
The dependencies of some of the beans in the application context form a cycle:
   policyService → policyMapper → liquibase → dataSource → policyMapper（成环）
```

原因是`LiquibaseAutoConfiguration`的构造方法里有一个`ObjectProvider<DataSource>`类型的兜底
参数——这个参数实际运行时根本用不到（因为我们的Liquibase走的是`spring.liquibase.url`直连配置，
不需要注入DataSource bean），但Spring对bean依赖图做**静态**分析时，只看构造方法签名，不管
这个参数运行时会不会真的被用到，一律当成一条真实的依赖边处理，于是被识别成了环。

**第二次尝试**：不用声明式的`@DependsOn`，改成在`@Bean`方法体里手动调用
`applicationContext.getBean(SpringLiquibase.class)`（命令式触发）。以为这样能绕开Spring
静态图分析——结果还是报同样的循环依赖。原因是Spring的循环检测**不是**基于声明式元数据的静态图
分析，而是基于一个运行时"当前正在创建中的bean"栈跟踪：不管你是通过`@DependsOn`声明的，还是在
方法体里手动`getBean()`触发的，只要在创建A的过程中递归触发了创建B，而创建B的过程中又要用到A，
运行时栈跟踪一样能抓到这个环，两种触发方式在这一点上等价。

**第三次尝试**：加`spring.main.allow-circular-references: true`，同时保留`@DependsOn`。
还是不行：

```
Despite circular references being allowed, the dependency cycle between beans could not be broken.
```

这个开关只对"属性注入/字段注入"形成的环有效——原理是Spring可以先给出一个尚未完全初始化的
早期bean引用（early reference），让另一个bean先把这个引用注入进去，回头再补完初始化。但
`@DependsOn`declares的是**纯粹的初始化顺序约束**（"必须等B完全初始化完，A才能开始初始化"），
不涉及"先给一个早期引用回头再补"这种机制，所以这个开关救不了。

**最终方案**：写一个懒加载的`DataSource`包装类（`LazyDataSource`，双重检查锁），把"真正构造
ShardingSphereDataSource"这件事推迟到第一次有人真的调用`getConnection()`的时候，而不是Spring
装配这个bean的时候。这样`dataSource()`这个bean本身不需要对Liquibase声明任何依赖（bean本身
构造很轻量，不连真实数据库，不触发ShardingSphere的表结构扫描），自然就不会被判定成环。

那"顺序对不对"这个问题怎么保证？观察到：每一次报错的堆栈里，`policyMapper`都会依赖到
`liquibase`（因为`mybatis-spring-boot-starter`本身通过Spring Boot的
`DependsOnDatabaseInitializationDetector`这套SPI机制，已经自动保证了MyBatis相关bean会在
Liquibase迁移跑完之后才创建）。所以只要"真正连接数据库"这件事被推迟到第一次MyBatis查询发生
的那一刻，Liquibase必然早就跑完了——不需要我们自己再显式声明一次依赖顺序。

这个方案跑通后，成功创建了一条记录（id `1349058480844800`），直接查MySQL验证行确实落在了
`policy_3`表里，Python脚本独立跑一遍id解码逻辑（`extractShard()`）也算出`3`，两边吻合。

## 第四步：`shardAware=true`直连物理表报表不存在

`GET /api/policies/{id}?shardAware=true`这条对比路径，最初设计是让MyBatis mapper直接对物理
表名（比如`policy_3`）发SQL（`FROM policy_${shardIndex}`），但走ShardingSphere包装过的
DataSource还是报错：

```
TableNotFoundException: Table or view 'policy_3' does not exist.
```

而这时候直接查MySQL能确认`policy_3`这张表、这一行数据都是真实存在的。查下来的结论是：
`policy_0`~`policy_3`是`policy`这张逻辑表在`!SHARDING`规则里声明的`actualDataNodes`，一旦
被声明成某个逻辑表的物理分片，ShardingSphere的SQL路由/元数据层就不允许再绕开逻辑表名、直接
按物理表名寻址它们了——哪怕加了`!SINGLE`的`"*.*"`通配也不行（单表加载器会跳过已经被其他规则
接管的表名，避免规则冲突/双重路由）。也就是说：直接对物理表名发SQL这件事，压根不在
ShardingSphere设计上支持的范围内（这样设计是合理的：如果允许绕开逻辑表随意直连物理分片，
就相当于给了一条能绕过ShardingSphere自身路由/元数据一致性保证的后门）。

最终方案是给"shardAware=true"这条对比路径配一个**完全独立、不经过ShardingSphere的原生
`DataSource`**（`rawMySqlDataSource`，普通`HikariDataSource`直连同一个MySQL实例/schema），
用`JdbcTemplate`直接对物理表名查询（`ShardTableReader`）。回头看这个方案其实比继续跟
ShardingSphere的内部限制较劲更合理——`shardAware=true`这条路径本来的教学目的就是"演示已经
知道该打哪个分片、绕开ShardingSphere路由决策直接查"，用一条真正独立于ShardingSphere的连接
来做这件事，语义上更准确。

代码层面：`ShardingSphereConfig`里给原来的`dataSource()`（ShardingSphere包装过的那个）加了
`@Primary`（避免Spring Boot自动配置在看到多个`DataSource` bean时不知道该给MyBatis/Liquibase
用哪个），另外注册了一个不带`@Primary`的`rawMySqlDataSource()`，只有`ShardTableReader`会显式
通过`@Qualifier`引用它。`PolicyMapper`里原来的`findByIdInShard`（MyBatis mapper方法，走
ShardingSphere连接）整个删掉了，因为这条路径根本没法通过MyBatis+ShardingSphere实现。

## 第五步：更新/取消保单报错——碰到了分片键不让改这条硬限制

修完上面那个问题、跑全量回归验证的时候，`PUT /api/policies/{id}`和
`POST /api/policies/{id}/cancel`都报500：

```
java.sql.SQLException: Can not update sharding value for table 'policy'.
```

原因：`PolicyMapper.xml`里`update`这条SQL的`SET`子句一直都包含`holder_name = #{holderName}`
（改造前单表阶段就有，分片改造时没注意到这一处）。`holder_name`现在是分片键，UPDATE去改一个
分片键的值，语义上等于"要把这一行从物理表A搬到物理表B"——这不是普通UPDATE语句能做到的事情
（UPDATE是原地改列值，不会重新计算路由、搬迁物理位置），ShardingSphere直接在SQL层面拒绝了
这种UPDATE，`cancel()`底层复用的也是同一条`update`语句，所以跟着一起炸。

这不是ShardingSphere集成过程中的一个"坑"，而是分片改造后一个本该改的设计缺口：分片键在这个
玩具系统里就该被当成创建后不可变的字段（真实系统如果真需要支持"改分片键"，得走"删旧行+插新行"
这种跨物理表的搬迁逻辑，属于分布式事务范畴，这次不做）。修法：

- `PolicyMapper.xml`的`update`语句`SET`子句去掉`holder_name`
- `UpdatePolicyRequest`这个DTO去掉`holderName`字段（检查过前端`PolicyListPage.jsx`，压根没有
  编辑功能，只有创建和列表，去掉这个字段不影响任何现有调用方）
- `PolicyService.update()`里去掉`existing.setHolderName(...)`这一行

## 最终验证

- 用6个不同姓名创建保单，直连MySQL确认物理分布到了全部4张表（`policy_0`~`policy_3`都有数据，
  不是全落到一张表上）
- 对每一条新建记录分别用`shardAware=false`和`shardAware=true`查询，返回数据一致，响应头
  正确标出`broadcast:policy_0..policy_3`或`single:policy_N`
- 更新、取消、列表接口修复后验证通过
- 重启`notification-service`、`search-service`，新建一条分片保单后确认Kafka事件正常被
  `notification-service`消费、Elasticsearch索引正常能通过`search-service`查到——P1~P4的链路
  在分片改造之后没有回归

## 最终依赖清单（`policy-service/pom.xml`，均为5.5.3，除非另有标注）

- `shardingsphere-jdbc` —— 核心driver
- `shardingsphere-sharding-core` —— `!SHARDING` YAML tag + 分片规则解析
- `shardingsphere-infra-expr-groovy` —— INLINE算法的Groovy表达式求值
- `shardingsphere-jdbc-dialect-mysql` —— MySQL方言识别/SQL解析改写
- `shardingsphere-infra-data-source-pool-hikari` —— Hikari连接池属性名映射
- `shardingsphere-standalone-mode-core` + `shardingsphere-standalone-mode-repository-memory` —— 单机governance mode
- `commons-lang3:3.20.0`（显式覆盖Spring Boot BOM锁定的旧版本）
- `shardingsphere-authority-core` + `shardingsphere-authority-simple` —— 权限provider
- `shardingsphere-single-core` —— 识别未分片的普通表
