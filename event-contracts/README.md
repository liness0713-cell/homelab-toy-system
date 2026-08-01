# event-contracts

Kafka事件的共享Java契约模块。不是一个可独立运行的服务，是一个纯Java库（`packaging=jar`），
被 `policy-service`（生产者）、`notification-service`（消费者）、以及P4阶段的`search-service`
以Maven依赖的方式引用。

## 为什么会有这个模块

P3阶段`policy-service`和`notification-service`各自维护了一份独立的`PolicyEvent.java`（包名不同）。
这直接导致了一个真实的bug：消费者的`JsonDeserializer`读到生产者写进消息头的类名
（`com.toysystem.policy.event.PolicyEvent`），但消费者自己的类路径下根本没有这个类
（它有的是`com.toysystem.notification.event.PolicyEvent`），于是报错
`not in the trusted packages`——本质上是"两份类，靠人工保持字段一致"这件事本身就不可靠。

这个模块把契约类收敛成唯一一份，生产者和消费者引用的是**同一个class**，从根上消灭了
"两份定义容易失步"的问题，而不是靠配置（比如`spring.json.use.type.headers=false`）绕过去。

## 目录内容

```
src/main/java/com/toysystem/event/
├── PolicyEvent.java       # policy-events topic 消息体
└── PolicyEventType.java   # POLICY_CREATED / POLICY_UPDATED / POLICY_CANCELLED
```

特意不依赖任何具体业务服务的实体类（比如`policy-service`的`Policy`）——这个模块只能被别的
服务依赖，不能反过来依赖别的服务，否则就会有循环依赖的风险。`policy-service`自己有一个
`PolicyEventFactory`负责把内部的`Policy`实体转换成这里定义的`PolicyEvent`。

## 怎么构建

这是Maven多模块项目（根目录`pom.xml`）的一个子模块，不单独构建/运行。改动这个模块后，
需要在**仓库根目录**跑一次 `mvn install`（把它安装到本地`~/.m2`仓库），`policy-service`/
`notification-service`才能拿到最新版本：

```bash
cd /path/to/homelab-toy-system
mvn -pl event-contracts -am install
```

（`gateway-service`不处理`PolicyEvent`对象，只做HTTP层转发，所以不依赖这个模块，仍然是独立的
Maven项目；`frontend`是Node生态，同样不受影响。）
