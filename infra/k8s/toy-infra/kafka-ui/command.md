装Kafka管理UI这个想法很合理，帮你梳理一下要打通的两段链路："UI内部怎么连到Kafka"和"宿主机浏览器怎么连到UI"，分别对应K8s里两种不同的机制。

选型：用kafka-ui(Provectus开源项目)

社区里最主流的开源Kafka管理UI，能看topic、消费者组、消息内容，功能全面、有Helm chart方便部署，是目前最常见的选择。

## 第一步：确认Kafka集群对外(集群内)暴露的Service名字

Strimzi会自动帮Kafka资源生成一个"入口Service"，专门给客户端连接用，先确认它的确切名字：

bash
kubectl get svc -n toy-infra

正常应该能看到类似my-kafka-kafka-bootstrap这样的条目（Strimzi的命名规律是<cluster名>-kafka-bootstrap），这就是UI以后要连接的地址——这也是为什么之前我们不需要给Kafka Pod自己开Service，Strimzi装的时候已经顺带建好了。把这条命令的输出贴给我，确认具体名字后我再给你写kafka-ui的Helm安装命令(地址需要精确对上)。

NAME                       TYPE        CLUSTER-IP     EXTERNAL-IP   PORT(S)                               AGE
my-kafka-kafka-bootstrap   ClusterIP   10.43.50.129   <none>        9091/TCP,9092/TCP                     2d23h
my-kafka-kafka-brokers     ClusterIP   None           <none>        9090/TCP,9091/TCP,8443/TCP,9092/TCP   2d23h


## 第二步：装kafka-ui，内部连接指向这个Service

大致命令框架(具体bootstrap server地址等你贴出Step1结果后我补全)：

不要执行这个
bash
helm repo add kafka-ui https://provectus.github.io/kafka-ui-charts
helm repo update

helm install kafka-ui kafka-ui/kafka-ui \
  -n toy-infra \
  --set envs.config.KAFKA_CLUSTERS_0_NAME=my-kafka \
  --set envs.config.KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS=<Step1看到的service名字>.toy-infra.svc.cluster.local:9092
第三步：外部访问，两种方式，你先选一个
宿主机怎么访问这个Kafka UI比较方便？
Ingress（走你已经配好的nginx-ingress，绑一个本地域名，比如kafka-ui.homelab.local）
Thought for 4s

确认清楚了，避免了重蹈覆辙——查证结果：

Provectus那个旧仓库(https://provectus.github.io/kafka-ui-charts)虽然还在，但已经停止维护，跟Docker镜像的情况完全一致
Kafbat(社区fork，接手了这个项目)维护着独立的Helm chart仓库：https://kafbat.github.io/helm-charts，chart名字还是kafka-ui，这个是活跃的正确选择

## Step 2 修正版：装kafbat维护的kafka-ui
bash
helm repo add kafka-ui https://kafbat.github.io/helm-charts
helm repo update

helm install kafka-ui kafka-ui/kafka-ui \
  -n toy-infra \
  --set envs.config.KAFKA_CLUSTERS_0_NAME=my-kafka \
  --set envs.config.KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS=my-kafka-kafka-bootstrap.toy-infra.svc.cluster.local:9092

地址用的是你贴的my-kafka-kafka-bootstrap这个Service，端口选9092（对应我们kafka-cluster.yaml里配置的plain/无TLS监听器；9091是Strimzi自己内部用的controller通信端口，不是给客户端连的，别选错）。


```
我想先问下
svc 
.toy-infra.svc.cluster.local
后面是怎么确定的？可以用命令查处来吗？

kafka为什么连bootstrap，而不是borker？


两个问题都问到了很实际的机制层面，分开讲。

一、.toy-infra.svc.cluster.local这个后缀怎么确定的

这是K8s内部DNS的固定格式，规律是：

<Service名字>.<Namespace>.svc.<集群域名>

svc是固定关键字(表示"这是个Service类型的DNS记录")，cluster.local是集群级别的默认域名后缀，这个可以配置(理论上装集群时能自定义成别的)，但k3s默认就是cluster.local，没有特殊配置的话基本不会变。

可以用命令验证，不用凭记忆猜——这里有两种查法：

方法1：直接看CoreDNS的配置(权威来源)

bash
kubectl get configmap coredns -n kube-system -o yaml

输出里能看到实际配置的域名后缀是什么(k3s默认集成了CoreDNS作为集群DNS)。

方法2：进到任意一个Pod里，直接看它自己的DNS配置（更直观，推荐）

bash
kubectl exec -it my-kafka-dual-role-0 -n toy-infra -- cat /etc/resolv.conf

每个Pod启动时，K8s会自动往它里面注入一份resolv.conf，search那一行会列出这个Pod能用的DNS后缀搜索列表，cluster.local会在其中。

方法3：最直接——实际做一次DNS查询验证

bash
kubectl run dns-test --rm -it --image=busybox --restart=Never -n toy-infra -- nslookup my-kafka-kafka-bootstrap.toy-infra.svc.cluster.local

如果能解析出一个ClusterIP地址，就实锤验证了这个域名确实有效。

顺带说一句，为什么我们之前的yaml/命令里其实可以省略后面这一串——同一个namespace内部访问，光写Service名字(my-kafka-kafka-bootstrap)就够用了，K8s的DNS搜索域会自动帮你补全；只有跨namespace访问时才必须写完整的<svc>.<namespace>.svc.cluster.local。我给你的kafka-ui命令里写了全称，是为了显式、不依赖"默认搜索域"这种隐式行为，排错时更直观。

二、为什么连bootstrap，不连brokers

回顾你贴的那两个Service：

my-kafka-kafka-bootstrap   ClusterIP   10.43.50.129   9091/TCP,9092/TCP
my-kafka-kafka-brokers     ClusterIP   None(!)        9090,9091,8443,9092/TCP

关键区别在CLUSTER-IP这一列——bootstrap有一个真实的固定IP，brokers那边写的是None，这个None意味着它是个Headless Service（无头服务），这是理解这两者分工的核心。

bootstrapService：给客户端用的"总机号码"
它背后其实通过负载均衡指向"随便哪个broker都行"（单节点场景下自然只有一个），Kafka客户端库(不管是kafka-ui、你的业务代码，还是命令行工具)连接时，只需要知道这一个地址就够了——这是Kafka协议自己的设计：客户端连上bootstrap地址后，第一件事是发一个"Metadata请求"，问Kafka"你集群里所有的broker都在哪、每个partition的leader是谁"，Kafka把这份"全体broker地址簿"返回给客户端后，客户端后续会自己直接连到对应的broker，不再依赖bootstrap这个入口。所以"bootstrap"这个名字很贴切——它只负责"启动阶段的引导发现"，不是持续的转发通道。

brokers这个Headless Service：给broker之间互相通信、以及K8s内部治理用的"花名册"
Headless Service(ClusterIP: None)的特点是：它不做负载均衡，查询这个Service的DNS时，返回的不是一个统一虚拟IP，而是背后每一个Pod各自的真实IP(格式类似my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc.cluster.local能精确指向某一个broker)。这种"精确点名"的能力，是StatefulSet管理有状态服务的标配搭配——Strimzi自己内部需要用它来做broker间的数据同步、controller选举通信这些"必须知道具体是哪一个Pod"的场景；如果你以后需要用类似kafkacat这类工具做更底层的调试，直接连某一个特定broker(比如想测试"只连broker-1看行为对不对")，也是走这个Headless Service。

结论：kafka-ui这种"客户端应用"，只需要"能发消息、能读消息、能看集群状态"，天然应该连bootstrap——这也是所有Kafka客户端库的标准做法，配置项通常也直接叫bootstrap.servers，这不是我们随意选的，是Kafka生态的通用惯例。
```


中文：

好，走Ingress这条路。整体链路会是这样：

宿主机浏览器 → http://kafka-ui.homelab.local
      → (需要在宿主机/etc/hosts里加一条解析)
      → nginx-ingress Controller (接收请求，按域名转发)
      → kafka-ui这个Service (集群内部)
      → kafka-ui这个Pod
      → my-kafka-kafka-bootstrap这个Service (Kafka入口)
      → my-kafka-dual-role-0这个Pod (真正的Kafka)


## Step 3：创建Ingress资源，绑定域名

（ingressClassName: nginx要跟你之前装nginx-ingress时用的class名字对上——如果当初装的时候有自定义过这个名字，跑一下kubectl get ingressclass确认一下实际叫什么，避免又踩一次"名字对不上"的坑）

Ingress资源（和之前给你的一致，先确认一下ingressClassName）
bash
kubectl get ingressclass
确认输出里的NAME字段（大概率是nginx，但先核实一下，避免又是一次"名字对不上"）。确认

NAME    CONTROLLER             PARAMETERS   AGE
nginx   k8s.io/ingress-nginx   <none>       26d


bash
cat <<'EOF' > kafka-ui-ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: kafka-ui
  namespace: toy-infra
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /
spec:
  ingressClassName: nginx
  rules:
    - host: kafka-ui.homelab.local
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: kafka-ui
                port:
                  number: 80
EOF

kubectl apply -f /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/kafka-ui/kafka-ui-ingress.yaml -n toy-infra
kubectl get ingress -n toy-infra

## Step 4：宿主机加DNS解析

这一步在你自己电脑的终端，不是k3s集群里：

bash
echo "192.168.40.23 kafka-ui.homelab.local" | sudo tee -a /etc/hosts

（192.168.40.23是你node的IP，跟之前kubectl get nodes -o wide看到的一致——因为k3s单节点场景下，nginx-ingress的入口就是这台node本身）