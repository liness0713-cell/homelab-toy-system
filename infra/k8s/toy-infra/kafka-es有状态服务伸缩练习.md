## 1
先把这几个配置文件的关系理清楚，再给扩容计划——毕竟"看不懂现在跑的是什么"和"怎么扩容"其实是同一个问题的两半。

配置文件到底在做什么

StorageClass local-storage：provisioner: kubernetes.io/no-provisioner意味着k8s不会帮你自动创建磁盘——这是"本地存储"的宿命，本地磁盘只能手动划出来、手动告诉k8s"这块盘在哪个node上"。volumeBindingMode: WaitForFirstConsumer是关键：PVC不会立刻绑定PV，而是等到真正有Pod要用它、需要调度时才决定绑定哪个node上的PV——这保证了"Pod的node"和"数据所在node"永远一致，不会出现Pod调度到A、但数据在B这种荒谬情况。

es-pv-0 / kafka-pv-0：这是你手动创建的、真正指向物理磁盘的PV，local.path: /data/es-0说明数据实际存在ziqiao-asm100这台机器的这个路径下。nodeAffinity部分是硬约束：这块PV只能被调度到ziqiao-asm100上的Pod使用——这跟我们之前练习手册第3节讲的podAntiAffinity用的是同一套nodeAffinity机制，只是这里用在PV上而不是Pod上。

Elasticsearch CR的volumeClaimTemplates：ECK（Elastic的operator）会根据这个模板自动生成PVC，PVC靠selector.matchLabels: app: es去匹配上面那块手动建的PV——这就是"自动生成的PVC"和"手动建的PV"对上号的机制。nodeSets.count: 1表示现在只有1个ES节点。

KafkaNodePool：Strimzi（Kafka的operator）的概念，定义一组Kafka Pod共享的角色和存储配置。roles: [controller, broker]叫dual-role，意思是这个Pod既当KRaft控制平面（取代老版本的Zookeeper）又当实际存数据/服务请求的broker——这是轻量部署常见做法，replicas: 1就是只起1个这样的Pod。

Kafka CR里的几个replication.factor都设成1：这是因为现在只有1个broker，逻辑上不可能有副本（副本数不可能超过broker数）。这几个数字目前是"将就能跑起来的下限"，不是"最优配置"——这也是接下来扩容要改的地方。

现状小结

一句话：现在Kafka和ES都是单点、单副本、数据全压在ziqiao-asm100一台机器上——虽然是3节点集群，但这两个有状态服务完全没利用到node2/node3，跟"3节点集群"这个招牌不太匹配，这正是接下来练习要解决的。


## 2
先point一个关键冲突再往下走：你之前给k3s-node3打了node-type=low-power:NoSchedule这个taint，目的就是"Kafka/ES暂时不要用它"——如果现在直接把Kafka/ES扩到3副本，第3个Pod会因为没有toleration而卡在Pending（跟我们练习手册第4节演示的效果一模一样）。

所以这次有状态扩容,现实的目标是扩到2副本（ziqiao-asm100 + k3s-node2），暂时不动node3——这跟你之前的决策是一致的,而且2副本也足够练习"多副本同步"、"ISR"、"leader选举"这些核心概念,不需要凑到3。

第一步：在node2上准备本地存储目录

先ssh进node2手动建目录（本地存储需要手动准备,这是local-storage这个StorageClass天生的限制）：

bash
ssh ubuntu@192.168.40.32
sudo mkdir -p /data/es-1 /data/kafka-1
sudo chmod 777 /data/es-1 /data/kafka-1   # 简化权限处理，玩具环境可以这样，生产环境要按实际用户id设置
exit
第二步：在ziqiao-asm100上新增两个PV（指向node2）
yaml
apiVersion: v1
kind: PersistentVolume
metadata:
  name: es-pv-1
  labels:
    app: es
spec:
  capacity:
    storage: 16Gi
  volumeMode: Filesystem
  accessModes:
    - ReadWriteOnce
  persistentVolumeReclaimPolicy: Retain
  storageClassName: local-storage
  local:
    path: /data/es-1
  nodeAffinity:
    required:
      nodeSelectorTerms:
      - matchExpressions:
        - key: kubernetes.io/hostname
          operator: In
          values:
          - k3s-node2
---
apiVersion: v1
kind: PersistentVolume
metadata:
  name: kafka-pv-1
  labels:
    app: kafka
spec:
  capacity:
    storage: 8Gi
  volumeMode: Filesystem
  accessModes:
    - ReadWriteOnce
  persistentVolumeReclaimPolicy: Retain
  storageClassName: local-storage
  local:
    path: /data/kafka-1
  nodeAffinity:
    required:
      nodeSelectorTerms:
      - matchExpressions:
        - key: kubernetes.io/hostname
          operator: In
          values:
          - k3s-node2

命令说明：跟原来es-pv-0/kafka-pv-0一模一样的结构，唯二区别是name（加-1后缀区分）和nodeAffinity（指向k3s-node2而不是ziqiao-asm100）。app: es/app: kafka这两个label保持不变——因为ECK/Strimzi的volumeClaimTemplates.selector就是靠这两个label去"认领"可用的PV，label一样、nodeAffinity不同，正是让"同一个应用的多个副本各自绑定到不同node上的盘"这件事成立的关键。

bash
kubectl apply -f es-pv-1.yaml
kubectl apply -f kafka-pv-1.yaml
kubectl get pv

观察点：确认两个新PV的STATUS是Available（还没被任何PVC认领）。

第三步：扩容KafkaNodePool，并调整副本相关配置

修改KafkaNodePool：

yaml
apiVersion: kafka.strimzi.io/v1
kind: KafkaNodePool
metadata:
  name: dual-role
  labels:
    strimzi.io/cluster: my-kafka
spec:
  replicas: 2        # 从1改成2
  roles:
    - controller
    - broker
  storage:
    type: persistent-claim
    size: 8Gi
    class: local-storage
    selector:
      app: kafka

同时修改Kafka CR里的副本因子（原来=1是因为只有1个broker,现在有2个broker,可以真正体现"多副本"）：

yaml
    config:
      offsets.topic.replication.factor: 2
      transaction.state.log.replication.factor: 2
      transaction.state.log.min.isr: 1
      default.replication.factor: 2
      min.insync.replicas: 1

命令说明：

default.replication.factor: 2——以后新建的topic默认会在2个broker上各存一份完整数据,而不是只存1份
min.insync.replicas: 1——这个故意保持1（不是2），代表"哪怕2个副本里只有1个存活确认写入,也允许继续写",如果设成2,当有1个broker挂掉时整个集群会直接拒绝写入,对只有2 broker的玩具集群来说太脆弱,后面等真扩到3+broker再考虑提到2
bash
kubectl apply -f kafkanodepool.yaml
kubectl apply -f kafka.yaml
kubectl get pods -n toy-infra -o wide -w

观察点：应该看到第2个Kafka broker Pod被调度到k3s-node2上（因为PVC通过label匹配到了kafka-pv-1，而这个PV的nodeAffinity锁定了node2）。

第四步：扩容Elasticsearch
yaml
  nodeSets:
    - name: default
      count: 2        # 从1改成2
bash
kubectl apply -f elasticsearch.yaml
kubectl get pods -n toy-infra -o wide -w

观察点：新的ES Pod应该被调度到k3s-node2（同理，绑定es-pv-1）。

第五步：验证真正的多副本效果（这才是这次练习的核心）

Kafka这边——进broker容器看topic的副本分布：

bash
kubectl exec -it my-kafka-dual-role-0 -n toy-infra -- bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe

观察点：找一个已有topic（比如你的policy-events），看Replicas和Isr这两列——理想情况下每个partition应该有2个replica，分布在不同broker id上，Isr（in-sync replicas）也应该是2个,说明数据真正同步了,不是摆设。

验证故障切换（杀掉一个broker，观察leader重新选举）：

bash
kubectl delete pod my-kafka-dual-role-0 -n toy-infra
kubectl exec -it my-kafka-dual-role-1 -n toy-infra -- bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe

观察点：被删的那个Pod重建期间，Leader列应该会切到另一个还存活的broker id上——这是2副本最直接的价值体现：单点故障不再意味着服务中断。

ES这边——看集群健康状态：

bash
kubectl exec -it my-es-es-default-0 -n toy-infra -- curl -s -u elastic:$ELASTIC_PASSWORD https://localhost:9200/_cluster/health?pretty -k

观察点：status字段——单节点时永远是yellow（因为副本分片没地方放,ES知道"有2份数据比较安全"但只有1个节点根本放不下第2份）；现在有2个节点了,如果索引的number_of_replicas设置>=1,应该能看到status变成green。

先跑第一、二步（准备PV），把kubectl get pv的结果发我确认一下再继续Kafka/ES扩容这部分。



## 3
好，去掉taint之后node3也能用了，那正好把这次练习目标提到3副本——三个node各一个,能真正体验到Kafka/ES设计上更贴近生产的"多数派"概念（比如Kafka的ISR、ES的分片分布都是在3节点上才有更丰富的语义）。

第一步：移除node3的taint
bash
kubectl taint node k3s-node3 node-type=low-power:NoSchedule-

（记得末尾的-号,这是删除taint的语法）

bash
kubectl describe node k3s-node3 | grep -A2 Taints

观察点：确认输出是Taints: <none>,taint已清除。

第二步：在node2和node3上分别准备本地存储目录
bash
ssh ubuntu@192.168.40.32
sudo mkdir -p /data/es-1 /data/kafka-1
sudo chmod 777 /data/es-1 /data/kafka-1
exit

ssh ubuntu@192.168.40.14
sudo mkdir -p /data/es-2 /data/kafka-2
sudo chmod 777 /data/es-2 /data/kafka-2
exit
第三步：新增4个PV（node2和node3各2个，分别给ES和Kafka）
yaml
apiVersion: v1
kind: PersistentVolume
metadata:
  name: es-pv-1
  labels:
    app: es
spec:
  capacity:
    storage: 16Gi
  volumeMode: Filesystem
  accessModes:
    - ReadWriteOnce
  persistentVolumeReclaimPolicy: Retain
  storageClassName: local-storage
  local:
    path: /data/es-1
  nodeAffinity:
    required:
      nodeSelectorTerms:
      - matchExpressions:
        - key: kubernetes.io/hostname
          operator: In
          values:
          - k3s-node2
---
apiVersion: v1
kind: PersistentVolume
metadata:
  name: es-pv-2
  labels:
    app: es
spec:
  capacity:
    storage: 16Gi
  volumeMode: Filesystem
  accessModes:
    - ReadWriteOnce
  persistentVolumeReclaimPolicy: Retain
  storageClassName: local-storage
  local:
    path: /data/es-2
  nodeAffinity:
    required:
      nodeSelectorTerms:
      - matchExpressions:
        - key: kubernetes.io/hostname
          operator: In
          values:
          - k3s-node3
---
apiVersion: v1
kind: PersistentVolume
metadata:
  name: kafka-pv-1
  labels:
    app: kafka
spec:
  capacity:
    storage: 8Gi
  volumeMode: Filesystem
  accessModes:
    - ReadWriteOnce
  persistentVolumeReclaimPolicy: Retain
  storageClassName: local-storage
  local:
    path: /data/kafka-1
  nodeAffinity:
    required:
      nodeSelectorTerms:
      - matchExpressions:
        - key: kubernetes.io/hostname
          operator: In
          values:
          - k3s-node2
---
apiVersion: v1
kind: PersistentVolume
metadata:
  name: kafka-pv-2
  labels:
    app: kafka
spec:
  capacity:
    storage: 8Gi
  volumeMode: Filesystem
  accessModes:
    - ReadWriteOnce
  persistentVolumeReclaimPolicy: Retain
  storageClassName: local-storage
  local:
    path: /data/kafka-2
  nodeAffinity:
    required:
      nodeSelectorTerms:
      - matchExpressions:
        - key: kubernetes.io/hostname
          operator: In
          values:
          - k3s-node3
bash
kubectl apply -f pv-node2.yaml
kubectl apply -f pv-node3.yaml
kubectl get pv

观察点：现在应该一共有6个PV（原有的es-pv-0/kafka-pv-0各1个 + 新增的4个），全部STATUS为Available（node0上的两个除外，它们已经被现有Pod占用，会显示Bound）。

第四步：Kafka扩到3副本
yaml
apiVersion: kafka.strimzi.io/v1
kind: KafkaNodePool
metadata:
  name: dual-role
  labels:
    strimzi.io/cluster: my-kafka
spec:
  replicas: 3        # 从1改成3
  roles:
    - controller
    - broker
  storage:
    type: persistent-claim
    size: 8Gi
    class: local-storage
    selector:
      app: kafka

Kafka CR的副本因子也一并调整——这次因为凑齐了3个broker,可以用生产环境更常见的配置：

yaml
    config:
      offsets.topic.replication.factor: 3
      transaction.state.log.replication.factor: 3
      transaction.state.log.min.isr: 2
      default.replication.factor: 3
      min.insync.replicas: 2

命令说明：min.insync.replicas: 2这次可以放心设为2了（而不是2副本时保守的1）——3副本情况下,即使1个broker挂掉,剩下2个还能满足"至少2个确认写入"的要求,集群依然能正常写入,这正是quorum（多数派）容错的体现,允许1台故障还能撑住。

bash
kubectl apply -f /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/kafka/kafka-nodepool.yaml
kubectl apply -f /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/kafka/kafka-cluster.yaml
kubectl get pods -n toy-infra -o wide -w

观察点：应该看到3个broker Pod分别落在ziqiao-asm100、k3s-node2、k3s-node3上。

第五步：ES扩到3副本
yaml
  nodeSets:
    - name: default
      count: 3        # 从1改成3
bash
kubectl apply -f /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/es/es-cluster.yaml
kubectl get pods -n toy-infra -o wide -w
第六步：验证 quorum 容错（这是3副本相比2副本最关键的新体验）

Kafka这边——杀掉一个broker，看集群是否还能正常写入：

bash
kubectl delete pod my-kafka-dual-role-0 -n toy-infra
kubectl exec -it my-kafka-dual-role-1 -n toy-infra -- bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe

观察点：Isr列应该从3变成2，但因为min.insync.replicas: 2，剩下2个副本依然满足最低要求，生产/消费应该完全不受影响——这是2副本集群做不到的（2副本时挂1个,min.insync.replicas最多设1,容错空间几乎为零）。

ES这边——同样杀掉一个Pod看集群状态：

bash
kubectl delete pod my-es-es-default-0 -n toy-infra
kubectl exec -it my-es-es-default-1 -n toy-infra -- curl -s -u elastic:$ELASTIC_PASSWORD https://localhost:9200/_cluster/health?pretty -k

观察点：status短暂可能变yellow（副本分片重新分配中），但集群本身不会宕机、也不会拒绝读写——3节点相比2节点最大的差异就在这里，2节点时任何一个掉线都可能直接影响可用性判断，3节点才有真正的"多数派还在"的安全边际。

先跑第一步移除taint，把describe node确认结果发我再继续。