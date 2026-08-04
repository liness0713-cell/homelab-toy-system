# notification-service

`policy-events` 的消费者A。纯消费者，没有数据库。当前职责：
- 订阅 `policy-events` topic（消费者组 `notification-service`）
- 收到事件后模拟"发通知"——只是打一条日志，不真的发邮件/短信
- 消费失败时有完整的容错：反序列化异常/业务异常都会重试，重试用尽后进死信topic，不会
  卡死整条流水线（详见下面"死信Topic"一节）

## 技术栈

Spring Boot 3.3 + spring-kafka，Java 21。是Maven多模块项目的子模块，依赖共享契约模块
`../event-contracts/`（`PolicyEvent`类定义在那边，这个服务不再自己维护一份）。

## 依赖

- Kafka 已经在跑（默认 `localhost:9092`，`docker compose -f infra/docker-compose.dev.yml up -d kafka`）
- `policy-service` 已经在跑并且发过事件（这个服务本身不产生数据，纯被动消费）
- 第一次构建前，先在**仓库根目录**跑一次 `mvn install`（把 `event-contracts` 装进本地仓库）

## 本地单独运行

```bash
source ~/.sdkman/bin/sdkman-init.sh
cd notification-service
mvn spring-boot:run
```

默认配置（可用环境变量覆盖，见 `application.yml`）：

| 环境变量 | 默认值 |
|---|---|
| `SERVER_PORT` | `8082` |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |

`auto-offset-reset: earliest`：第一次启动时消费者组还没有提交过offset，会从topic最早的消息开始追，
方便演示"服务起晚了也能追上之前错过的事件"。

## 怎么看到效果

创建/更新/取消一个保单（通过 `policy-service` 或 `gateway-service`），观察这个服务的控制台日志，
应该能看到类似：

```
[通知] eventId=... holder=山田太郎 -> 您的保单 POL-XXXX 已创建成功，产品：TENGAN，保费：1200.50
```

## 暴露的端口

服务端口：`8082`，只有 `/actuator/health`，没有对外业务API。

## 死信Topic（Dead Letter Topic）容错

P3实测踩过"一条毒药消息（poison pill）卡死consumer"的坑（见下一节复盘）。修订后的完整方案，
两层配置都在这个服务里：

1. **反序列化层**（`application.yml`）：`value-deserializer` 用 `ErrorHandlingDeserializer`
   包一层真正干活的 `JsonDeserializer`——反序列化失败时异常被包进record交给容器的错误处理器，
   而不是直接从Kafka客户端里炸出来。
2. **业务处理层 + 死信topic**（`config/KafkaErrorHandlingConfig.java`）：`DefaultErrorHandler`
   配 `FixedBackOff(1000ms, 3次)` + `DeadLetterPublishingRecoverer`——反序列化失败或
   `@KafkaListener`方法抛异常，都会重试3次（每次间隔1秒），重试用尽后原始消息被转发到
   `policy-events.notification-service.DLT`，consumer的offset正常往前走，不会卡住后面的正常消息。
3. **死信topic显式声明**（`config/KafkaTopicConfig.java`）：`policy-events.notification-service.DLT`，1个分区
   （死信量预期很小，不用跟原topic的3分区对齐）。

验证过两条路径都生效：
- 直接往 `policy-events` 塞一条非JSON的脏字节（`kafka-console-producer.sh`），立刻被识别为
  不可重试的反序列化异常，直接进DLT，没有浪费3次重试
- 让 `@KafkaListener` 方法对某个测试消息主动抛异常，观察到3次重试（日志里能看到1秒间隔的
  "Seeking to offset N"），重试用尽后同样进了DLT，消费者组的offset也确认推进、没有卡住

死信topic目前只做到"消息不再堵塞主流程"这一步，没有做人工重放/告警通知（更进阶的运维能力，
留到以后想深入时再加）。

**死信topic名字带上了消费者组名**（`policy-events.notification-service.DLT`，而不是默认的
`policy-events.DLT`）：P4阶段 `search-service` 也订阅了同一个 `policy-events` topic、用的是
自己独立的消费者组。如果两边失败的消息都堆到同一个默认死信topic里，排查问题时没法一眼看出
这条死信消息到底是谁处理失败的——所以每个消费者组的死信topic都带上自己的名字，`search-service`
那边也是同一个约定（见其README）。

## 已知的坑（写给自己看的，别踩第二次）

### 坑1：两份独立的PolicyEvent类导致的反序列化失败（已用共享模块根治）

最初 `policy-service` 和 `notification-service` 各自维护一份独立的 `PolicyEvent.java`（包名
不同）。Spring Kafka的 `JsonSerializer`（生产者侧）默认会把**自己的类名**写进消息的
`__TypeId__` 头，消费端的 `JsonDeserializer` 默认优先信这个头——但消费者的类路径下根本没有
生产者那个包名下的类，直接报"not in the trusted packages"。

最初的修复是配置层面的绕过：consumer这边设 `spring.json.use.type.headers=false`，强制忽略
生产端传来的类名，永远用自己配置的`default.type`。这能解决当时的报错，但"两份定义容易失步"
的风险还在——本质问题没解决。

**根治方案**：新建共享模块 `event-contracts`，生产者和消费者都依赖同一个`PolicyEvent`类
（同一个FQCN）。现在类完全一致，`__TypeId__`头天然就能正确解析，不再需要
`use.type.headers=false`这个绕过配置（见当前的`application.yml`，`trusted.packages`
直接指向`com.toysystem.event`）。

### 坑2：反序列化异常卡死consumer（已用ErrorHandlingDeserializer + DLT解决）

反序列化异常是在Kafka客户端内部抛出的，`DefaultErrorHandler`处理不了这种异常，会报
`This error handler cannot process 'SerializationException's directly`，然后**卡在同一条
消息上死循环重试，永远不往后消费**。解法见上面"死信Topic容错"一节。

### 坑3（小坑）：DLT分区数和原topic对不上，Recoverer报无害的WARN

`DeadLetterPublishingRecoverer`默认想把死信消息发到"跟原消息相同分区号"的DLT分区。
`policy-events`有3个分区，但`policy-events.notification-service.DLT`只开了1个分区（死信量预期很小），
分区号2/1对1分区的DLT来说不存在，会打一条 `Destination resolver returned non-existent
partition` 的WARN（消息其实还是发成功了，Kafka自己兜底选了个分区）。修法：给
`DeadLetterPublishingRecoverer`显式传一个目标解析函数，分区号写`-1`，明确告诉它"不用
对齐分区号，交给生产者的分区器决定"，消除这条WARN（见`KafkaErrorHandlingConfig.java`）。
