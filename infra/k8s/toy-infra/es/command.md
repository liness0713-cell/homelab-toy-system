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

kubectl apply -f /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/es-pv-0.yaml
kubectl get pv --show-labels

跟kafka-pv-0.yaml对比，改动只有三处：metadata.name(es-pv-0)、labels.app(es，避免以后跟Kafka的PV混淆抢座位，这是我们之前专门聊过的坑)、local.path(/data/es-0)，其余结构完全一致，storageClassName还是共用local-storage。

一个值得确认的点：8Gi够不够ES用

Elasticsearch通常比Kafka更吃存储空间(尤其是装了ECK之后，可能还会有Kibana之类的组件跟着占地方)，这里先按你之前Kafka一致的量给了8Gi起步，如果你对以后要塞进去多少数据心里有数(比如只是homelab玩具级的测试数据，还是真打算存点实际的日志/搜索数据)，可以告诉我，我们把容量数字调整得更贴合实际用途，跑通后收紧比事后扩容省事。

跑完kubectl get pv --show-labels，确认es-pv-0状态是Available、带着app=es标签，我们就可以继续去装ECK Operator了。
