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


## 4 kafka改副本数量没起作用
重启当然可以试，如果真是文件同步或者挂载缓存的问题，重启确实有可能刷新掉这个僵住的缓存。但重启相对"重"，而且不一定能命中问题根源——这里有个更直接的办法：跳过文件比对这一步，直接对集群里的对象打patch，不依赖本地文件内容是否同步到位，能立刻验证问题出在哪。

直接patch，绕开文件同步问题
bash
kubectl patch kafkanodepool dual-role -n toy-infra --type merge -p '{"spec":{"replicas":3}}'

命令说明：这条命令直接告诉API server"把spec.replicas改成3"，完全不涉及本地文件、不做"跟旧文件比对"这一步——如果这条命令执行后立刻显示kafkanodepool.kafka.strimzi.io/dual-role patched（而不是unchanged），就证明了问题100%出在文件读取/同步这一环，跟k8s本身、跟你的yaml内容都无关。

验证：

bash
kubectl get kafkanodepool dual-role -n toy-infra -o jsonpath='{.spec.replicas}{"\n"}{.status.observedGeneration}{"\n"}'

如果replicas这次变成3了,就不用重启电脑了——问题已经绕过去,后面Kafka Pod应该马上开始创建：

bash
kubectl get pods -n toy-infra -o wide -w
如果连这条patch命令也显示没变化

那说明问题不在文件层面，而是别的地方（比如kubectl本身连到了错的context/集群，或者有alias/缓存干扰）——这种情况下重启确实是个合理的排查手段，先确认一下当前连的是哪个集群，避免重启后还是同样的问题：

bash
kubectl config current-context

把这条也发我一下，如果确实决定重启，重启后先重跑一遍这条patch命令,而不是回去改文件再apply,这样能最快确认问题是否真的解决了。

## 5 重建kafka
中文

现在的状态比较严峻，得先说清楚这意味着什么：KRaft需要"多数派健在"才能正常工作——3个controller里必须至少2个存活且状态一致，选举才能收敛。现在dual-role-0和dual-role-1都在CrashLoopBackOff，只有dual-role-2（node3上那个）活着——这已经跌破了quorum的最低门槛（3中最多容忍1个故障，现在挂了2个），即便dual-role-2容器状态显示Running，它大概率也无法完成任何需要"多数确认"的操作（比如选主、提交元数据变更），整个Kafka集群实质上已经不可用。

这跟node2之前的连续崩溃/重启脱不开关系——最可能的过程是：node2先崩了一次，dual-role-1带着不完整/过时的KRaft状态重新加入集群参与选举，制造出你之前看到的那种"epoch内leader状态矛盾"，这个矛盾一旦产生，会像多米诺骨牌一样，让健康的dual-role-0在处理这条被污染的选举消息时也触发同样的fatal fault挂掉——这解释了为什么两个broker几乎同时进入崩溃循环。

先确认诊断,再决定要不要"推倒重来"

看两个崩溃Pod的上一次日志，确认是不是同一个IllegalStateException：

bash
kubectl logs my-kafka-dual-role-0 -n toy-infra --previous --tail=50
kubectl logs my-kafka-dual-role-1 -n toy-infra --previous --tail=50

如果两边报的都是这类KRaft内部状态矛盾（大概率是），这种损坏在实践中通常无法"温和修复"——KRaft的日志一旦出现这种不变量被打破的情况，官方目前没有提供在线修复工具，唯一现实的路径是重置元数据存储,让这几个broker重新组建一个干净的KRaft quorum。

现实的选择：这是练习环境，直接重建更划算

继续深挖修复单个broker的KRaft状态，投入产出比很低（Kafka社区对这类问题的建议基本也是"重新格式化storage"），而且这本来就是我们在做的伸缩练习，重建一次本身也是很好的"故障恢复演练"素材。

bash
# 1. 先删除Kafka和KafkaNodePool这两个CR（Strimzi会自动清理对应的Pod/PVC绑定）
kubectl delete kafka my-kafka -n toy-infra
kubectl delete kafkanodepool dual-role -n toy-infra

# 2. 确认PVC和Pod都已清空
kubectl get pods -n toy-infra -o wide
kubectl get pvc -n toy-infra

# 3. 清理三台机器上/data/kafka-*目录里的旧KRaft数据（避免新集群又读到脏数据）
ssh ubuntu@192.168.40.23 "sudo rm -rf /data/kafka-0/*"    # 如果ziqiao-asm100本身就是这个IP,直接本机执行也可以
ssh ubuntu@192.168.40.32 "sudo rm -rf /data/kafka-1/*"
ssh ubuntu@192.168.40.14 "sudo rm -rf /data/kafka-2/*"

# 4. PV本身不用删（Retain策略，plus路径还在），但PV的status可能停留在Released,需要清一下claimRef让它变回Available
kubectl patch pv kafka-pv-0 -p '{"spec":{"claimRef": null}}'
kubectl patch pv kafka-pv-1 -p '{"spec":{"claimRef": null}}'
kubectl patch pv kafka-pv-2 -p '{"spec":{"claimRef": null}}'

# 5. 重新apply KafkaNodePool和Kafka这两个CR（用你已经改好的、带replicas:3和新replication factor的版本）
kubectl apply -f kafka-nodepool.yaml
kubectl apply -f kafka.yaml

观察点：这次重建后topic数据会丢失（因为KRaft存储的是元数据+partition日志本身,清空即清空,这是这条路径必须承受的代价）——如果policy-events这类topic里有你在意的测试数据,提前确认一下能不能接受清空;如果只是练习用的测试数据,直接清空重来是最干净的选择。

先跑上面--previous日志确认命令,把结果发我确认一下是不是真的是同一个IllegalStateException,再决定要不要走重建这条路。

日本語(ふりがな付き)

今(いま)の状態(じょうたい)はかなり深刻(しんこく)です。まずこれが何(なに)を意味(いみ)するかはっきりさせましょう：KRaftは「多数派(たすうは)が健在(けんざい)」であって初(はじ)めて正常(せいじょう)に機能(きのう)します——3(さん)つのcontrollerのうち少(すく)なくとも2(に)つが生(い)きていて状態(じょうたい)が一致(いっち)していなければ、選挙(せんきょ)は収束(しゅうそく)しません。今(いま)dual-role-0とdual-role-1は両方(りょうほう)ともCrashLoopBackOffで、生(い)きているのはdual-role-2(node3上(じょう)のもの)だけです——これはすでにquorumの最低限界(さいていげんかい)を下回(したまわ)っています(3(さん)つのうち1(ひと)つまでの故障(こしょう)しか許容(きょよう)できないのに、今(いま)2(に)つ落(お)ちています)。dual-role-2のコンテナがRunningと表示(ひょうじ)されていても、「多数決(たすうけつ)の確認(かくにん)」が必要(ひつよう)な操作(そうさ)(リーダー選出(せんしゅつ)、メタデータ変更(へんこう)のコミットなど)はほぼ完了(かんりょう)できず、Kafkaクラスター全体(ぜんたい)が実質的(じっしつてき)に機能(きのう)していない状態(じょうたい)です。

これはnode2の以前(いぜん)の連続(れんぞく)クラッシュ/再起動(さいきどう)と無関係(むかんけい)ではありません——おそらくの経緯(けいい)としては：node2が一度(いちど)クラッシュし、dual-role-1が不完全(ふかんぜん)/古(ふる)いKRaft状態(じょうたい)のままクラスターに再参加(さいさんか)して選挙(せんきょ)に加(くわ)わり、以前(いぜん)見(み)た「epoch内(ない)でleader状態(じょうたい)が矛盾(むじゅん)する」という状況(じょうきょう)を作(つく)り出(だ)しました。この矛盾(むじゅん)が一度(いちど)発生(はっせい)すると、ドミノ倒(だお)しのように、健全(けんぜん)だったdual-role-0もこの汚染(おせん)された選挙(せんきょ)メッセージを処理(しょり)する際(さい)に同(おな)じfatal faultをトリガーして落(お)ちてしまいます——これで二(ふた)つのbrokerがほぼ同時(どうじ)にクラッシュループに入(はい)った理由(りゆう)が説明(せつめい)できます。

まず診断(しんだん)を確認(かくにん)し、それから「作(つく)り直(なお)す」かどうか決(き)めましょう

二(ふた)つのクラッシュしているPodの前回(ぜんかい)のログを見(み)て、同(おな)じIllegalStateExceptionかどうか確認(かくにん)します：

bash
kubectl logs my-kafka-dual-role-0 -n toy-infra --previous --tail=50
kubectl logs my-kafka-dual-role-1 -n toy-infra --previous --tail=50

両方(りょうほう)ともこの種(しゅ)のKRaft内部状態(ないぶじょうたい)の矛盾(むじゅん)であれば(おそらくそうです)、この種(しゅ)の破損(はそん)は実務上(じつむじょう)「穏(おだ)やかに修復(しゅうふく)する」ことは通常(つうじょう)できません——KRaftのログでこの種(しゅ)の不変条件(ふへんじょうけん)が破(やぶ)られると、公式(こうしき)には現時点(げんじてん)でオンライン修復(しゅうふく)ツールは提供(ていきょう)されておらず、唯一(ゆいいつ)現実的(げんじつてき)な道(みち)はメタデータストレージをリセットして、これらのbrokerで新(あたら)しくクリーンなKRaft quorumを組(く)み直(なお)すことです。

現実的(げんじつてき)な選択(せんたく)：練習環境(れんしゅうかんきょう)なので、直接(ちょくせつ)作(つく)り直(なお)す方(ほう)が割(わ)りに合(あ)う

単一(たんいつ)のbrokerのKRaft状態(じょうたい)を深(ふか)く追(お)って修復(しゅうふく)し続(つづ)けるのは投資対効果(とうしたいこうか)が低(ひく)く(Kafkaコミュニティもこの種(しゅ)の問題(もんだい)には基本的(きほんてき)に「storageを再(さい)フォーマットする」ことを勧(すす)めています)、しかも**これはもともと私(わたし)たちが行(おこな)っていた伸縮(しんしゅく)練習(れんしゅう)**なので、一度(いちど)作(つく)り直(なお)すこと自体(じたい)も良(よ)い「障害復旧(しょうがいふっきゅう)演習(えんしゅう)」の材料(ざいりょう)になります。

bash
# 1. まずKafkaとKafkaNodePoolという二つのCRを削除する(StrimziがPod/PVCの紐付けを自動的に整理してくれます)
kubectl delete kafka my-kafka -n toy-infra
kubectl delete kafkanodepool dual-role -n toy-infra

# 2. PVCとPodがきれいに空になったか確認する
kubectl get pods -n toy-infra -o wide
kubectl get pvc -n toy-infra

# 3. 三台のマシンの/data/kafka-*ディレクトリ内の古いKRaftデータを削除する(新しいクラスターが汚れたデータを再び読み込まないようにするため)
ssh ubuntu@192.168.40.23 "sudo rm -rf /data/kafka-0/*"    # ziqiao-asm100自体がこのIPならローカルで直接実行してもOK
ssh ubuntu@192.168.40.32 "sudo rm -rf /data/kafka-1/*"
ssh ubuntu@192.168.40.14 "sudo rm -rf /data/kafka-2/*"

# 4. PV自体は削除不要(Retainポリシー、パスも残っている)だが、PVのstatusがReleasedのままの可能性があるので、claimRefをクリアしてAvailableに戻す
kubectl patch pv kafka-pv-0 -p '{"spec":{"claimRef": null}}'
kubectl patch pv kafka-pv-1 -p '{"spec":{"claimRef": null}}'
kubectl patch pv kafka-pv-2 -p '{"spec":{"claimRef": null}}'

# 5. KafkaNodePoolとKafkaという二つのCRを再applyする(すでに修正済みの、replicas:3と新しいレプリケーションファクター付きのバージョンを使用)
kubectl apply -f kafka-nodepool.yaml
kubectl apply -f kafka.yaml

観察(かんさつ)ポイント：今回(こんかい)の作(つく)り直(なお)しでtopicデータは失(うしな)われます(KRaftが保存(ほぞん)しているのはメタデータ+partitionログ自体(じたい)で、消(け)せば消(け)える、これがこの道(みち)を選(えら)ぶ以上(いじょう)避(さ)けられない代償(だいしょう)です)——policy-eventsのようなtopicに気(き)にかけているテストデータがあれば、事前(じぜん)に消(け)しても構(かま)わないか確認(かくにん)してください。単(たん)なる練習用(れんしゅうよう)のテストデータであれば、そのまま消(け)して作(つく)り直(なお)すのが一番(いちばん)クリーンな選択(せんたく)です。

まず上記(じょうき)の--previousログ確認(かくにん)コマンドを実行(じっこう)し、結果(けっか)を送(おく)ってください。本当(ほんとう)に同(おな)じIllegalStateExceptionかどうか確認(かくにん)してから、作(つく)り直(なお)すかどうか決(き)めましょう。

English

The current state is fairly serious — let me clarify what this means first: KRaft requires a "majority alive" to function — at least 2 of the 3 controllers need to be alive and in consistent state for elections to converge. Right now both dual-role-0 and dual-role-1 are in CrashLoopBackOff, with only dual-role-2 (on node3) alive — this has already dropped below the minimum quorum threshold (out of 3, it can tolerate at most 1 failure, and now 2 are down). Even though dual-role-2's container shows Running, it likely can't complete any operation requiring "majority confirmation" (leader election, metadata commits), so the Kafka cluster is effectively unusable.

This is tied to node2's earlier repeated crashing/restarting — the most likely sequence: node2 crashed once, dual-role-1 rejoined the cluster and participated in elections with incomplete/stale KRaft state, creating that "leader state contradiction within an epoch" you saw earlier. Once that contradiction occurred, it likely cascaded like dominoes — the previously healthy dual-role-0 triggered the same fatal fault while processing that tainted election message and died too. This explains why both brokers entered crash loops almost simultaneously.

Confirm the diagnosis first, then decide whether to "rebuild from scratch"

Check the previous logs of both crashing pods to confirm whether they show the same IllegalStateException:

bash
kubectl logs my-kafka-dual-role-0 -n toy-infra --previous --tail=50
kubectl logs my-kafka-dual-role-1 -n toy-infra --previous --tail=50

If both show this kind of KRaft internal state contradiction (very likely), this kind of corruption typically can't be "gently repaired" in practice — once KRaft's log has this invariant broken, there's currently no official online repair tool; the only realistic path is resetting the metadata storage so these brokers can form a fresh, clean KRaft quorum.

The practical choice: this is a practice environment, rebuilding is more cost-effective

Continuing to dig into repairing a single broker's KRaft state has low ROI (the Kafka community's advice for this kind of issue is basically "reformat the storage" too), and this was already part of our scaling exercise — a rebuild is itself great material for a "disaster recovery drill."

bash
# 1. Delete the Kafka and KafkaNodePool CRs first (Strimzi will auto-clean up the corresponding pods/PVC bindings)
kubectl delete kafka my-kafka -n toy-infra
kubectl delete kafkanodepool dual-role -n toy-infra

# 2. Confirm pods and PVCs are cleared
kubectl get pods -n toy-infra -o wide
kubectl get pvc -n toy-infra

# 3. Clean out the old KRaft data in /data/kafka-* on all three machines (avoid the new cluster reading dirty data again)
ssh ubuntu@192.168.40.23 "sudo rm -rf /data/kafka-0/*"    # if ziqiao-asm100 is this IP itself, can just run it locally
ssh ubuntu@192.168.40.32 "sudo rm -rf /data/kafka-1/*"
ssh ubuntu@192.168.40.14 "sudo rm -rf /data/kafka-2/*"

# 4. The PVs themselves don't need deleting (Retain policy, path still exists), but their status might be stuck at Released — clear claimRef to bring them back to Available
kubectl patch pv kafka-pv-0 -p '{"spec":{"claimRef": null}}'
kubectl patch pv kafka-pv-1 -p '{"spec":{"claimRef": null}}'
kubectl patch pv kafka-pv-2 -p '{"spec":{"claimRef": null}}'

# 5. Re-apply the KafkaNodePool and Kafka CRs (using your already-updated version with replicas:3 and the new replication factors)
kubectl apply -f kafka-nodepool.yaml
kubectl apply -f kafka.yaml

What to observe: topic data will be lost in this rebuild (KRaft's storage holds both metadata and the partition logs themselves — clearing means clearing, which is the unavoidable cost of this path). If there's test data in topics like policy-events that you care about, confirm beforehand whether it's okay to lose. If it's just practice/test data, wiping and rebuilding clean is the cleanest choice.

Run the --previous log check above first and send me the results to confirm whether it really is the same IllegalStateException, then decide whether to go the rebuild route.

ziqiao@ziqiao-ASM100:~$ kubectl logs my-kafka-dual-role-0 -n toy-infra --previous --tail=50 2026-09-21 11:54:44 INFO [main] ProducerStateManager:441 - [ProducerStateManager partition=__cluster_metadata-0] Wrote producer snapshot at offset 357483 with 0 producer ids in 3 ms. 2026-09-21 11:54:44 INFO

PASTED

Diagnosing a node failure from architecture mismatch clues.
中文

好，这两条日志现在把整件事的因果链完全讲清楚了，而且比我之前判断的更严重一层——这不是同一个问题，是两个问题叠在一起。

dual-role-0：确认是我们分析的那个KRaft状态矛盾

日志完整印证了之前的判断：QuorumState那行写着"quorum store文件里的epoch是0，但log里最后写的epoch是30"——本地持久化的选举状态文件和实际日志对不上，这是一次典型的"写入过程中被中断，导致两份本该同步的记录产生了偏差"。程序自己也检测到了这个不一致（WARN那行），试图带着"epoch 30但leader未知"这个状态继续跑，结果几毫秒后收到一条来自其他节点的、认为"leader已经是0"的消息，两边对不上，直接触发fatal fault自杀。

dual-role-1：这才是真正的根——exec format error

这条完全不是KRaft层面的问题，是容器镜像本身跑不起来——"exec format error"的意思是：这个二进制文件/脚本的格式，跟当前系统的CPU架构或者执行环境不匹配，操作系统的exec系统调用直接拒绝执行它。

排除掉真正的"架构不匹配"（node2跟其他两台一样是amd64，不存在这个可能）之后，几乎可以确定是镜像在拉取或解压过程中被中断、产生了损坏的文件——回想一下时间线：node2之前反复崩溃/被强制重启，很可能就是在某次docker pull/containerd解压镜像层的过程中，宿主机突然被中断，导致这个kafka_run.sh脚本文件被写了一半就断了，变成一个格式不完整、内核根本无法识别怎么执行的"半成品文件"。

完整因果链现在完全清楚了

node2不稳定 → 拉取/解压Kafka镜像时被中断 → dual-role-1本地容器文件系统里的启动脚本损坏 → dual-role-1一启动就exec format error直接失败 → 这个残缺状态的节点尝试参与Raft选举时发出了不完整/错乱的消息 → dual-role-0收到这条消息后触发IllegalStateException一起崩溃——一根线串起来了，不是巧合，是真正的连锁故障。

这意味着单纯"清空KRaft元数据重建"还不够,必须先处理镜像损坏

如果不管这个损坏的镜像,直接走之前的重建步骤,dual-role-1大概率还是会因为同一个exec format error起不来。补一步：

bash
# 先ssh进node2,清掉损坏的镜像缓存,强制下次重新拉取
ssh ubuntu@192.168.40.32
sudo crictl images | grep strimzi   # 找到strimzi/kafka这个镜像的IMAGE ID
sudo crictl rmi <上面查到的IMAGE ID>
exit

清完镜像之后，再执行之前完整的重建流程（删Kafka/KafkaNodePool → 清各节点/data/kafka-*目录 → 清PV的claimRef → 重新apply），这次dual-role-1重新拉取镜像时应该能拿到一份完整、未损坏的文件。

先跑上面清镜像的命令，把crictl images的输出发我确认一下找对了镜像再删，之后我们再继续走重建的完整步骤。

日本語(ふりがな付き)

さて、この二(ふた)つのログでことの因果関係(いんがかんけい)が完全(かんぜん)にはっきりしました。しかも以前(いぜん)の判断(はんだん)よりもう一段階(いちだんかい)深刻(しんこく)です——これは同(おな)じ問題(もんだい)ではなく、二(ふた)つの問題(もんだい)が重(かさ)なっています。

dual-role-0：以前分析(いぜんぶんせき)したKRaft状態(じょうたい)の矛盾(むじゅん)で確定(かくてい)

ログが以前(いぜん)の判断(はんだん)を完全(かんぜん)に裏付(うらづ)けています。QuorumStateの行(ぎょう)には「quorum storeファイル内(ない)のepochは0だが、logに最後(さいご)に書(か)き込(こ)まれたepochは30」とあります——ローカルに永続化(えいぞくか)された選挙状態(せんきょじょうたい)ファイルと実際(じっさい)のlogが食(く)い違(ちが)っている、典型的(てんけいてき)な「書(か)き込(こ)み途中(とちゅう)で中断(ちゅうだん)され、本来(ほんらい)同期(どうき)されているべき二(ふた)つの記録(きろく)にずれが生(しょう)じた」ケースです。プログラム自身(じしん)もこの不整合(ふせいごう)を検知(けんち)し(WARNの行(ぎょう))、「epoch 30だがleader不明(ふめい)」という状態(じょうたい)のまま動(うご)き続(つづ)けようとしましたが、数(すう)ミリ秒後(びょうご)に他(ほか)のノードから「leaderはもう0だ」というメッセージを受(う)け取(と)り、両者(りょうしゃ)が食(く)い違(ちが)ったため、直(ただ)ちにfatal faultで自(みずか)ら停止(ていし)しました。

dual-role-1：これが本当(ほんとう)の根本原因(こんぽんげんいん)です——exec format error

これはKRaft層(そう)の問題(もんだい)ではまったくなく、コンテナイメージ自体(じたい)が起動(きどう)できていません——「exec format error」の意味(いみ)は：このバイナリファイル/スクリプトの形式(けいしき)が、現在(げんざい)のシステムのCPUアーキテクチャや実行環境(じっこうかんきょう)と一致(いっち)しておらず、OSのexecシステムコールがそもそも実行(じっこう)を拒否(きょひ)している、ということです。

本当(ほんとう)の「アーキテクチャ不一致(ふいっち)」の可能性(かのうせい)を排除(はいじょ)すると(node2は他(ほか)の二(ふた)台(だい)と同(おな)じamd64なので、この可能性(かのうせい)はありません)、ほぼ確実(かくじつ)にイメージのpullまたは解凍(かいとう)の途中(とちゅう)で中断(ちゅうだん)が起(お)き、破損(はそん)したファイルが生(う)まれたと考(かんが)えられます——時系列(じけいれつ)を思(おも)い出(だ)してください。node2は以前(いぜん)繰(く)り返(かえ)しクラッシュ/強制(きょうせい)再起動(さいきどう)されており、おそらくあるdocker pull/containerdによるイメージ層(そう)の解凍(かいとう)の最中(さいちゅう)に、ホストマシンが突然(とつぜん)中断(ちゅうだん)され、このkafka_run.shスクリプトファイルが書(か)き込(こ)み途中(とちゅう)で切(き)れてしまい、カーネルがどう実行(じっこう)すればいいかまったく認識(にんしき)できない「未完成(みかんせい)ファイル」になってしまったのでしょう。

完全(かんぜん)な因果関係(いんがかんけい)が今(いま)はっきりしました

node2の不安定(ふあんてい)さ → Kafkaイメージのpull/解凍中(かいとうちゅう)に中断(ちゅうだん) → dual-role-1のローカルコンテナファイルシステム内(ない)の起動(きどう)スクリプトが破損(はそん) → dual-role-1は起動(きどう)した瞬間(しゅんかん)にexec format errorで即座(そくざ)に失敗(しっぱい) → この不完全(ふかんぜん)な状態(じょうたい)のノードがRaft選挙(せんきょ)に参加(さんか)しようとして不完全(ふかんぜん)/おかしなメッセージを発信(はっしん) → dual-role-0がこのメッセージを受(う)け取(と)ってIllegalStateExceptionをトリガーし一緒(いっしょ)にクラッシュ——一本(いっぽん)の線(せん)でつながりました。偶然(ぐうぜん)ではなく、本物(ほんもの)の連鎖障害(れんさしょうがい)です。

つまり単純(たんじゅん)に「KRaftメタデータを消(け)して作(つく)り直(なお)す」だけでは不十分(ふじゅうぶん)で、まずイメージの破損(はそん)を対処(たいしょ)する必要(ひつよう)があります

この破損(はそん)したイメージをそのままにして以前(いぜん)の再構築手順(さいこうちくてじゅん)を進(すす)めても、dual-role-1はおそらく同(おな)じexec format errorでまた起動(きどう)できないままです。一(ひと)つステップを追加(ついか)します：

bash
# まずnode2にsshで入り、破損したイメージキャッシュを削除して、次回強制的に再pullさせる
ssh ubuntu@192.168.40.32
sudo crictl images | grep strimzi   # strimzi/kafkaというイメージのIMAGE IDを探す
sudo crictl rmi <上で見つけたIMAGE ID>
exit

イメージを削除(さくじょ)した後(あと)、前回(ぜんかい)説明(せつめい)した完全(かんぜん)な再構築(さいこうちく)の流(なが)れ(Kafka/KafkaNodePoolを削除(さくじょ) → 各(かく)ノードの/data/kafka-*ディレクトリをクリア → PVのclaimRefをクリア → 再applyする)を実行(じっこう)してください。今回(こんかい)はdual-role-1が再(ふたた)びイメージをpullする際(さい)、完全(かんぜん)で破損(はそん)していないファイルを取得(しゅとく)できるはずです。

まず上記(じょうき)のイメージ削除(さくじょ)コマンドを実行(じっこう)し、crictl imagesの出力(しゅつりょく)を送(おく)ってください。正(ただ)しいイメージを見(み)つけたか確認(かくにん)してから削除(さくじょ)し、その後(ご)一緒(いっしょ)に完全(かんぜん)な再構築手順(さいこうちくてじゅん)を進(すす)めましょう。

English

Alright, these two logs now fully explain the causal chain — and it's actually one layer more serious than I initially assessed: this isn't one problem, it's two problems stacked together.

dual-role-0: confirmed as the KRaft state contradiction we analyzed

The log fully confirms the earlier assessment: the QuorumState line says "epoch in the quorum store file is 0, but the last written epoch in the log is 30" — the locally persisted election-state file and the actual log disagree, a textbook case of "a write got interrupted mid-way, causing two records that should have been in sync to drift apart." The process itself even detected this inconsistency (the WARN line) and tried to proceed with "epoch 30 but leader unknown," but a few milliseconds later received a message from another node claiming "the leader is already 0" — the mismatch immediately triggered a fatal fault and it killed itself.

dual-role-1: this is the actual root cause — exec format error

This is not a KRaft-level issue at all — it's the container image itself failing to run. "exec format error" means: this binary/script's format doesn't match the current system's CPU architecture or execution environment, and the OS's exec syscall flat-out refuses to execute it.

Ruling out genuine "architecture mismatch" (node2 is amd64, same as the other two, so that's not it), it's almost certainly the case that the image got interrupted mid-pull or mid-extraction, producing a corrupted file. Recall the timeline: node2 was repeatedly crashing/force-restarted before — very likely, in the middle of a docker pull/containerd extracting an image layer, the host got interrupted, and the kafka_run.sh script file got written halfway before cutting off, becoming a "half-finished file" the kernel had no idea how to execute.

The full causal chain is now completely clear

node2 instability → interrupted mid Kafka image pull/extraction → dual-role-1's startup script corrupted in the local container filesystem → dual-role-1 fails instantly with exec format error on every start → this crippled node attempted to participate in Raft elections and sent incomplete/garbled messages → dual-role-0 received this message and triggered IllegalStateException, crashing too — one continuous thread. Not a coincidence — a genuine cascading failure.

This means simply "wiping KRaft metadata and rebuilding" isn't enough — the corrupted image needs fixing first

If we go through the earlier rebuild steps while ignoring this corrupted image, dual-role-1 will very likely fail with the same exec format error again. One extra step needed:

bash
# SSH into node2 first, clear the corrupted image cache, forcing a fresh pull next time
ssh ubuntu@192.168.40.32
sudo crictl images | grep strimzi   # find the IMAGE ID for the strimzi/kafka image
sudo crictl rmi <the IMAGE ID found above>
exit

After clearing the image, proceed with the full rebuild flow from before (delete Kafka/KafkaNodePool → clear each node's /data/kafka-* directory → clear the PVs' claimRef → re-apply). This time when dual-role-1 re-pulls the image, it should get a complete, uncorrupted file.

Run the image-clearing command above first and send me the crictl images output so we confirm we found the right image before deleting it, then we'll continue with the full rebuild steps together.