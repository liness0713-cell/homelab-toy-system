我稍微问了一下sre同事，它们说配置mysql redis等是直接写了aws或azrue给的字符串，没有再搞一层service，哪边是对的？

这个问题问得很好，值得诚实地讲清楚——两边都对，只是在解决"不同层级的问题"，你同事说的是更常见的云原生实践，但这不代表你现在做的这套是错的或者多余的，原因要拆开看。

云环境里为什么直接用连接字符串就够了

AWS RDS、Azure Database、ElastiCache这类托管服务，本身就自带一个由云厂商维护的、永久稳定的DNS域名(比如mydb.xxxxx.rds.amazonaws.com)——这个域名背后，云厂商自己在做"故障转移时IP会变、但域名不变"这件事。也就是说，云厂商的托管DNS，本身就已经是"Service"这一层抽象在做的事情——它已经把"真实物理地址可能变化"这个问题解决掉了，你的应用直接把这串域名当成连接字符串写进配置里，跟直接连一个"永远不会失效的地址"没有区别，K8s自己再包一层Service纯属多此一举，反而增加了一层没必要的间接性。

你现在的homelab场景，本质上没有这层"云厂商托管DNS"

你的MySQL/Redis是跑在宿主机Docker里，唯一的"稳定地址"就是这台node的IP(192.168.40.23)——这个IP本身并不像云厂商的DNS域名那样"永远稳定、故障自动转移"，它就是一台物理机的固定IP，如果你以后换了网卡、重装了系统、加了新的homelab设备做NAT，这个IP有可能变。所以在你的场景里，直接把192.168.40.23:3306硬编码进每个业务应用的配置里，技术上完全可行，跟你同事说的"直接写连接字符串"是同一套逻辑，并不是错的。

那我们做的Service + EndpointSlice这一层，价值在哪

不是必须，但有明确的收益，两点：

统一寻址风格：你的业务代码里，连Kafka用的是my-kafka-kafka-bootstrap:9092，连ES用的是my-es-es-http:9200，这些都是K8s内部DNS名字，不掺杂具体IP。如果MySQL/Redis单独例外，配置里要写死一个IP，会让整套配置风格不统一，以后回头看容易confuse "这个地址是K8s管理的还是外部的"。加上这层Service，业务代码统一都是mysql.toy-infra.svc.cluster.local这种写法，感知不到"这东西其实在宿主机上"这个实现细节。
以后迁移的隔离层：如果你以后决定把MySQL/Redis也搬进K8s集群里(变成StatefulSet+PV，跟Kafka/ES一个待遇)，业务代码一行都不用改，因为它们连的从来都是mysql这个Service名字，不是具体IP——迁移这件事只需要改mysql-service.yaml这一份文件。如果当初是硬编码IP，迁移时得去改每一个引用了这个IP的业务配置。
老实说：这也不是"教科书场景"独有的做法，云环境里也真实存在

云环境里其实也有团队会做同样的事——给RDS/ElastiCache的连接字符串包一层K8s里的ExternalName Service或者跟我们一样的"无selector Service"，图的就是上面说的"统一寻址风格"和"迁移隔离层"这两个收益。你同事说的"直接写连接字符串"是更简单、更常见的做法，但不代表"包一层Service"是错的或者过度设计——这是团队规模、迁移灵活性需求、代码规范偏好上的取舍，不是对错问题。

给你的建议

考虑到你现在这套homelab本身的定位就是"深入理解K8s原生机制、为将来看懂/参与更大架构做准备"，我觉得继续走Service+EndpointSlice这条路是合理的——这正是你能亲手体会"无selector Service"这个K8s概念、以及"为什么云环境可以跳过这一层"这个对比的机会。如果你以后到了真实生产环境、公司本身就用RDS/ElastiCache，那时候直接遵循你同事说的做法(用连接字符串)就是对的，没必要在有托管DNS的情况下画蛇添足加这层。

"无selector Service"本身只是"配置"的一半，另一半必须额外配一个Endpoints资源，手动告诉K8s"这个Service该指向哪个具体IP:端口"。这是因为普通Service靠selector自动去找带对应label的Pod、自动生成路由表；但MySQL/Redis跑在宿主机Docker里，根本不是K8s管的Pod，没有label可选，所以必须手动补上这张"路由表"。

在写具体yaml之前，先确认一下K8s 1.36这个版本下Endpoints这个API是否还是推荐用法，避免像之前v1beta2那次一样用了过期字段。

Searched the web

中文：

好，查证起了作用——避免了一次新的过期API坑。Kubernetes从1.33版本起，Endpoints这个API已经正式弃用，官方明确建议改用discovery.k8s.io/v1下的EndpointSlice。你的集群是1.36(还记得最开始kubectl get nodes -o wide看到的版本吗)，已经在弃用范围内，所以我们直接用新写法，不走Endpoints这条老路。

概念先讲清楚：无selector Service需要两个资源配合
Service — 定义"这个虚拟入口叫什么名字、监听哪个端口"，但因为没有selector，K8s不会自动帮你填"这个入口该转发去哪"
EndpointSlice — 手动告诉K8s"这个入口具体该转发到哪个IP:端口"，通过kubernetes.io/service-name这个label跟Service关联起来
MySQL的yaml
bash
mkdir -p /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/host-services
cd /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/host-services

cat <<'EOF' > mysql-service.yaml
apiVersion: v1
kind: Service
metadata:
  name: mysql
  namespace: toy-infra
spec:
  ports:
    - port: 3306
      targetPort: 3306
---
apiVersion: discovery.k8s.io/v1
kind: EndpointSlice
metadata:
  name: mysql
  namespace: toy-infra
  labels:
    kubernetes.io/service-name: mysql
addressType: IPv4
ports:
  - name: ""
    port: 3306
    protocol: TCP
endpoints:
  - addresses:
      - "192.168.40.23"
EOF

##
kubectl apply -f /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/host-mysql-redis/mysql-service.yaml


Redis的yaml
bash
cat <<'EOF' > redis-service.yaml
apiVersion: v1
kind: Service
metadata:
  name: redis
  namespace: toy-infra
spec:
  ports:
    - port: 6379
      targetPort: 6379
---
apiVersion: discovery.k8s.io/v1
kind: EndpointSlice
metadata:
  name: redis
  namespace: toy-infra
  labels:
    kubernetes.io/service-name: redis
addressType: IPv4
ports:
  - name: ""
    port: 6379
    protocol: TCP
endpoints:
  - addresses:
      - "192.168.40.23"
EOF

##
kubectl apply -f /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/host-mysql-redis/redis-service.yaml

关键点解释：

地址用的是node IP(192.168.40.23)，不是127.0.0.1——因为Docker容器是通过0.0.0.0:3306这种方式发布到宿主机网络的，从K8s Pod的网络命名空间看过去，"宿主机"就是这个node的IP，不能用127.0.0.1(那样解析成的是Pod自己内部的回环地址，不是宿主机)
Service本身没写selector字段——这正是"无selector Service"的写法，靠后面单独的EndpointSlice补上路由信息
EndpointSlice的metadata.labels.kubernetes.io/service-name: mysql这一行是关联的关键——K8s靠这个label(不是靠名字巧合)判断"这个EndpointSlice属于哪个Service"，两者名字相同只是约定俗成，真正生效的是这个label
验证连通性

跟之前测ES一样，起个临时Pod测试：

##
bash
kubectl run mysql-test --rm -it --image=mysql:8.4 --restart=Never -n toy-infra -- \
  mysql -h mysql -u root -p

kubectl run redis-test --rm -it --image=redis:7 --restart=Never -n toy-infra -- \
  redis-cli -h redis ping

（MySQL的root密码需要看你Docker Compose里配的什么，如果记不清可以贴给我；Redis如果没设密码，直接ping应该返回PONG）

##
先跑Service+EndpointSlice这两步apply，把结果贴给我，我们确认kubectl get svc -n toy-infra能看到mysql和redis两个新条目，再一起验证连通性。


ziqiao@ziqiao-ASM100:~$ kubectl apply -f /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/host-mysql-redis/mysql-service.yaml
service/mysql created
endpointslice.discovery.k8s.io/mysql created

ziqiao@ziqiao-ASM100:~$ kubectl apply -f /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/host-mysql-redis/redis-service.yaml
service/redis created
endpointslice.discovery.k8s.io/redis created

ziqiao@ziqiao-ASM100:~$ kubectl get svc -n toy-infra
NAME                       TYPE        CLUSTER-IP      EXTERNAL-IP   PORT(S)                               AGE
kafka-ui                   ClusterIP   10.43.111.171   <none>        80/TCP                                5d
my-es-es-default           ClusterIP   None            <none>        9200/TCP                              24h
my-es-es-http              ClusterIP   10.43.7.172     <none>        9200/TCP                              24h
my-es-es-internal-http     ClusterIP   10.43.77.165    <none>        9200/TCP                              24h
my-es-es-transport         ClusterIP   None            <none>        9300/TCP                              24h
my-kafka-kafka-bootstrap   ClusterIP   10.43.50.129    <none>        9091/TCP,9092/TCP                     8d
my-kafka-kafka-brokers     ClusterIP   None            <none>        9090/TCP,9091/TCP,8443/TCP,9092/TCP   8d
my-kibana-kb-http          ClusterIP   10.43.93.101    <none>        5601/TCP                              24h
mysql                      ClusterIP   10.43.144.58    <none>        3306/TCP                              27s
redis                      ClusterIP   10.43.240.8     <none>        6379/TCP                              12s

ziqiao@ziqiao-ASM100:~$ kubectl run mysql-test --rm -it --image=mysql:8.4 --restart=Never -n toy-infra -- \
  mysql -h mysql -u root -p
All commands and output from this session will be recorded in container logs, including credentials and sensitive information passed through the command prompt.
If you don't see a command prompt, try pressing enter.

Welcome to the MySQL monitor.  Commands end with ; or \g.
Your MySQL connection id is 621
Server version: 8.4.10 MySQL Community Server - GPL

Copyright (c) 2000, 2026, Oracle and/or its affiliates.

Oracle is a registered trademark of Oracle Corporation and/or its
affiliates. Other names may be trademarks of their respective
owners.

Type 'help;' or '\h' for help. Type '\c' to clear the current input statement.

mysql> SHOW DATABASES;
+--------------------+
| Database           |
+--------------------+
| information_schema |
| mysql              |
| performance_schema |
| sys                |
| toy_policy_db      |
+--------------------+
5 rows in set (0.12 sec)

mysql> exit
Bye
Session ended, resume using 'kubectl attach mysql-test -c mysql-test -n toy-infra -i -t' command
pod "mysql-test" deleted from toy-infra namespace

ziqiao@ziqiao-ASM100:~$ kubectl run redis-test --rm -it --image=redis:7 --restart=Never -n toy-infra -- \
  redis-cli -h redis ping
PONG
All commands and output from this session will be recorded in container logs, including credentials and sensitive information passed through the command prompt.
If you don't see a command prompt, try pressing enter.
Session ended, resume using 'kubectl attach redis-test -c redis-test -n toy-infra -i -t' command
pod "redis-test" deleted from toy-infra namespace