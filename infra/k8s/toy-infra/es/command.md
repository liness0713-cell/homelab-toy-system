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