## Step 1：确认当前node名字
kubectl get nodes -o wide

## Step 2：在宿主机上建数据目录
sudo mkdir -p /data/kafka-0
sudo chmod 777 /data/kafka-0
（先用777图省事，跑通后可以收紧权限，不是这一步的重点）

## Step 3：创建一个新的StorageClass（专门给local PV用）
kubectl apply -f /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/kafka/local-storage-sc.yaml

## Step 4：手动创建PV（把node名字换成Step 1看到的）
kubectl apply -f /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/kafka/kafka-pv-0.yaml
kubectl get pv --show-labels


Bitnami从2025年8月28日起收紧了免费镜像仓库策略，很多bitnami/xxx镜像被移到了需要订阅的"Bitnami Secure Images"仓库，公开的docker.io/bitnami/kafka可能已经被限流/下架/需要认证才能拉。建议现在就切换到Strimzi，而不是在Bitnami这条路上继续打补丁。


可以开始装Strimzi了。

## Step 1：加Strimzi的Helm仓库
helm repo add strimzi https://strimzi.io/charts/
helm repo update

## Step 2：装Strimzi Operator本身

这一步只装"控制大脑"（一个常驻Pod，负责watch后续所有Kafka类型的资源），还不会创建任何Kafka broker：

bash
helm install strimzi-kafka-operator strimzi/strimzi-kafka-operator \
  -n toy-infra

## Step 3：确认Operator跑起来了
bash
kubectl get pods -n toy-infra

应该会看到一个类似strimzi-cluster-operator-xxxxx的Pod，STATUS是Running。这个Pod本身不消耗Kafka的存储，它只是"控制平面"，跑起来后会自动注册一批新的CRD类型(Kafka、KafkaNodePool、KafkaTopic等)。可以顺手确认一下这些CRD确实注册成功了：

bash
kubectl get crd | grep kafka

应该能看到kafkas.kafka.strimzi.io、kafkanodepools.kafka.strimzi.io这些条目。

先跑这3步，把输出贴给我确认Operator健康起来了，下一步我们再写Kafka和KafkaNodePool这两份YAML，把之前那块kafka-pv-0真正接上去。


Operator跑起来了(那次重启大概率是7小时前系统/容器运行时的正常波动，不用担心，READY 1/1、当前状态Running就是健康的)。现在写KafkaNodePool和Kafka这两份YAML，把之前那块kafka-pv-0接上去。

先搞懂这两个CRD各自管什么，再看yaml
KafkaNodePool——定义"一组具体的broker/controller节点长什么样"：几个节点、用什么角色(broker/controller/两者兼任)、存储配置(storageClass、大小)。这是"节点池"的概念，一个Kafka集群可以有多个NodePool(比如以后你想把controller和broker分开)。
Kafka——定义"整个Kafka集群"的顶层配置：监听端口、副本数策略、集群整体设置，它会引用上面的NodePool。

## broker/controller  有什么区别？

一句话区分

Broker负责"干活"——存数据、收生产者写入、给消费者读数据；Controller负责"管家"——决定集群的元数据（有哪些topic、哪些partition在哪个broker上、谁是leader），协调整个集群的状态一致性。

类比：一家餐厅
Broker = 服务员+仓库——真正端菜（处理生产者/消费者的读写请求）、把菜存起来（把消息数据落到磁盘）
Controller = 店长——不亲自端菜，但决定"哪张桌子该谁负责""菜单上有什么"，一旦有服务员请假（broker宕机），店长负责重新分配工作
为什么以前需要Zookeeper，现在不需要了

老版本Kafka（KRaft之前）没有"controller"这个角色概念内置在Kafka里，"店长"的工作是外包给Zookeeper这个独立系统做的——Zookeeper专门维护"谁是谁的leader""集群元数据"这些信息，Kafka broker本身只管干活。这套架构的问题是：你得额外运维一整套Zookeeper集群，多一套系统就多一层复杂度和故障点。

KRaft模式（Kafka Raft，Kafka 3.3+成熟、4.0起完全默认）把"店长"这个角色收编进Kafka自己内部，不再依赖Zookeeper——用Kafka自己实现的Raft共识算法，让一小撮节点专门当controller，自己选举、自己维护元数据一致性。这也是为什么你之前"官方Apache Kafka镜像天然支持KRaft，不用装Zookeeper"这件事成立的原因。

为什么我们的yaml里写roles: [controller, broker]（两个角色叠一起）

正常生产环境里，controller和broker是分开部署的（各自独立的一批节点，各司其职，互不干扰、方便分别扩容）——这也是为什么我们Bitnami那次报错日志里看到的是kafka-controller-0/1/2这种专职controller节点(那次chart默认给了3个专职controller，跟broker是分开的)。

但在你现在单节点homelab场景下，物理上就一台机器，没必要（也没条件）把controller和broker拆成两组Pod抢同一台机器的资源——所以我们的KafkaNodePool让同一批节点(现在就1个)身兼两职，既当controller又当broker，这是Strimzi官方文档里专门给"开发/测试/小规模场景"推荐的配置方式，叫dual-role（跟我们yaml里metadata.name: dual-role这个命名对上了）。

以后加了第二台node，会怎么演变

到时候更贴近生产实践的做法是拆成两个独立的KafkaNodePool：一个roles: [controller]专职当店长(通常controller只需要少量节点，甚至1个在小集群里就够)，另一个roles: [broker]专职端菜干活，两者可以独立扩容——这也是我们之前聊过的"local PV + nodeAffinity"精细化控制会真正派上用场的地方：给controller节点和broker节点分别打不同label的PV，避免混淆。


## 应用顺序：先NodePool，再Kafka（有依赖关系）
bash
kubectl apply -f /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/kafka/kafka-nodepool.yaml -n toy-infra
kubectl apply -f /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/kafka/kafka-cluster.yaml -n toy-infra
之后确认状态
bash
kubectl get pods -n toy-infra -w

（-w是watch模式，能看着Pod从Pending → ContainerCreating → Running的过程，看到Running且READY变绿之后按Ctrl+C退出watch）

跑完把结果贴给我，我们确认存储是不是真的绑定到了kafka-pv-0。