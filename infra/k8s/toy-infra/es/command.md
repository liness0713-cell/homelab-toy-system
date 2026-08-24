查完了，情况和Kafka那次高度相似，直接告诉你结论和依据。

现状：ES在K8s上主要有三条路
Bitnami Elasticsearch chart — 仍然存在(当前版本21.6.3)，但考虑到2025年8月那次Bitnami镜像政策收紧，大概率会踩到和Kafka一模一样的坑(镜像tag被移到付费仓库、免费tag下架)，没有直接证据说ES这边一定会失败，但基于我们已经实锤过的Bitnami Kafka镜像下架经历，这条路风险已知偏高，不建议再赌一次。
Elastic官方Helm chart — Elastic公司自己维护的chart，存在但功能相对基础。
ECK(Elastic Cloud on Kubernetes) — Elastic官方维护的Operator，来源明确写着这是"生产环境的最佳选择"——这正是我们刚学过的"CRD + Operator"那套模式(和Strimzi同一个设计思路)，Elasticsearch集群会变成一个Elasticsearch类型的CRD对象，Operator负责watch并翻译成底层StatefulSet/PVC。 
OneUptime
建议：直接上ECK，跳过Bitnami这条弯路

理由很直接：我们刚用Strimzi把"Operator模式"这套思维练熟了，ECK是同一套架构范式在ES上的对应物——local-storage这个StorageClass、app: es这样打标签的PV、"先建PV再让Operator认领"这套流程，全部可以直接复用，学习成本几乎为零；而Bitnami chart这条路，即便这次侥幸没踩雷，也只是"又装了一遍已知会过期的东西"，价值有限。

要不要现在就开始装ECK Operator？流程会和Strimzi很像：先装Operator本身，再建PV(比如es-pv-0，打app: es标签)，再写一份Elasticsearch类型的CR去认领它。

### 装磁盘 跟kafka磁盘一个逻辑

你的判断完全对——local-storage这个StorageClass是"通用规则"，不是Kafka专属的，之前已经apply过一次，集群里已经存在了，ES可以直接复用，Step3这一步确实该省略。需要重新做的只有"PV(实际的盘)"这一层，因为ES的数据不能跟Kafka的数据混在一起。

按你这两个文件的样式，给你ES对应的版本：

## Step 1：确认node名字（复用之前的，不用重跑）

已知是ziqiao-asm100，跳过。

## Step 2：在宿主机建ES专属的数据目录
bash
sudo mkdir -p /data/es-0
sudo chmod 777 /data/es-0

## Step 3：（跳过，StorageClass已存在）
## Step 4：创建ES专属的PV
bash
cd /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/es   # 建议给ES单独开个目录，别和kafka混在一起
mkdir -p /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/es

```
cat <<'EOF' > es-pv-0.yaml
apiVersion: v1
kind: PersistentVolume
metadata:
  name: es-pv-0
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
    path: /data/es-0
  nodeAffinity:
    required:
      nodeSelectorTerms:
      - matchExpressions:
        - key: kubernetes.io/hostname
          operator: In
          values:
          - ziqiao-asm100
EOF
```

kubectl apply -f /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/es/es-pv-0.yaml
kubectl get pv --show-labels

跟kafka-pv-0.yaml对比，改动只有三处：metadata.name(es-pv-0)、labels.app(es，避免以后跟Kafka的PV混淆抢座位，这是我们之前专门聊过的坑)、local.path(/data/es-0)，其余结构完全一致，storageClassName还是共用local-storage。

一个值得确认的点：8Gi够不够ES用

Elasticsearch通常比Kafka更吃存储空间(尤其是装了ECK之后，可能还会有Kibana之类的组件跟着占地方)，这里先按你之前Kafka一致的量给了8Gi起步，如果你对以后要塞进去多少数据心里有数(比如只是homelab玩具级的测试数据，还是真打算存点实际的日志/搜索数据)，可以告诉我，我们把容量数字调整得更贴合实际用途，跑通后收紧比事后扩容省事。

跑完kubectl get pv --show-labels，确认es-pv-0状态是Available、带着app=es标签，我们就可以继续去装ECK Operator了。

ziqiao@ziqiao-ASM100:~$ kubectl get pv --show-labels
NAME         CAPACITY   ACCESS MODES   RECLAIM POLICY   STATUS      CLAIM                                 STORAGECLASS    VOLUMEATTRIBUTESCLASS   REASON   AGE    LABELS
es-pv-0      16Gi       RWO            Retain           Available                                         local-storage   <unset>                          8s     app=es
kafka-pv-0   8Gi        RWO            Retain           Bound       toy-infra/data-my-kafka-dual-role-0   local-storage   <unset>                          7d8h   app=kafka





PV这边确认无误，es-pv-0是16Gi、Available、app=es标签正确，跟kafka-pv-0互不干扰。现在装ECK Operator。

Step 1：装ECK Operator的CRD和控制器

ECK官方推荐用它自己的Helm chart（先确认一下最新的安装方式，避免又用过时命令）。

Searched the web

中文：

确认清楚了，官方现行安装方式是通过Elastic Helm仓库安装，命令是helm install elastic-operator elastic/eck-operator -n elastic-system --create-namespace。有一个值得先说明的架构决定点。

值得先确认的点：Operator装到独立namespace，不是toy-infra

注意官方命令里用的是-n elastic-system，不是我们一直在用的toy-infra——这是ECK的官方推荐做法，和Strimzi不一样：Strimzi的Operator是"namespace内自治"的模式（只管自己所在namespace的Kafka资源），而ECK Operator默认是集群级(cluster-wide)的，一个Operator可以管理所有namespace下的Elasticsearch资源，所以官方习惯把它单独放到elastic-system这个专属namespace，跟具体业务namespace(toy-infra)分开，逻辑上更清晰——但这个不是必须的，只是官方惯例，如果你想让它也待在toy-infra里，技术上也可以做到，只是要多加参数限定管理范围。我倾向于遵循官方默认习惯，单独给它开elastic-system，这样以后如果排查ECK Operator本身的问题，日志/资源都在一个专门目录里，不会跟Kafka/kafka-ui这些业务组件混在一起。

## Step 1：加Elastic官方Helm仓库，装Operator + CRD
bash
helm repo add elastic https://helm.elastic.co
helm repo update

helm install elastic-operator elastic/eck-operator \
  -n elastic-system \
  --create-namespace
## Step 2：确认Operator跑起来了
bash
kubectl get pods -n elastic-system
kubectl get crd | grep elastic

第二条应该能看到elasticsearches.elasticsearch.k8s.elastic.co这样的条目，跟我们之前kafkanodepools.kafka.strimzi.io是同一类东西。

先跑这两步，把结果贴给我确认Operator健康，我们再写Elasticsearch这个CR去认领es-pv-0。


ziqiao@ziqiao-ASM100:~$ helm repo add elastic https://helm.elastic.co
"elastic" has been added to your repositories
ziqiao@ziqiao-ASM100:~$ helm repo update
Hang tight while we grab the latest from your chart repositories...
...Successfully got an update from the "elastic" chart repository
...Successfully got an update from the "strimzi" chart repository
...Successfully got an update from the "ingress-nginx" chart repository
...Successfully got an update from the "kafka-ui" chart repository
Update Complete. ⎈Happy Helming!⎈
ziqiao@ziqiao-ASM100:~$ helm install elastic-operator elastic/eck-operator \
  -n elastic-system \
  --create-namespace
NAME: elastic-operator
LAST DEPLOYED: Tue Aug 25 07:33:44 2026
NAMESPACE: elastic-system
STATUS: deployed
REVISION: 1
TEST SUITE: None
NOTES:
1. Inspect the operator logs by running the following command:
   kubectl logs -n elastic-system sts/elastic-operator
ziqiao@ziqiao-ASM100:~$ kubectl get pods -n elastic-system
NAME                 READY   STATUS    RESTARTS   AGE
elastic-operator-0   1/1     Running   0          31s
ziqiao@ziqiao-ASM100:~$ kubectl get crd | grep elastic
agents.agent.k8s.elastic.co                            2026-08-24T22:33:45Z
apmservers.apm.k8s.elastic.co                          2026-08-24T22:33:45Z
autoopsagentpolicies.autoops.k8s.elastic.co            2026-08-24T22:33:45Z
beats.beat.k8s.elastic.co                              2026-08-24T22:33:45Z
elasticmapsservers.maps.k8s.elastic.co                 2026-08-24T22:33:45Z
elasticsearchautoscalers.autoscaling.k8s.elastic.co    2026-08-24T22:33:45Z
elasticsearches.elasticsearch.k8s.elastic.co           2026-08-24T22:33:45Z
enterprisesearches.enterprisesearch.k8s.elastic.co     2026-08-24T22:33:45Z
kibanas.kibana.k8s.elastic.co                          2026-08-24T22:33:45Z
logstashes.logstash.k8s.elastic.co                     2026-08-24T22:33:45Z
packageregistries.packageregistry.k8s.elastic.co       2026-08-24T22:33:45Z
stackconfigpolicies.stackconfigpolicy.k8s.elastic.co   2026-08-24T22:33:45Z



Operator跑起来了(1/1 Running)，CRD也注册齐全，elasticsearches.elasticsearch.k8s.elastic.co和kibanas.kibana.k8s.elastic.co都在，说明以后想装Kibana做可视化查询也是同一个Operator顺带管理，不用再装别的。

写Elasticsearch这个CR之前，先确认一下当前稳定的ES版本号，避免像Kafka那次一样凭旧印象写错版本导致校验失败。

Searched the web

中文：

确认了，当前最新稳定版是9.5.2(2026年8月20日发布)。写Elasticsearch这个CR：

## Step 3：写Elasticsearch资源，认领es-pv-0
bash
mkdir -p /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/es
cd /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/es

cat <<'EOF' > es-cluster.yaml
apiVersion: elasticsearch.k8s.elastic.co/v1
kind: Elasticsearch
metadata:
  name: my-es
  namespace: toy-infra
spec:
  version: 9.5.2
  nodeSets:
    - name: default
      count: 1
      config:
        node.store.allow_mmap: false
      volumeClaimTemplates:
        - metadata:
            name: elasticsearch-data
          spec:
            accessModes:
              - ReadWriteOnce
            resources:
              requests:
                storage: 16Gi
            storageClassName: local-storage
            selector:
              matchLabels:
                app: es
EOF

kubectl apply -f /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/es/es-cluster.yaml

几个关键点解释一下：

node.store.allow_mmap: false — 这是ECK官方文档给单节点/homelab场景的标准建议，跳过mmap内存映射的内核参数调优(生产环境通常需要调vm.max_map_count这个宿主机内核参数，homelab场景先关掉这个要求，省一步系统配置)
volumeClaimTemplates.spec.selector.matchLabels.app: es — 语法上和Kafka那次不同(ES这边用的是K8s原生的matchLabels嵌套写法，不是Strimzi自定义的扁平写法)，这次是ECK自己的CRD schema决定的，跟之前Kafka那次“selector格式踩坑”属于同一类问题(不同Operator的CRD各自定义自己的字段格式，不能想当然套用)
namespace: toy-infra — 跟kafka-ui、Kafka集群放在同一个namespace，方便管理，Operator本身虽然在elastic-system，但它管理的具体ES集群资源可以指定到任意namespace


## Step 4：确认状态
bash
kubectl get elasticsearch -n toy-infra
kubectl get pods -n toy-infra
kubectl get pvc -n toy-infra

第一次拉ES镜像可能要一点时间(ES镜像体积比Kafka大不少)，kubectl get elasticsearch那一列会有个HEALTH字段，从空/red变成green才算真正就绪。

跑完把这三条命令的输出贴给我，我们确认存储绑定是否成功指向了es-pv-0(不能又落到local-path)。





ziqiao@ziqiao-ASM100:~$ kubectl get pvc -n toy-infra
NAME                                    STATUS   VOLUME       CAPACITY   ACCESS MODES   STORAGECLASS    VOLUMEATTRIBUTESCLASS   AGE
data-my-kafka-dual-role-0               Bound    kafka-pv-0   8Gi        RWO            local-storage   <unset>                 7d
elasticsearch-data-my-es-es-default-0   Bound    es-pv-0      16Gi       RWO            local-storage   <unset>                 48s
ziqiao@ziqiao-ASM100:~$ ziqiao@ziqiao-ASM100:~$ kubectl get pv --show-labels
ziqiao@ziqiao-ASM100:~$: command not found
ziqiao@ziqiao-ASM100:~$ kubectl get pv --show-labels
NAME         CAPACITY   ACCESS MODES   RECLAIM POLICY   STATUS   CLAIM                                             STORAGECLASS    VOLUMEATTRIBUTESCLASS   REASON   AGE    LABELS
es-pv-0      16Gi       RWO            Retain           Bound    toy-infra/elasticsearch-data-my-es-es-default-0   local-storage   <unset>                          18m    app=es
kafka-pv-0   8Gi        RWO            Retain           Bound    toy-infra/data-my-kafka-dual-role-0               local-storage   <unset>                          7d9h   app=kafka
ziqiao@ziqiao-ASM100:~$ kubectl get pvc -n toy-infra
NAME                                    STATUS   VOLUME       CAPACITY   ACCESS MODES   STORAGECLASS    VOLUMEATTRIBUTESCLASS   AGE
data-my-kafka-dual-role-0               Bound    kafka-pv-0   8Gi        RWO            local-storage   <unset>                 7d
elasticsearch-data-my-es-es-default-0   Bound    es-pv-0      16Gi       RWO            local-storage   <unset>                 2m
ziqiao@ziqiao-ASM100:~$ kubectl get elasticsearch -n toy-infra
NAME    HEALTH   NODES   VERSION   PHASE   AGE
my-es   green    1       9.5.2     Ready   2m20s
ziqiao@ziqiao-ASM100:~$ kubectl get pods -n toy-infra
NAME                                        READY   STATUS    RESTARTS        AGE
kafka-ui-6d4b99c65b-pcq2v                   1/1     Running   2 (50m ago)     3d23h
my-es-es-default-0                          1/1     Running   0               3m21s
my-kafka-dual-role-0                        1/1     Running   5 (2d22h ago)   7d
my-kafka-entity-operator-6d9c4cbfd-8fbjp    2/2     Running   16 (49m ago)    7d
strimzi-cluster-operator-5b4ff798cc-7bnk7   1/1     Running   6 (2d22h ago)   7d8h
ziqiao@ziqiao-ASM100:~$ kubectl get svc -n toy-infra
NAME                       TYPE        CLUSTER-IP      EXTERNAL-IP   PORT(S)                               AGE
kafka-ui                   ClusterIP   10.43.111.171   <none>        80/TCP                                3d23h
my-es-es-default           ClusterIP   None            <none>        9200/TCP                              3m35s
my-es-es-http              ClusterIP   10.43.7.172     <none>        9200/TCP                              3m36s
my-es-es-internal-http     ClusterIP   10.43.77.165    <none>        9200/TCP                              3m36s
my-es-es-transport         ClusterIP   None            <none>        9300/TCP                              3m36s
my-kafka-kafka-bootstrap   ClusterIP   10.43.50.129    <none>        9091/TCP,9092/TCP                     7d
my-kafka-kafka-brokers     ClusterIP   None            <none>        9090/TCP,9091/TCP,8443/TCP,9092/TCP   7d




ES这次一次性跑通，没有中间报错——存储正确绑定到es-pv-0(不是local-path)，HEALTH: green，PHASE: Ready，说明索引分片状态完全健康。

存储链路核对（跟Kafka那次同一套逻辑，完全对上了）
elasticsearch-data-my-es-es-default-0 (PVC) → es-pv-0 (PV) → /data/es-0 (宿主机实际目录)
Service这块的命名规律，正好跟你之前问的"bootstrap vs brokers"是同一个模式
my-es-es-http             ClusterIP   10.43.7.172    9200/TCP   ← 客户端连接入口(类比Kafka的bootstrap)
my-es-es-internal-http    ClusterIP   10.43.77.165   9200/TCP   ← Operator内部健康检查用
my-es-es-default          ClusterIP   None           9200/TCP   ← Headless(类比Kafka的brokers)，精确定位到具体某个ES节点
my-es-es-transport        ClusterIP   None           9300/TCP   ← Headless，ES节点之间做集群内部通信(选主、分片同步)用，类似Kafka broker间通信

以后kafka-ui同款的可视化工具(比如ES自己的Kibana，或者更轻量的Elasticvue)要连ES，应该连**my-es-es-http:9200**这个地址——逻辑跟当初连my-kafka-kafka-bootstrap:9092完全一致，都是"给客户端用的统一入口"。

一个要注意的点：ES默认开了安全认证，需要拿密码才能连

ECK装出来的ES默认启用了X-Pack安全认证(账号是elastic)，密码是Operator自动生成并存进Secret里的，不像我们Kafka那边配的是plain/无认证。取密码：

bash
kubectl get secret my-es-es-elastic-user -n toy-infra -o jsonpath='{.data.elastic}' | base64 -d
echo
建议的下一步

要不要现在就验证一下ES能不能正常读写(比如从Kafka Pod里curl一下ES的健康检查接口，验证跨服务网络是通的)？还是想先装个可视化工具(Kibana，同一个ECK Operator可以直接管理，装法跟Elasticsearch这个CR几乎一样)？或者暂停这条线，先去处理别的事情？

## ECK装出来的ES默认启用了X-Pack安全认证(账号是elastic)，密码是Operator自动生成并存进Secret里的
ziqiao@ziqiao-ASM100:~$ kubectl get secret my-es-es-elastic-user -n toy-infra -o jsonpath='{.data.elastic}' | base64 -d
pkPFLiFqJNTqZ5JiROHU7nhL



好，两件事按顺序来：先验证连通性，再装Kibana。

Step 1：验证ES连通性

先取密码：

Ran a command

中文：

## Step 1：取ES的登录密码
bash
kubectl get secret my-es-es-elastic-user -n toy-infra -o jsonpath='{.data.elastic}' | base64 -d
echo
pkPFLiFqJNTqZ5JiROHU7nhL

把这个密码记下来，后面curl要用。

## Step 2：从集群内部验证ES能否正常访问

起一个临时的调试Pod，跑curl测试(不用改动任何现有Pod)：

bash
kubectl run curl-test --rm -it --image=curlimages/curl --restart=Never -n toy-infra -- \
  curl -u elastic:pkPFLiFqJNTqZ5JiROHU7nhL -k https://my-es-es-http:9200

注意这里是https不是http——ES默认自带TLS加密，这也是ECK的默认安全设定，-k是跳过证书校验(自签名证书，homelab场景先这么处理，够用)。

如果一切正常，应该能看到一段JSON，里面有"cluster_name":"my-es"、"tagline":"You Know, for Search"这样的字段，就说明网络链路、认证都是通的。

```
kubectl run curl-test --rm -it --image=curlimages/curl --restart=Never -n toy-infra -- \=Never -n toy-infra -- \
  curl -u elastic:pkPFLiFqJNTqZ5JiROHU7nhL -k https://my-es-es-http:9200
All commands and output from this session will be recorded in container logs, including credentials and sensitive information passed through the command prompt.
If you don't see a command prompt, try pressing enter.
{
  "name" : "my-es-es-default-0",
  "cluster_name" : "my-es",
  "cluster_uuid" : "HiYMw7zdRUmIGuyoiG9JGg",
  "version" : {
    "number" : "9.5.2",
    "build_flavor" : "default",
    "build_type" : "docker",
    "build_hash" : "b42549c72e6e040825b13e5d8ebf7ff63886b24d",
    "build_date" : "2026-08-18T10:07:58.745010960Z",
    "build_snapshot" : false,
    "lucene_version" : "10.5.1",
    "minimum_wire_compatibility_version" : "8.19.0",
    "minimum_index_compatibility_version" : "8.0.0"
  },
  "tagline" : "You Know, for Search"
}
Session ended, resume using 'kubectl attach curl-test -c curl-test -n toy-infra -i -t' command
pod "curl-test" deleted from toy-infra namespace
```



## Step 3：装Kibana（同一个ECK Operator管理，CR结构和ES非常像）
bash
cd /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/es

cat <<'EOF' > kibana.yaml
apiVersion: kibana.k8s.elastic.co/v1
kind: Kibana
metadata:
  name: my-kibana
  namespace: toy-infra
spec:
  version: 9.5.2
  count: 1
  elasticsearchRef:
    name: my-es
EOF

kubectl apply -f /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/es/kibana.yaml

关键点：elasticsearchRef.name: my-es——这一行是Kibana和ES"配对"的关键，Operator看到这个引用后，会自动帮Kibana注入连接ES所需的地址和认证信息，不需要你手动填ES的地址和密码，这是ECK这种同厂商Operator比kafka-ui那种"手动填bootstrap地址"更省心的地方(因为Kibana和ES是同一个Operator统一管理的两种CRD，能互相感知)。

Kibana本身不需要持久化存储(它是无状态的UI层，数据都在ES那边)，所以这份yaml不需要涉及PV/PVC。

## Step 4：确认Kibana状态
bash
kubectl get kibana -n toy-infra
kubectl get pods -n toy-infra
kubectl get svc -n toy-infra

HEALTH变成green、PHASE变成Ready就算成功。之后如果你想让Kibana也能从宿主机浏览器访问，我们可以照搬kafka-ui那套Ingress流程，加一个kibana.homelab.local的域名映射。

先跑完Step1-2验证连通性，把结果贴给我，再继续看Step3-4的Kibana装配是否顺利。