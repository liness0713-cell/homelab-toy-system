5个业务服务的镜像全部推送成功——frontend、gateway-service、notification-service、policy-service、search-service都在registry里了。回顾一下最初的行动指南进度：

1. Namespace ✅
2. 本地私有仓库(registry:2) ✅
3. Kafka/ES 用Operator装进toy-infra ✅
4. MySQL/Redis 的无selector Service ✅
5. 打包推送5个业务服务镜像 ✅（刚完成）
6. ConfigMap/Secret ← 下一步
7. Deployment/Service/Ingress
下一步Step6：ConfigMap/Secret，先理清楚哪些配置该放哪

回顾一下你贴过的几份application.yml，里面的环境变量大致分两类：

该放ConfigMap的(非敏感配置)：

SPRING_DATASOURCE_URL、SPRING_REDIS_HOST、KAFKA_BOOTSTRAP_SERVERS、ELASTICSEARCH_URIS这类"地址/连接目标"
POLICY_SERVICE_URI、SEARCH_SERVICE_URI这类gateway路由目标
CORS_ALLOWED_ORIGIN_1/2

该放Secret的(敏感凭证)：

SPRING_DATASOURCE_USERNAME/SPRING_DATASOURCE_PASSWORD(MySQL的toy_app/toy_app_pw)
JWT_SECRET

要现在开始写这两份yaml吗？在动手之前，有一个决定点需要先跟你确认：这5个服务的ConfigMap，是每个服务各自一份，还是共享一份公共的（比如KAFKA_BOOTSTRAP_SERVERS这种被3个服务同时用到的值，写3遍容易以后改的时候漏改一处）？

ConfigMap组织方式想怎么安排？
拆成两层：一份共享ConfigMap(Kafka/ES地址这类公共值)+每个服务自己的个性化ConfigMap
Searched the web

中文：

确认了spring.elasticsearch.username/password是Spring Boot官方标准属性(从2.4.0版本就有，3.x延续)，不需要额外写代码就能配置认证——但这里牵出了一个之前没聊过的、必须现在处理的问题：ES走的是HTTPS+自签名证书，Spring Boot默认的Elasticsearch客户端会因为"不认识这个证书颁发者"而拒绝连接(跟我们之前curl -k跳过校验是同一个问题，但Java客户端不能简单加个-k绕过)。

需要你先做个选择：search-service怎么处理ES的自签名证书信任问题
search-service连接ES时，怎么处理自签名证书信任问题？
推荐做法：把ECK自动生成的CA证书注入search-service的信任库（需要在Dockerfile/启动命令里加一步处理，更贴近生产实践）
Searched the web

中文：

确认了官方文档——ECK会自动生成一个叫<cluster名>-es-http-certs-public的Secret(在你这套环境里就是my-es-es-http-certs-public)，里面有ca.crt这个字段，就是我们需要信任的CA证书。

整体思路：Java不能直接读PEM证书，需要转换成"信任库(truststore)"格式

这是需要理解的关键机制——Java的TLS信任机制不认PEM格式的证书文件，需要先用Java自带的keytool工具，把ca.crt导入一个JKS格式的truststore文件，再告诉JVM"启动时用这个truststore"。因为证书内容是运行时才通过K8s Secret挂载进Pod的(不是构建镜像时就知道的)，这一步必须放在容器启动脚本里做，不能写死进Dockerfile的构建阶段。

修改search-service的Dockerfile：加一个启动脚本
dockerfile
# ---------- 构建阶段(跟其他服务一致) ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY event-contracts/pom.xml event-contracts/pom.xml
COPY policy-service/pom.xml policy-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY search-service/pom.xml search-service/pom.xml
RUN mvn -pl search-service -am dependency:go-offline -B
COPY event-contracts/src ./event-contracts/src
COPY search-service/src ./search-service/src
RUN mvn -pl search-service -am clean package -DskipTests -B

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/search-service/target/*.jar app.jar
COPY search-service/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh
EXPOSE 8083
ENTRYPOINT ["/app/entrypoint.sh"]

新建search-service/entrypoint.sh
bash
#!/bin/sh
set -e

# 把ECK自动生成的CA证书，导入一份Java能用的truststore
keytool -importcert \
  -trustcacerts \
  -noprompt \
  -alias es-ca \
  -file /certs/ca.crt \
  -keystore /tmp/truststore.jks \
  -storepass changeit

# 把truststore路径告诉JVM，再启动应用
exec java \
  -Djavax.net.ssl.trustStore=/tmp/truststore.jks \
  -Djavax.net.ssl.trustStorePassword=changeit \
  -jar app.jar

关键点解释：

/certs/ca.crt这个路径现在还不存在，等我们写search-service的Deployment yaml时(Step7)，需要把my-es-es-http-certs-public这个Secret挂载成一个Volume，挂到/certs这个路径下——这是接下来必须记住要做的事
ENTRYPOINT还是exec形式(还记得我们讲的信号传递问题吗)，只是这次指向的是一个脚本，脚本内部最后一行用exec java ...(不是直接java ...)——这里的exec是shell自己的内置命令，作用是"用java这个进程，直接替换掉当前这个shell脚本进程，而不是让shell继续存在、把java当子进程去等待"，这样容器的1号进程最终还是Java本身，不会因为"套了一层脚本"就又把我们之前解决的信号传递问题带回来
-storepass changeit——这只是本地临时truststore的密码，不是什么敏感凭证，changeit是Java生态里公认的"占位密码"惯例，随便设也没关系，只在容器内部这个一次性文件生效

现在写ConfigMap和Secret——两层结构，如你所定
bash
mkdir -p /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/config
cd /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/config

# 共享ConfigMap：中间件地址这类公共值
bash
cat <<'EOF' > shared-configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: shared-config
  namespace: toy-infra
data:
  KAFKA_BOOTSTRAP_SERVERS: "my-kafka-kafka-bootstrap.toy-infra.svc.cluster.local:9092"
  ELASTICSEARCH_URIS: "https://my-es-es-http.toy-infra.svc.cluster.local:9200"
  SPRING_REDIS_HOST: "redis.toy-infra.svc.cluster.local"
  SPRING_REDIS_PORT: "6379"
  SPRING_DATASOURCE_URL: "jdbc:mysql://mysql.toy-infra.svc.cluster.local:3306/toy_policy_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
EOF

kubectl apply -f shared-configmap.yaml

# 各服务自己的ConfigMap
bash
cat <<'EOF' > gateway-configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: gateway-config
  namespace: toy-infra
data:
  POLICY_SERVICE_URI: "http://policy-service.toy-infra.svc.cluster.local:8081"
  SEARCH_SERVICE_URI: "http://search-service.toy-infra.svc.cluster.local:8083"
  CORS_ALLOWED_ORIGIN_1: "https://app.homelab.local"
EOF

cat <<'EOF' > policy-configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: policy-config
  namespace: toy-infra
data:
  SERVER_PORT: "8081"
EOF

kubectl apply -f gateway-configmap.yaml
// kubectl apply -f policy-configmap.yaml

（notification/search两个服务目前application.yml里除了共享值，没有额外的独有配置项，暂时不需要单独ConfigMap，等以后有需要再加）

### Secret：敏感凭证
bash
kubectl create secret generic mysql-credentials \
  -n toy-system \
  --from-literal=SPRING_DATASOURCE_USERNAME=toy_app \
  --from-literal=SPRING_DATASOURCE_PASSWORD=toy_app_pw

kubectl create secret generic jwt-secret \
  -n toy-system \
  --from-literal=JWT_SECRET=toy-system-dev-secret-key-please-change-me-in-prod-0001

# ES的elastic账号密码，取的是我们之前从ECK自动生成的secret里读出来的那串
kubectl create secret generic es-credentials \
  -n toy-system \
  --from-literal=ELASTICSEARCH_USERNAME=elastic \
  --from-literal=ELASTICSEARCH_PASSWORD=pkPFLiFqJNTqZ5JiROHU7nhL

取ES的登录密码
bash
kubectl get secret my-es-es-elastic-user -n toy-infra -o jsonpath='{.data.elastic}' | base64 -d
echo
pkPFLiFqJNTqZ5JiROHU7nhL

（用kubectl create secret generic --from-literal这种命令行方式，而不是写yaml文件，是因为敏感内容不适合明文存进你的git仓库——这几条命令本身也不建议存进代码仓库的脚本里，跑一次记住效果就行；如果你想要yaml形式管理，需要手动base64编码塞进data字段，或者引入外部密钥管理工具，这个可以后面有需要再聊）

下一步Step7：Deployment/Service/Ingress

ConfigMap/Secret建好后，下一步就是把这几个环境变量真正"接"到Pod里，加上CA证书那个Volume挂载。这块工作量还不小，要现在继续，还是先到这里？



有个小订正，我们现在部署应用，namespace已经换到了toy-system，上面cm先不改，我自己改，我还需要逐一核对key alue。另外我对kubectl  secret 不是很了解，我们存进去，其实是可以通过命令拿到明文的对不，另外es这里我看到了很多secret，有登录密码，有证书。。我把输出贴给你分别讲讲，我还看到了my-es-es-http-certs-internal，为什么用public呢？

ziqiao@ziqiao-ASM100:~$ kubectl get secret  -n toy-infra
NAME                                           TYPE                 DATA   AGE
my-es-es-default-es-config                     Opaque               1      5d
my-es-es-default-es-transport-certs            Opaque               3      5d
my-es-es-elastic-user                          Opaque               1      5d
my-es-es-file-settings                         Opaque               1      5d
my-es-es-http-ca-internal                      Opaque               2      5d
my-es-es-http-certs-internal                   Opaque               3      5d
my-es-es-http-certs-public                     Opaque               2      5d
my-es-es-internal-users                        Opaque               5      5d
my-es-es-remote-ca                             Opaque               1      5d
my-es-es-transport-ca-internal                 Opaque               2      5d
my-es-es-transport-certs-public                Opaque               1      5d
my-es-es-xpack-file-realm                      Opaque               4      5d
my-kafka-clients-ca                            Opaque               1      12d
my-kafka-clients-ca-cert                       Opaque               3      12d
my-kafka-cluster-ca                            Opaque               1      12d
my-kafka-cluster-ca-cert                       Opaque               3      12d
my-kafka-cluster-operator-certs                Opaque               2      12d
my-kafka-dual-role-0                           Opaque               2      12d
my-kafka-entity-topic-operator-certs           Opaque               2      12d
my-kafka-entity-user-operator-certs            Opaque               2      12d
my-kafka-trustbundle                           Opaque               2      12d
my-kibana-kb-config                            Opaque               2      4d23h
my-kibana-kb-es-ca                             Opaque               2      4d23h
my-kibana-kb-http-ca-internal                  Opaque               2      4d23h
my-kibana-kb-http-certs-internal               Opaque               3      4d23h
my-kibana-kb-http-certs-public                 Opaque               2      4d23h
my-kibana-kibana-user                          Opaque               4      4d23h
sh.helm.release.v1.kafka-ui.v1                 helm.sh/release.v1   1      9d
sh.helm.release.v1.strimzi-kafka-operator.v1   helm.sh/release.v1   1      12d
toy-infra-my-kibana-kibana-user                Opaque               2      4d23h



中文：

先记一下：namespace已经换成toy-system

明白，之前给的ConfigMap/Secret示例继续沿用toy-infra这个字眼，你自己改成toy-system、并逐一核对key/value——这个很有必要，因为环境变量名必须跟application.yml里${XXX}占位符精确对应，一个字母错了就会导致该配置项悄悄退回默认值，排查起来很隐蔽。

一、Secret存的是不是明文——能否直接读出来

能，而且很容易——Secret默认存储方式是Base64编码，不是加密，这是个经常被误解的点。Base64是一种"编码"，不是"密码学意义上的加密"，任何拿到这段Base64字符串的人，不需要密钥、不需要密码，直接一行命令就能还原出明文：

bash
kubectl get secret mysql-credentials -n toy-system -o jsonpath='{.data.SPRING_DATASOURCE_PASSWORD}' | base64 -d

也就是说，Secret这个机制真正提供的安全性，只是"不让明文直接暴露在yaml文件的data字段里、不让它出现在kubectl get的默认输出里"，本质上是"防止随手看到"，不是"防止蓄意查看"——任何有权限执行kubectl get secret的人(比如有权限访问这个namespace的用户，或者k3s自己在etcd里存的原始数据，如果没有额外配置etcd加密)，都能轻松解码出明文。真正的生产级敏感信息管理，通常会用Vault、Sealed Secrets、外部KMS这类工具做真正的加密，K8s原生Secret更多是"规范上把敏感信息和普通配置分开管理"这层意义，而不是一个坚固的保险箱。这一点你现在知道就够了，homelab场景不用额外处理，但以后接触真实生产环境时，"Secret不等于安全"这个认知很重要。

二、ES这一堆Secret，分组解释

先按用途分类，再重点回答"为什么用public"这个问题。

1. 登录凭证类
my-es-es-elastic-user——elastic超级管理员账号的密码，就是我们之前取出来登录Kibana用的那个
my-es-es-internal-users、my-es-es-xpack-file-realm——ES内部还有一些系统级内置账号(不是给人用的，是ES各组件之间互相调用API时用的服务账号)，这几个Secret存的是这类内部账号的凭证，一般用不上，不用管
2. HTTP层证书(客户端连ES的9200端口用的这一层)——这是我们刚才要处理的
my-es-es-http-ca-internal——CA(证书颁发机构)的证书+私钥
my-es-es-http-certs-internal——ES自己(服务端)用来在9200端口提供HTTPS服务的证书+私钥
my-es-es-http-certs-public——只包含CA证书和HTTP证书本身，不含任何私钥
3. Transport层证书(ES节点之间互相通信用的9300端口这一层，我们之前聊过的"店长之间开会"那条内部通道)
my-es-es-transport-ca-internal、my-es-default-es-transport-certs、my-es-es-transport-certs-public——同样是"内部CA+私钥"vs"公开、不含私钥"这个分组逻辑，只是针对的是节点间通信，不是给外部客户端连的，我们search-service用不上这几个
4. 其他运维类
my-es-es-file-settings、my-es-es-remote-ca、my-es-default-es-config——ECK Operator自己内部管理集群配置、跨集群复制这类功能用的，不需要你手动碰
三、回答你的核心问题：为什么用public而不是internal

关键区别就在于私钥在不在里面——查证过的官方文档原话是这么描述的：

xxx-ca-internal——包含CA证书和私钥(仅供内部使用)
xxx-certs-internal——包含HTTP证书和私钥(仅供内部使用)
xxx-certs-public——只包含CA证书和HTTP证书，不含任何私钥

私钥是"证明我就是ES服务端本人"的凭证，只有ES自己(以及负责签发证书的Operator)应该持有它——如果我们把带私钥的internal版本挂给search-service用，等于把"冒充ES服务端"的能力也一起给了出去，这是完全不必要、且有安全风险的。

而我们真正需要的，只是**"信任这份证书的颁发者是谁"这一件事——也就是ca.crt这个字段，这个信息本来就该是"公开的"，任何想连接ES的客户端都应该能拿到它去验证"我连的这个ES，证书是不是这个CA签发的、值得信任"，但不应该有能力自己签发新证书或冒充服务端。public这个Secret存在的意义，正是"专门切出一份只包含验证所需信息、剔除了签发权限的安全版本"，供外部客户端(比如我们的search-service)安全消费**——这是一个很典型的"最小权限原则"体现：只给消费者验证身份所需的东西，不给它额外的、用不上还有风险的东西。

日本語(かな付き)：

SecretはBase64[エンコード:えんこーど]([暗号化:あんごうか]ではない)なので、kubectl get secret ... | base64 -dで[誰:だれ]でも[平文:へいぶん]に[戻:もど]せます——[本当:ほんとう]の[意味:いみ]でのセキュリティは[提供:ていきょう]していません。

ESの[証明書:しょうめいしょ][系:けい]Secretでinternalとpublicの[違:ちが]いは、[秘密鍵:ひみつかぎ]が[入:はい]っているかどうかです。internal版には[秘密鍵:ひみつかぎ]が[含:ふく]まれており([サーバー:さーばー][自身:じしん]や[証明書:しょうめいしょ]を[発行:はっこう]するOperatorだけが[持:も]つべきもの)、public版はCA[証明書:しょうめいしょ]だけを[含:ふく]み[秘密鍵:ひみつかぎ]は[一切:いっさい]ありません。search-serviceのような**[外部:がいぶ][クライアント:くらいあんと]**は「この[証明書:しょうめいしょ]は[信頼:しんらい]できるCAが[発行:はっこう]したものか」を[検証:けんしょう]する[能力:のうりょく]だけがあればよく、[サーバー:さーばー]になりすます能力:のうりょくは[不要:ふよう]かつ[危険:きけん]なので、public版を[使:つか]うのが[正:ただ]しい([最小権限:さいしょうけんげん]の[原則:げんそく])。

English:

Secrets are Base64-encoded, not encrypted — anyone can decode them back to plaintext with kubectl get secret ... | base64 -d. It doesn't provide real security on its own.

For the ES certificate secrets, the internal vs public distinction comes down to whether the private key is included. The internal versions contain the private key (which only the server itself, or the Operator issuing certs, should ever hold), while public contains only the CA certificate and HTTP certificate — no private key at all. An external client like search-service only needs the ability to verify "was this certificate issued by a CA I trust" — it has no legitimate need for (and shouldn't have) the power to impersonate the server, which is exactly what the private key would grant. Using public is the correct application of least-privilege here.


kubectl get secret mysql-credentials -n toy-system -o jsonpath='{.data.SPRING_DATASOURCE_PASSWORD}' | base64 -d
拆解讲一下这个命令

拆开逐段讲。

kubectl get secret mysql-credentials -n toy-system

标准的资源查询命令——"去toy-system这个namespace里，把名叫mysql-credentials的这个Secret资源的内容取出来"。单独跑这一条，默认输出是个表格摘要(名字、类型、有几个key、创建了多久)，看不到具体的value内容。

-o jsonpath='{.data.SPRING_DATASOURCE_PASSWORD}'

-o是"output format"(输出格式)，kubectl默认给你看的是人类友好的表格，但背后任何一个K8s资源，本质上都是一份完整的JSON/YAML结构数据——-o jsonpath=...就是告诉kubectl："别给我表格了，我要从这份完整数据里，精确挖出某一个字段的值"。

jsonpath是一种类似"用路径指路"的查询语法，{.data.SPRING_DATASOURCE_PASSWORD}翻译成人话就是："从这份JSON的最外层，找到data这个字段，再从data里面，找到SPRING_DATASOURCE_PASSWORD这个key，把它的值原样吐出来"。

如果你不加-o jsonpath，直接跑kubectl get secret mysql-credentials -n toy-system -o yaml，会看到类似这样的完整结构：

yaml
apiVersion: v1
kind: Secret
metadata:
  name: mysql-credentials
  namespace: toy-system
data:
  SPRING_DATASOURCE_USERNAME: dG95X2FwcA==
  SPRING_DATASOURCE_PASSWORD: dG95X2FwcF9wdw==

jsonpath那条命令，就是从这整份结构里，精确定位到data.SPRING_DATASOURCE_PASSWORD这一行，只把dG95X2FwcF9wdw==这一串东西打印出来，跳过前面所有你不需要看的部分——这比自己盯着一大坨yaml输出、肉眼去找那一行方便得多，尤其是写脚本自动化处理时更是必须这么做(不可能让脚本去"肉眼"解析yaml排版)。

中间的 | (管道符)——之前你已经问过这个概念

把前一条命令的输出(也就是刚才那个dG95X2FwcF9wdw==)，作为输入喂给下一条命令。

base64 -d

-d是"decode"(解码)的意思——把接收到的这串dG95X2FwcF9wdw==，按Base64规则反向还原成它编码前的原始内容，也就是toy_app_pw这个明文密码。

整体串起来

"从toy-system这个namespace里，找到mysql-credentials这个Secret，精确挖出data字段下SPRING_DATASOURCE_PASSWORD这个key对应的Base64字符串，把这串字符串还原成明文密码，打印出来。"

这也正好印证了我们之前讲的——这整个过程完全不需要任何"密钥"或"授权口令"，唯一的门槛是"你有没有权限执行kubectl get secret这个操作本身"(由K8s的RBAC权限系统控制)，一旦有这个查询权限，剩下的jsonpath+base64 -d就是纯粹的格式转换，谁都能做，没有额外的加密屏障。




policy-configmap.yaml  这个似乎用不到，只是写了自己的端口号而已。。。除了这个我都创建在toy-system了，可以继续了

理解到位，SERVER_PORT这个值跟application.yml里的默认值(${SERVER_PORT:8081})完全一样，属于"写了等于没写"，可以先不建这个ConfigMap，等以后哪天真的需要覆盖默认端口再加。

进入Step7之前，有一个必须先确认、否则所有Deployment都会失败的问题——k3s默认不信任localhost:5000这种没有HTTPS的私有registry，直接写Deployment指向这个镜像地址，大概率会在拉镜像这一步就卡死。这个问题不能靠猜，得先查证。

中文：

查证结果很关键——k3s默认不认识、也不会尝试访问明文HTTP的私有registry，必须显式配置一个registries.yaml文件，告诉containerd(k3s底层真正负责拉镜像的组件)"这个地址是可信的，走HTTP也没问题"。这一步不做，之前推送成功的5个镜像，K8s会完全拉取失败。

配置registries.yaml
bash
sudo mkdir -p /etc/rancher/k3s

cat <<'EOF' | sudo tee /etc/rancher/k3s/registries.yaml
mirrors:
  "localhost:5000":
    endpoint:
      - "http://192.168.40.23:5000"
configs:
  "localhost:5000":
    tls:
      insecure_skip_verify: true
EOF

sudo systemctl restart k3s

关键点解释：

mirrors——告诉containerd"当有人要拉localhost:5000/xxx这个地址的镜像时，该去哪个真实endpoint找"，这里endpoint和"名字"是同一个地址，看起来有点绕，但这是标准写法——因为我们镜像tag里写的就是localhost:5000/gateway-service:xxx，这个"名字"本身就需要被路由到"具体怎么访问"这个信息
configs.tls.insecure_skip_verify: true——明确告诉containerd"这个地址不用HTTPS证书校验"，因为你的registry:2容器本身跑的是最简单的HTTP，没有配置任何TLS证书
让配置生效——需要重启k3s服务
bash
sudo systemctl restart k3s

注意：这一步会让整个k3s集群短暂重启(所有Pod会经历一次短暂中断，然后自动恢复)，因为k3s是把整个控制面+kubelet打包成一个服务在跑，重启这个服务等于重启整个集群的"大脑"。在你目前的开发阶段做这个操作没问题，但这个操作本身值得记住——生产环境这么干是要走变更管理流程的。

验证方式：等所有Pod恢复正常运行后，用一个小测试确认
bash
kubectl get pods -A   # 确认所有namespace的Pod都恢复Running了

# 建一个测试Pod，直接指定拉我们私有registry里的某个镜像，验证containerd真的能拉到
kubectl run registry-test --rm -it --image=localhost:5000/frontend:202608291813-b836314 --restart=Never -n toy-system -- echo "pull ok"

如果这个临时Pod能正常跑起来(不是卡在ImagePullBackOff)，说明registries.yaml配置生效了，我们可以放心开始写5个服务的Deployment。

先跑这几步，确认没问题后我们继续Step7。


sudo tee 是啥意思来着？它能覆盖现有的吗

之前我们聊tee的时候用的是-a(追加模式)，这次命令里没有-a，这个区别很关键。

tee不带-a——默认行为是"�covered up覆盖整个文件"

回顾一下tee的本职工作："把输入内容，一边显示在终端，一边写入文件"——默认模式下，写入文件这个动作是"覆盖"，不是"追加"：如果目标文件原本有内容，会被完全清空，只剩下这次传进去的新内容。这次我们用heredoc生成的那一整段mirrors:\nendpoint:...内容，会完全替换掉/etc/rancher/k3s/registries.yaml原来的全部内容(也就是你之前那份写错了192.168.40.23那个key的版本)，不会两份内容叠在一起变成一个混乱的文件。

对比一下之前/etc/hosts那次为什么必须加-a：那次是往一个已经有大量其他重要内容(比如127.0.0.1 localhost这些系统本身的解析记录)的文件里"追加一行"，用-a是为了保留原有内容；而这次registries.yaml，我们是要整个替换掉上一版配置(因为上一版写错了)，所以正好不加-a，用默认的覆盖行为，反而是我们想要的效果。

顺带确认一下，跟sudo的配合逻辑还是同一套

sudo tee /etc/rancher/k3s/registries.yaml——这里还是我们之前讲过的"绕过sudo echo ... > file不生效"这个坑的标准解法：sudo提权给tee这个进程本身，让它有权限写入这个属于root的系统配置文件，而不是让shell自己去做重定向。

一个值得养成的习惯：不确定会不会覆盖时，先看一眼再动手
bash
cat /etc/rancher/k3s/registries.yaml   # 覆盖前先看一眼现在是什么，心里有底

跑完这条心里有数之后，再执行那条sudo tee命令，就不用担心"万一叠加出一个乱七八糟的文件"了——这次确认过，默认tee就是覆盖，可以放心直接跑。


ziqiao@ziqiao-ASM100:/etc/rancher/k3s$ kubectl get pods -A 
NAMESPACE        NAME                                            READY   STATUS    RESTARTS       AGE
elastic-system   elastic-operator-0                              1/1     Running   7 (22h ago)    7d23h
ingress-demo     hello-demo-6cb95679d8-nkml9                     1/1     Running   39 (22h ago)   38d
ingress-nginx    ingress-nginx-controller-5cd9869bf8-p2rhh       1/1     Running   39 (38m ago)   38d
kube-system      coredns-5f5694d56b-wbq98                        1/1     Running   40 (22h ago)   38d
kube-system      local-path-provisioner-58d557dc48-b9qpl         1/1     Running   41 (22h ago)   38d
kube-system      metrics-server-7c86f97b8d-t9598                 1/1     Running   41 (22h ago)   38d
kube-system      svclb-ingress-nginx-controller-b1506ec7-zc79t   2/2     Running   78 (22h ago)   38d
toy-infra        kafka-ui-6d4b99c65b-pcq2v                       1/1     Running   9 (38m ago)    11d
toy-infra        my-es-es-default-0                              1/1     Running   7 (38m ago)    7d23h
toy-infra        my-kafka-dual-role-0                            1/1     Running   12 (22h ago)   14d
toy-infra        my-kafka-entity-operator-6d9c4cbfd-8fbjp        2/2     Running   41 (22h ago)   14d
toy-infra        my-kibana-kb-79b9d6f654-nplm8                   1/1     Running   7 (38m ago)    7d23h
toy-infra        strimzi-cluster-operator-5b4ff798cc-7bnk7       1/1     Running   13 (22h ago)   15d
ziqiao@ziqiao-ASM100:/etc/rancher/k3s$ kubectl run registry-test --rm -it --image=localhost:5000/frontend:202608291813-b836314 --restart=Never -n toy-system -- echo "pull ok"
pull ok
All commands and output from this session will be recorded in container logs, including credentials and sensitive information passed through the command prompt.
If you don't see a command prompt, try pressing enter.
Session ended, resume using 'kubectl attach registry-test -c registry-test -n toy-system -i -t' command
pod "registry-test" deleted from toy-system namespace

看起来没问题，而且restart k3s似乎没有影响pod，一直都是running状态

pull ok证明镜像拉取链路完全打通，registries.yaml配置生效了。

顺带回应你观察到的"重启k3s没有影响Pod"这个现象——这个观察很敏锐，值得解释一下

这不是巧合，而是K8s架构设计上刻意做到的"控制面"和"数据面"分离：

systemctl restart k3s重启的是"控制面"——也就是API Server、controller-manager、scheduler这些"负责决策、记录状态"的组件，它们本身不直接负责"让容器进程持续跑着"这件事
真正让容器进程活着的，是每个node上的containerd(容器运行时)——这是一个跟k3s主进程相对独立的组件，Pod里的容器进程，是containerd直接管理的操作系统进程，跟k3s这个"大脑"重启与否，没有直接的生死绑定关系

所以systemctl restart k3s期间，实际发生的是："大脑"(API Server等)短暂失联了几秒到几十秒，但已经在跑的容器，没人去叫停它们，它们就继续按自己的节奏跑着，等"大脑"重新上线后，会重新跟containerd对上暗号，确认"喔，这些Pod确实还在跑，状态记录同步一下"，一切照旧——这也是K8s"声明式+去中心化执行"这套设计哲学的直接体现：控制面短暂中断，不代表已经在运行的工作负载会跟着一起停摆。

不过有个例外情况值得提一句：如果重启期间，恰好有些Pod被"驱逐"或需要"重新调度"(比如controller判断某个Pod该重建了)，这类需要"大脑做决策"的动作会被暂时搁置，等大脑恢复后才会继续处理——但对于"已经稳定运行、没有变化需求"的Pod，确实就像你观察到的这样，安然无恙。

现在registry问题彻底解决，可以正式进入Step7了。写5个服务的Deployment之前，最后确认一份清单——每个服务分别需要挂载哪些ConfigMap/Secret，按你之前的架构梳理一下：

服务	环境变量来源
gateway-service	gateway-config(ConfigMap) + jwt-secret(Secret)
policy-service	shared-config(ConfigMap) + mysql-credentials(Secret)
notification-service	shared-config(ConfigMap)
search-service	shared-config(ConfigMap) + es-credentials(Secret) + CA证书Volume(my-es-es-http-certs-public)
frontend	不需要(构建时已经把API地址写死进JS了)

确认这份清单没问题的话，我们从哪个服务开始写Deployment？建议顺序是先写没有额外依赖的notification-service练手，再写policy-service(涉及MySQL+Redis)，然后search-service(最复杂，涉及证书挂载)，最后gateway-service+Ingress，frontend+Ingress——这样从简单到复杂，遇到问题也方便逐步定位。你觉得这个顺序可以吗？


那我们从notification-service开始，逐行讲透，后面几个服务模式类似，会讲得更快。

notification-service的Deployment
bash
mkdir -p /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-system/notification-service
cd /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-system/notification-service

cat <<'EOF' > deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: notification-service
  namespace: toy-system
spec:
  replicas: 1
  selector:
    matchLabels:
      app: notification-service
  template:
    metadata:
      labels:
        app: notification-service
    spec:
      terminationGracePeriodSeconds: 35
      containers:
        - name: notification-service
          image: localhost:5000/notification-service:202608291813-b836314
          ports:
            - containerPort: 8082
          envFrom:
            - configMapRef:
                name: shared-config
          env:
            - name: SERVER_PORT
              value: "8082"
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8082
            initialDelaySeconds: 15
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8082
            initialDelaySeconds: 30
            periodSeconds: 20
EOF

kubectl apply -f deployment.yaml
逐段讲解
apiVersion: apps/v1 / kind: Deployment

跟我们之前接触的Kafka、Elasticsearch这些CRD不同，Deployment是K8s原生内置类型(不需要装任何Operator)，专门管理"无状态"应用——这也是我们之前在Kafka那次专门对比过的：Kafka这种有状态服务要用StatefulSet，而这4个业务服务都是无状态的普通Java应用(重启后不需要保留任何本地数据，状态都存在MySQL/Redis/Kafka里)，所以用最基础的Deployment就够了。

spec.replicas: 1

"我要几个这个Pod的副本"——现在homelab单节点场景先给1个，以后要做高可用/负载均衡，把这个数字改大即可，K8s会自动帮你在多个Pod之间做负载均衡(前提是有对应Service)。

spec.selector.matchLabels 和 template.metadata.labels——这两处必须完全一致

这是Deployment管理Pod的核心机制：Deployment并不直接"拥有"Pod，而是通过"这个label长这样的Pod，都归我管"这种间接方式去认领——selector.matchLabels声明"我要去找app: notification-service这个标签的Pod"，template.metadata.labels声明"我创建出来的Pod，贴的标签就是app: notification-service"，两者对上号，Deployment才能正确追踪、管理它创建出来的Pod(比如判断"这个Pod挂了，我该不该按replicas数量重新拉起一个")。

spec.template——这一整块，才是"真正描述Pod长什么样"的地方

Deployment本身只是"Pod的管理外壳"，template这个字段底下的内容，本质上就是一份完整的Pod定义(跟我们之前直接kubectl run临时Pod时的结构是同一套schema)，Deployment会照着这份模板，按replicas数量去创建对应数量的实际Pod。

terminationGracePeriodSeconds: 35——呼应我们之前聊的优雅关闭

还记得Spring Boot那边配的timeout-per-shutdown-phase: 30s吗——这里特意设成35秒(比30秒略长)，是给出一点缓冲余量：K8s发SIGTERM后，等这么久才会补一记SIGKILL；如果两边设成完全相等的30秒，可能会因为几毫秒的时间差，让Spring Boot还差一点点没收尾完就被强杀，留5秒余量更稳妥。

containers[0].image——这里我先临时填了你之前notification-service推送成功时的那个tag

这里需要你确认一下——如果你后来又重新build过、有更新的tag，把这里换成最新的那个，或者告诉我你想固定用哪个tag。

envFrom.configMapRef.name: shared-config

envFrom和之前ConfigMap里聊的"具体某个key"不一样——这是"整个搬过来"的写法：shared-config这个ConfigMap里的所有key(KAFKA_BOOTSTRAP_SERVERS、ELASTICSEARCH_URIS等)，会全部变成这个容器的环境变量，不需要一个一个手动列出来对应关系。notification-service虽然用不上SPRING_DATASOURCE_URL这类跟它无关的值，但多余的环境变量注入进去不会有副作用，图省事这样写完全没问题。

env这个字段——跟envFrom是互补关系，用来单独覆盖/追加某一个值

这里手动加了SERVER_PORT，是因为这个值是这个服务独有的(不属于共享配置)，之前我们讨论过，application.yml里server.port默认值本身就是8082，所以这一行技术上可以省略——这里保留是为了显式、方便以后一眼看出这个服务实际监听端口是多少，属于"多写一行、换来可读性"的取舍，你也可以选择删掉它。

readinessProbe 和 livenessProbe——之前没细讲过的新概念，值得展开

这是K8s检查"Pod是否健康"的两种探针，作用不同：

readinessProbe(就绪探针)——判断"这个Pod现在能不能接收流量"，如果探测失败，K8s会把这个Pod从Service的负载均衡列表里暂时摘除(但不会重启它)，等探测恢复正常再重新加回去。适合"应用还在启动中/暂时依赖的下游服务连不上，先别给它派活"这种场景。
livenessProbe(存活探针)——判断"这个Pod是不是已经僵死、需要重启"，如果探测持续失败，K8s会直接杀掉这个容器、重新拉起一个新的。适合"进程卡死了、内存泄漏到没法响应了"这种需要"重启大法"的场景。

这里探测的路径/actuator/health，正是你之前application.yml里那段management.endpoints.web.exposure.include: health,info配置暴露出来的Spring Boot Actuator健康检查接口——这也是为什么当初那份配置"顺手"就配上了，现在正好派上用场。initialDelaySeconds是"容器启动后，先等多久才开始第一次探测"(给应用留出启动时间，避免启动过程中被误判成"活不了"而被反复重启)。

先跑这份，kubectl apply之后看看Pod状态：

bash
kubectl get pods -n toy-system
kubectl logs -f deployment/notification-service -n toy-system

把结果贴给我，确认notification-service顺利跑起来(能正常连上Kafka)后，我们再写ClusterIP Service(内部服务不需要Ingress)，然后进入policy-service。


k8s有哪几种探针？

除了刚才讲的readinessProbe和livenessProbe，K8s其实一共有三种探针，还有一种叫startupProbe，专门解决前两种探针在"启动缓慢的应用"上会遇到的一个实际矛盾，值得一起讲清楚。

三种探针分工
探针	回答的问题	失败后K8s的动作
startupProbe(启动探针)	"这个应用启动过程完成了吗？"	只要探测持续失败，K8s会一直耐心等待，暂缓执行readiness/liveness探测，直到startupProbe成功一次为止；如果一直失败到超过设定的重试上限，才会重启容器
readinessProbe(就绪探针)	"现在这一刻，这个Pod能不能正常处理请求？"	探测失败：从Service负载均衡列表里暂时摘除，不重启，等恢复了再加回去
livenessProbe(存活探针)	"这个进程是不是已经僵死、卡住动不了了？"	探测失败：直接杀掉重启这个容器
为什么需要专门加一个startupProbe——它解决的具体矛盾

假设一个应用正常启动要花90秒(比如要连接好几个数据库、预热缓存)，如果只配了livenessProbe(比如initialDelaySeconds: 30)，会发生这种尴尬情况：应用其实还在正常启动过程中(第35秒，还没启动完)，但livenessProbe已经开始探测了，探测失败，K8s误判"这个容器僵死了"，把它杀掉重启——重启后又要重新走一遍90秒的启动流程，如果每次都被这个"没耐心"的livenessProbe打断，这个Pod可能永远都启动不起来，陷入死循环。

startupProbe的解法是：先让startupProbe用"很宽松的耐心"去等应用启动完成，在这期间livenessProbe和readinessProbe完全不会被触发探测，只有startupProbe第一次成功之后，才会切换到正常的readiness/liveness两套逻辑接管——这样就不会出现"应用还在正常启动、却被当成僵死进程误杀"的情况。

三者的典型时间线，串起来看
容器启动
   │
   ▼
startupProbe开始探测(比如每10秒探一次，最多容忍30次失败=300秒)
   │  ← 这期间readiness/liveness完全不生效，K8s只是安静等着
   ▼
startupProbe第一次探测成功 → 应用被判定"已经启动完毕"
   │
   ▼
从此切换到readinessProbe + livenessProbe正常接管(通常各自的探测间隔更短、更敏感)
对我们这4个Java服务来说，需不需要加startupProbe

Spring Boot应用的启动时间通常在几秒到十几秒之间(不像有些Java应用有复杂的预热逻辑要跑几分钟)，我们之前给notification-service设的initialDelaySeconds: 15(readiness)和30(liveness)，这个宽限期本身已经足够覆盖正常启动耗时，目前这个规模不加startupProbe也没问题。但如果以后你发现某个服务启动特别慢(比如加载了很重的缓存/模型)，或者发现Pod总是"刚启动就被重启、陷入循环"，startupProbe就是专门为这种场景设计的解药，到时候可以加上，现在先保持简单。


ziqiao@ziqiao-ASM100:~/Documents/homelab-toy-system/infra/k8s/toy-system/notification-service$ kubectl get pods -n toy-system
NAME                                   READY   STATUS    RESTARTS   AGE
notification-service-5b6759fdb-nhmfh   0/1     Running   0          17s
ziqiao@ziqiao-ASM100:~/Documents/homelab-toy-system/infra/k8s/toy-system/notification-service$ kubectl logs -f deployment/notification-service -n toy-system

  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

 :: Spring Boot ::                (v3.3.5)

2026-09-02T21:42:46.721Z  INFO 1 --- [notification-service] [           main] c.t.n.NotificationServiceApplication     : Starting NotificationServiceApplication v0.1.0 using Java 21.0.12 with PID 1 (/app/app.jar started by root in /app)
2026-09-02T21:42:46.726Z DEBUG 1 --- [notification-service] [           main] c.t.n.NotificationServiceApplication     : Running with Spring Boot v3.3.5, Spring v6.1.14
2026-09-02T21:42:46.727Z  INFO 1 --- [notification-service] [           main] c.t.n.NotificationServiceApplication     : No active profile set, falling back to 1 default profile: "default"
2026-09-02T21:42:48.121Z  INFO 1 --- [notification-service] [           main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat initialized with port 8082 (http)
2026-09-02T21:42:48.136Z  INFO 1 --- [notification-service] [           main] o.apache.catalina.core.StandardService   : Starting service [Tomcat]
2026-09-02T21:42:48.136Z  INFO 1 --- [notification-service] [           main] o.apache.catalina.core.StandardEngine    : Starting Servlet engine: [Apache Tomcat/10.1.31]
2026-09-02T21:42:48.185Z  INFO 1 --- [notification-service] [           main] o.a.c.c.C.[Tomcat].[localhost].[/]       : Initializing Spring embedded WebApplicationContext
2026-09-02T21:42:48.186Z  INFO 1 --- [notification-service] [           main] w.s.c.ServletWebServerApplicationContext : Root WebApplicationContext: initialization completed in 1380 ms
2026-09-02T21:42:48.952Z  INFO 1 --- [notification-service] [           main] o.s.b.a.e.web.EndpointLinksResolver      : Exposing 2 endpoints beneath base path '/actuator'
2026-09-02T21:42:49.049Z  INFO 1 --- [notification-service] [           main] o.a.k.clients.admin.AdminClientConfig    : AdminClientConfig values: 
        auto.include.jmx.reporter = true
        bootstrap.controllers = []
        bootstrap.servers = [my-kafka-kafka-bootstrap.toy-infra.svc.cluster.local:9092]
        client.dns.lookup = use_all_dns_ips
        client.id = notification-service-admin-0
        connections.max.idle.ms = 300000
        default.api.timeout.ms = 60000
        enable.metrics.push = true
        metadata.max.age.ms = 300000
        metric.reporters = []
        metrics.num.samples = 2
        metrics.recording.level = INFO
        metrics.sample.window.ms = 30000
        receive.buffer.bytes = 65536
        reconnect.backoff.max.ms = 1000
        reconnect.backoff.ms = 50
        request.timeout.ms = 30000
        retries = 2147483647
        retry.backoff.max.ms = 1000
        retry.backoff.ms = 100
        sasl.client.callback.handler.class = null
        sasl.jaas.config = null
        sasl.kerberos.kinit.cmd = /usr/bin/kinit
        sasl.kerberos.min.time.before.relogin = 60000
        sasl.kerberos.service.name = null
        sasl.kerberos.ticket.renew.jitter = 0.05
        sasl.kerberos.ticket.renew.window.factor = 0.8
        sasl.login.callback.handler.class = null
        sasl.login.class = null
        sasl.login.connect.timeout.ms = null
        sasl.login.read.timeout.ms = null
        sasl.login.refresh.buffer.seconds = 300
        sasl.login.refresh.min.period.seconds = 60
        sasl.login.refresh.window.factor = 0.8
        sasl.login.refresh.window.jitter = 0.05
        sasl.login.retry.backoff.max.ms = 10000
        sasl.login.retry.backoff.ms = 100
        sasl.mechanism = GSSAPI
        sasl.oauthbearer.clock.skew.seconds = 30
        sasl.oauthbearer.expected.audience = null
        sasl.oauthbearer.expected.issuer = null
        sasl.oauthbearer.jwks.endpoint.refresh.ms = 3600000
        sasl.oauthbearer.jwks.endpoint.retry.backoff.max.ms = 10000
        sasl.oauthbearer.jwks.endpoint.retry.backoff.ms = 100
        sasl.oauthbearer.jwks.endpoint.url = null
        sasl.oauthbearer.scope.claim.name = scope
        sasl.oauthbearer.sub.claim.name = sub
        sasl.oauthbearer.token.endpoint.url = null
        security.protocol = PLAINTEXT
        security.providers = null
        send.buffer.bytes = 131072
        socket.connection.setup.timeout.max.ms = 30000
        socket.connection.setup.timeout.ms = 10000
        ssl.cipher.suites = null
        ssl.enabled.protocols = [TLSv1.2, TLSv1.3]
        ssl.endpoint.identification.algorithm = https
        ssl.engine.factory.class = null
        ssl.key.password = null
        ssl.keymanager.algorithm = SunX509
        ssl.keystore.certificate.chain = null
        ssl.keystore.key = null
        ssl.keystore.location = null
        ssl.keystore.password = null
        ssl.keystore.type = JKS
        ssl.protocol = TLSv1.3
        ssl.provider = null
        ssl.secure.random.implementation = null
        ssl.trustmanager.algorithm = PKIX
        ssl.truststore.certificates = null
        ssl.truststore.location = null
        ssl.truststore.password = null
        ssl.truststore.type = JKS

2026-09-02T21:42:49.260Z  INFO 1 --- [notification-service] [           main] o.a.kafka.common.utils.AppInfoParser     : Kafka version: 3.7.1
2026-09-02T21:42:49.261Z  INFO 1 --- [notification-service] [           main] o.a.kafka.common.utils.AppInfoParser     : Kafka commitId: e2494e6ffb89f828
2026-09-02T21:42:49.261Z  INFO 1 --- [notification-service] [           main] o.a.kafka.common.utils.AppInfoParser     : Kafka startTimeMs: 1788385369258
2026-09-02T21:42:49.774Z  INFO 1 --- [notification-service] [service-admin-0] o.a.kafka.common.utils.AppInfoParser     : App info kafka.admin.client for notification-service-admin-0 unregistered
2026-09-02T21:42:49.780Z  INFO 1 --- [notification-service] [service-admin-0] o.apache.kafka.common.metrics.Metrics    : Metrics scheduler closed
2026-09-02T21:42:49.780Z  INFO 1 --- [notification-service] [service-admin-0] o.apache.kafka.common.metrics.Metrics    : Closing reporter org.apache.kafka.common.metrics.JmxReporter
2026-09-02T21:42:49.781Z  INFO 1 --- [notification-service] [service-admin-0] o.apache.kafka.common.metrics.Metrics    : Metrics reporters closed
2026-09-02T21:42:49.823Z  INFO 1 --- [notification-service] [           main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat started on port 8082 (http) with context path '/'
2026-09-02T21:42:49.872Z  INFO 1 --- [notification-service] [           main] o.a.k.clients.consumer.ConsumerConfig    : ConsumerConfig values: 
        allow.auto.create.topics = true
        auto.commit.interval.ms = 5000
        auto.include.jmx.reporter = true
        auto.offset.reset = earliest
        bootstrap.servers = [my-kafka-kafka-bootstrap.toy-infra.svc.cluster.local:9092]
        check.crcs = true
        client.dns.lookup = use_all_dns_ips
        client.id = consumer-notification-service-1
        client.rack = 
        connections.max.idle.ms = 540000
        default.api.timeout.ms = 60000
        enable.auto.commit = false
        enable.metrics.push = true
        exclude.internal.topics = true
        fetch.max.bytes = 52428800
        fetch.max.wait.ms = 500
        fetch.min.bytes = 1
        group.id = notification-service
        group.instance.id = null
        group.protocol = classic
        group.remote.assignor = null
        heartbeat.interval.ms = 3000
        interceptor.classes = []
        internal.leave.group.on.close = true
        internal.throw.on.fetch.stable.offset.unsupported = false
        isolation.level = read_uncommitted
        key.deserializer = class org.apache.kafka.common.serialization.StringDeserializer
        max.partition.fetch.bytes = 1048576
        max.poll.interval.ms = 300000
        max.poll.records = 500
        metadata.max.age.ms = 300000
        metric.reporters = []
        metrics.num.samples = 2
        metrics.recording.level = INFO
        metrics.sample.window.ms = 30000
        partition.assignment.strategy = [class org.apache.kafka.clients.consumer.RangeAssignor, class org.apache.kafka.clients.consumer.CooperativeStickyAssignor]
        receive.buffer.bytes = 65536
        reconnect.backoff.max.ms = 1000
        reconnect.backoff.ms = 50
        request.timeout.ms = 30000
        retry.backoff.max.ms = 1000
        retry.backoff.ms = 100
        sasl.client.callback.handler.class = null
        sasl.jaas.config = null
        sasl.kerberos.kinit.cmd = /usr/bin/kinit
        sasl.kerberos.min.time.before.relogin = 60000
        sasl.kerberos.service.name = null
        sasl.kerberos.ticket.renew.jitter = 0.05
        sasl.kerberos.ticket.renew.window.factor = 0.8
        sasl.login.callback.handler.class = null
        sasl.login.class = null
        sasl.login.connect.timeout.ms = null
        sasl.login.read.timeout.ms = null
        sasl.login.refresh.buffer.seconds = 300
        sasl.login.refresh.min.period.seconds = 60
        sasl.login.refresh.window.factor = 0.8
        sasl.login.refresh.window.jitter = 0.05
        sasl.login.retry.backoff.max.ms = 10000
        sasl.login.retry.backoff.ms = 100
        sasl.mechanism = GSSAPI
        sasl.oauthbearer.clock.skew.seconds = 30
        sasl.oauthbearer.expected.audience = null
        sasl.oauthbearer.expected.issuer = null
        sasl.oauthbearer.jwks.endpoint.refresh.ms = 3600000
        sasl.oauthbearer.jwks.endpoint.retry.backoff.max.ms = 10000
        sasl.oauthbearer.jwks.endpoint.retry.backoff.ms = 100
        sasl.oauthbearer.jwks.endpoint.url = null
        sasl.oauthbearer.scope.claim.name = scope
        sasl.oauthbearer.sub.claim.name = sub
        sasl.oauthbearer.token.endpoint.url = null
        security.protocol = PLAINTEXT
        security.providers = null
        send.buffer.bytes = 131072
        session.timeout.ms = 45000
        socket.connection.setup.timeout.max.ms = 30000
        socket.connection.setup.timeout.ms = 10000
        ssl.cipher.suites = null
        ssl.enabled.protocols = [TLSv1.2, TLSv1.3]
        ssl.endpoint.identification.algorithm = https
        ssl.engine.factory.class = null
        ssl.key.password = null
        ssl.keymanager.algorithm = SunX509
        ssl.keystore.certificate.chain = null
        ssl.keystore.key = null
        ssl.keystore.location = null
        ssl.keystore.password = null
        ssl.keystore.type = JKS
        ssl.protocol = TLSv1.3
        ssl.provider = null
        ssl.secure.random.implementation = null
        ssl.trustmanager.algorithm = PKIX
        ssl.truststore.certificates = null
        ssl.truststore.location = null
        ssl.truststore.password = null
        ssl.truststore.type = JKS
        value.deserializer = class org.springframework.kafka.support.serializer.ErrorHandlingDeserializer

2026-09-02T21:42:49.911Z  INFO 1 --- [notification-service] [           main] o.a.k.c.t.i.KafkaMetricsCollector        : initializing Kafka metrics collector
2026-09-02T21:42:49.964Z  INFO 1 --- [notification-service] [           main] o.a.kafka.common.utils.AppInfoParser     : Kafka version: 3.7.1
2026-09-02T21:42:49.964Z  INFO 1 --- [notification-service] [           main] o.a.kafka.common.utils.AppInfoParser     : Kafka commitId: e2494e6ffb89f828
2026-09-02T21:42:49.964Z  INFO 1 --- [notification-service] [           main] o.a.kafka.common.utils.AppInfoParser     : Kafka startTimeMs: 1788385369964
2026-09-02T21:42:49.978Z  INFO 1 --- [notification-service] [           main] o.a.k.c.c.internals.LegacyKafkaConsumer  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Subscribed to topic(s): policy-events
2026-09-02T21:42:50.005Z  INFO 1 --- [notification-service] [           main] c.t.n.NotificationServiceApplication     : Started NotificationServiceApplication in 3.85 seconds (process running for 4.442)
2026-09-02T21:42:50.009Z  WARN 1 --- [notification-service] [ntainer#0-0-C-1] org.apache.kafka.clients.NetworkClient   : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Error while fetching metadata with correlation id 2 : {policy-events=UNKNOWN_TOPIC_OR_PARTITION}
2026-09-02T21:42:50.010Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] org.apache.kafka.clients.Metadata        : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Cluster ID: RVsvRSyNQ5WTguJvgWWDgQ
2026-09-02T21:42:50.138Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Discovered group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.141Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] (Re-)joining group
2026-09-02T21:42:50.166Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null) is unavailable or invalid due to cause: error response NOT_COORDINATOR. isDisconnected: false. Rediscovery will be attempted.
2026-09-02T21:42:50.167Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Requesting disconnect from last known coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.167Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] JoinGroup failed: This is not the correct coordinator. Marking coordinator unknown. Sent generation was Generation{generationId=-1, memberId='', protocol='null'}
2026-09-02T21:42:50.167Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] org.apache.kafka.clients.NetworkClient   : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Client requested disconnect from node 2147483647
2026-09-02T21:42:50.172Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Discovered group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.173Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null) is unavailable or invalid due to cause: coordinator unavailable. isDisconnected: false. Rediscovery will be attempted.
2026-09-02T21:42:50.173Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Requesting disconnect from last known coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.291Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Discovered group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.292Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Request joining group due to: rebalance failed due to 'This is not the correct coordinator.' (NotCoordinatorException)
2026-09-02T21:42:50.293Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] (Re-)joining group
2026-09-02T21:42:50.295Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null) is unavailable or invalid due to cause: error response NOT_COORDINATOR. isDisconnected: false. Rediscovery will be attempted.
2026-09-02T21:42:50.295Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Requesting disconnect from last known coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.296Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] JoinGroup failed: This is not the correct coordinator. Marking coordinator unknown. Sent generation was Generation{generationId=-1, memberId='', protocol='null'}
2026-09-02T21:42:50.296Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] org.apache.kafka.clients.NetworkClient   : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Client requested disconnect from node 2147483647
2026-09-02T21:42:50.299Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Discovered group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.299Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null) is unavailable or invalid due to cause: coordinator unavailable. isDisconnected: false. Rediscovery will be attempted.
2026-09-02T21:42:50.299Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Requesting disconnect from last known coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.420Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Discovered group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.421Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Request joining group due to: rebalance failed due to 'This is not the correct coordinator.' (NotCoordinatorException)
2026-09-02T21:42:50.421Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] (Re-)joining group
2026-09-02T21:42:50.424Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null) is unavailable or invalid due to cause: error response NOT_COORDINATOR. isDisconnected: false. Rediscovery will be attempted.
2026-09-02T21:42:50.424Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Requesting disconnect from last known coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.424Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] JoinGroup failed: This is not the correct coordinator. Marking coordinator unknown. Sent generation was Generation{generationId=-1, memberId='', protocol='null'}
2026-09-02T21:42:50.424Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] org.apache.kafka.clients.NetworkClient   : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Client requested disconnect from node 2147483647
2026-09-02T21:42:50.427Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Discovered group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.428Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null) is unavailable or invalid due to cause: coordinator unavailable. isDisconnected: false. Rediscovery will be attempted.
2026-09-02T21:42:50.428Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Requesting disconnect from last known coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.533Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Discovered group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.534Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Request joining group due to: rebalance failed due to 'This is not the correct coordinator.' (NotCoordinatorException)
2026-09-02T21:42:50.535Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] (Re-)joining group
2026-09-02T21:42:50.537Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null) is unavailable or invalid due to cause: error response NOT_COORDINATOR. isDisconnected: false. Rediscovery will be attempted.
2026-09-02T21:42:50.537Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Requesting disconnect from last known coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.537Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] JoinGroup failed: This is not the correct coordinator. Marking coordinator unknown. Sent generation was Generation{generationId=-1, memberId='', protocol='null'}
2026-09-02T21:42:50.538Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] org.apache.kafka.clients.NetworkClient   : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Client requested disconnect from node 2147483647
2026-09-02T21:42:50.540Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Discovered group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.540Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null) is unavailable or invalid due to cause: coordinator unavailable. isDisconnected: false. Rediscovery will be attempted.
2026-09-02T21:42:50.540Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Requesting disconnect from last known coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.660Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Discovered group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.661Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Request joining group due to: rebalance failed due to 'This is not the correct coordinator.' (NotCoordinatorException)
2026-09-02T21:42:50.662Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] (Re-)joining group
2026-09-02T21:42:50.667Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null) is unavailable or invalid due to cause: error response NOT_COORDINATOR. isDisconnected: false. Rediscovery will be attempted.
2026-09-02T21:42:50.667Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Requesting disconnect from last known coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.667Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] JoinGroup failed: This is not the correct coordinator. Marking coordinator unknown. Sent generation was Generation{generationId=-1, memberId='', protocol='null'}
2026-09-02T21:42:50.667Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] org.apache.kafka.clients.NetworkClient   : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Client requested disconnect from node 2147483647
2026-09-02T21:42:50.670Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Discovered group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.670Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null) is unavailable or invalid due to cause: coordinator unavailable. isDisconnected: false. Rediscovery will be attempted.
2026-09-02T21:42:50.670Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Requesting disconnect from last known coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.791Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Discovered group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.792Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Request joining group due to: rebalance failed due to 'This is not the correct coordinator.' (NotCoordinatorException)
2026-09-02T21:42:50.793Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] (Re-)joining group
2026-09-02T21:42:50.795Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null) is unavailable or invalid due to cause: error response NOT_COORDINATOR. isDisconnected: false. Rediscovery will be attempted.
2026-09-02T21:42:50.796Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Requesting disconnect from last known coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.796Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] JoinGroup failed: This is not the correct coordinator. Marking coordinator unknown. Sent generation was Generation{generationId=-1, memberId='', protocol='null'}
2026-09-02T21:42:50.796Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] org.apache.kafka.clients.NetworkClient   : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Client requested disconnect from node 2147483647
2026-09-02T21:42:50.798Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Discovered group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.798Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null) is unavailable or invalid due to cause: coordinator unavailable. isDisconnected: false. Rediscovery will be attempted.
2026-09-02T21:42:50.798Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Requesting disconnect from last known coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.881Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Discovered group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.882Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Request joining group due to: rebalance failed due to 'This is not the correct coordinator.' (NotCoordinatorException)
2026-09-02T21:42:50.882Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] (Re-)joining group
2026-09-02T21:42:50.885Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null) is unavailable or invalid due to cause: error response NOT_COORDINATOR. isDisconnected: false. Rediscovery will be attempted.
2026-09-02T21:42:50.885Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Requesting disconnect from last known coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.885Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] JoinGroup failed: This is not the correct coordinator. Marking coordinator unknown. Sent generation was Generation{generationId=-1, memberId='', protocol='null'}
2026-09-02T21:42:50.885Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] org.apache.kafka.clients.NetworkClient   : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Client requested disconnect from node 2147483647
2026-09-02T21:42:50.887Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Discovered group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.888Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null) is unavailable or invalid due to cause: coordinator unavailable. isDisconnected: false. Rediscovery will be attempted.
2026-09-02T21:42:50.888Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Requesting disconnect from last known coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.973Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Discovered group coordinator my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 2147483647 rack: null)
2026-09-02T21:42:50.974Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Request joining group due to: rebalance failed due to 'This is not the correct coordinator.' (NotCoordinatorException)
2026-09-02T21:42:50.974Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] (Re-)joining group
2026-09-02T21:42:50.983Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Request joining group due to: need to re-join with the given member-id: consumer-notification-service-1-6fbb3988-274b-4a9b-92ef-625df1c07cd3
2026-09-02T21:42:50.983Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] (Re-)joining group
2026-09-02T21:42:54.006Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Successfully joined group with generation Generation{generationId=1, memberId='consumer-notification-service-1-6fbb3988-274b-4a9b-92ef-625df1c07cd3', protocol='range'}
2026-09-02T21:42:54.019Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Finished assignment for group at generation 1: {consumer-notification-service-1-6fbb3988-274b-4a9b-92ef-625df1c07cd3=Assignment(partitions=[policy-events-0])}
2026-09-02T21:42:54.033Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Successfully synced group in generation Generation{generationId=1, memberId='consumer-notification-service-1-6fbb3988-274b-4a9b-92ef-625df1c07cd3', protocol='range'}
2026-09-02T21:42:54.034Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Notifying assignor about the new Assignment(partitions=[policy-events-0])
2026-09-02T21:42:54.038Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] k.c.c.i.ConsumerRebalanceListenerInvoker : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Adding newly assigned partitions: policy-events-0
2026-09-02T21:42:54.056Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator  : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Found no committed offset for partition policy-events-0
2026-09-02T21:42:54.066Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.a.k.c.c.internals.SubscriptionState    : [Consumer clientId=consumer-notification-service-1, groupId=notification-service] Resetting offset for partition policy-events-0 to position FetchPosition{offset=0, offsetEpoch=Optional.empty, currentLeader=LeaderAndEpoch{leader=Optional[my-kafka-dual-role-0.my-kafka-kafka-brokers.toy-infra.svc:9092 (id: 0 rack: null)], epoch=0}}.
2026-09-02T21:42:54.067Z  INFO 1 --- [notification-service] [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : notification-service: partitions assigned: [policy-events-0]
2026-09-02T21:43:06.199Z  INFO 1 --- [notification-service] [nio-8082-exec-1] o.a.c.c.C.[Tomcat].[localhost].[/]       : Initializing Spring DispatcherServlet 'dispatcherServlet'
2026-09-02T21:43:06.200Z  INFO 1 --- [notification-service] [nio-8082-exec-1] o.s.web.servlet.DispatcherServlet        : Initializing Servlet 'dispatcherServlet'
2026-09-02T21:43:06.201Z  INFO 1 --- [notification-service] [nio-8082-exec-1] o.s.web.servlet.DispatcherServlet        : Completed initialization in 1 ms
^Cziqiao@ziqiao-ASM100:~/Documents/homelab-toy-system/infra/k8s/toy-system/notification-service$ kubectl get pods -n toy-system
NAME                                   READY   STATUS    RESTARTS   AGE
notification-service-5b6759fdb-nhmfh   1/1     Running   0          2m19s


从0/1到1/1，READY变绿——notification-service正式跑起来了。这份日志其实是个很好的实战案例，正好把我们之前聊过的好几个知识点串了起来，值得回顾确认几处细节。

一、READY 0/1 → 1/1这个过程，正好印证了readinessProbe的作用

注意日志时间线：

21:42:50.005 — Spring Boot自己报告"Started ... in 3.85 seconds"，应用本身3.85秒就跑起来了
但kubectl get pods一开始显示0/1，一直到大约16秒后才变成1/1

这个"应用早就启动完了，但K8s还没判定它READY"的时间差，正是readinessProbe在发挥作用——回顾我们的配置initialDelaySeconds: 15，K8s要等15秒后才第一次去探测/actuator/health，探测成功才会把READY标记打上。这也解释了为什么日志最后你能看到Initializing Spring DispatcherServlet这条(在21:43:06，也就是启动后16秒左右)——那正是readinessProbe第一次真正发起HTTP请求，触发了Spring MVC对/actuator/health这个端点的懒加载初始化。

二、日志里那一长串NOT_COORDINATOR报错——不是真的出错，是Kafka的正常"探路"过程

这部分很容易让人一开始心里一紧，但其实是无害的：Kafka Consumer第一次启动时，需要先找到"这个消费者组的协调者(coordinator)是谁"，在单broker环境下，这个协调发现过程会经历几轮"猜错了、重试"的正常握手，NotCoordinatorException只是"这一轮问错了人，换个方式再问一次"，最终在21:42:54成功了(Successfully joined group)——从第一次尝试到成功，总共才4秒左右，这是完全正常的启动噪音，不是故障，以后看到类似日志不用紧张。

三、kafbat UI上确认的两个topic——猜测应该是policy-events和它的死信topic

回顾你之前notification-service的application.yml注释里提到的"死信Topic容错"机制(DeadLetterPublishingRecoverer)，这两个topic大概率是：

policy-events——正常消费的业务topic(日志里Subscribed to topic(s): policy-events能对上)
policy-events.DLT——死信topic，专门接收反序列化/处理失败、重试用尽的消息

这也是一个很好的验证点——如果以后想测试"死信机制真的生效"，可以试着往policy-events塞一条格式错误的消息，看它会不会最终被转发进.DLT那个topic。

notification-service这一关顺利通过，接下来按之前定的顺序，写它的ClusterIP Service(内部服务，不需要Ingress)，然后进入policy-service——这个会涉及MySQL+Redis的连接测试，也是我们Secret真正派上用场的地方。

notification-service的Service(纯内部，简单)
bash
cat <<'EOF' > service.yaml
apiVersion: v1
kind: Service
metadata:
  name: notification-service
  namespace: toy-system
spec:
  selector:
    app: notification-service
  ports:
    - port: 8082
      targetPort: 8082
EOF

kubectl apply -f service.yaml

这次带了selector，跟我们之前给MySQL/Redis写的"无selector Service"正好相反——因为notification-service是K8s自己管理的Pod(带app: notification-service这个label)，K8s能自动帮它找到对应的Pod，不需要我们手动写EndpointSlice，这是最普通、最标准的Service用法。

确认建好后，我们开始policy-service。




Service建好了，notification-service这条线彻底完工。开始policy-service，这个是目前最复杂的一个——涉及MySQL、Redis、Kafka三个依赖，还有Liquibase在启动时跑数据库迁移，需要多留意几个细节。

policy-service的Deployment
bash
mkdir -p /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-system/policy-service
cd /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-system/policy-service

cat <<'EOF' > deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: policy-service
  namespace: toy-system
spec:
  replicas: 1
  selector:
    matchLabels:
      app: policy-service
  template:
    metadata:
      labels:
        app: policy-service
    spec:
      terminationGracePeriodSeconds: 35
      containers:
        - name: policy-service
          image: localhost:5000/policy-service:<换成你实际的tag>
          ports:
            - containerPort: 8081
          envFrom:
            - configMapRef:
                name: shared-config
            - secretRef:
                name: mysql-credentials
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8081
            initialDelaySeconds: 20
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8081
            initialDelaySeconds: 40
            periodSeconds: 20
EOF

kubectl apply -f deployment.yaml
跟notification-service相比，这里有几处值得注意的差异
envFrom同时挂了ConfigMap和Secret
yaml
envFrom:
  - configMapRef:
      name: shared-config
  - secretRef:
      name: mysql-credentials

envFrom这个字段本身可以接受一个列表，不局限于一个来源——shared-config提供SPRING_DATASOURCE_URL、KAFKA_BOOTSTRAP_SERVERS这类地址信息，mysql-credentials这个Secret提供SPRING_DATASOURCE_USERNAME/PASSWORD这两个敏感值，两者合并后，容器里最终看到的环境变量，是这两个来源叠加的结果——不需要你手动把Secret里的内容再抄一遍到ConfigMap或反过来，各自管各自的。

initialDelaySeconds调得比notification-service更宽松(20/40 vs 15/30)

回顾一下policy-service的application.yml——它比notification-service多了Liquibase数据库迁移这一步(spring.liquibase.change-log那段配置)，这一步会在应用启动过程中同步执行，跑完才会继续往下走——虽然平时可能很快，但比"只是连个Kafka"要多花一点时间，给探针多留点缓冲，避免启动稍微慢一点点就被误判。

一个需要你确认、可能影响启动结果的点：MySQL里目前有没有已经手动建过的表

回顾你很早之前那次SHOW DATABASES验证——当时toy_policy_db这个库已经存在，但没有验证过里面有没有表。Liquibase启动时会做这几件事：检查DATABASECHANGELOG这张记录表存不存在，不存在就自己建，然后按db.changelog-master.xml里定义的顺序，把还没执行过的迁移脚本一个个跑一遍(建policy_0~policy_3这类分片表)——这个过程完全自动，不需要你手动干预，但值得留意日志里Liquibase相关的输出，确认它真的顺利跑完了迁移，而不是卡住或报错。

Apply之后，重点看这几处日志
bash
kubectl apply -f deployment.yaml
kubectl logs -f deployment/policy-service -n toy-system

重点关注的几个信号(对应它要连的三个依赖)：

Liquibase相关日志——有没有类似Successfully acquired change log lock、Update summary这类字样，代表数据库迁移跑通了
ShardingSphere相关日志——你之前application.yml注释里提到DataSource是通过ShardingSphereConfig手动构造的，启动日志里应该能看到它加载分片规则的输出
Redis连接——有没有报连接失败(如果redis这个Service名字或端口有误，这里会最先暴露出来)
Kafka Producer初始化——类似notification-service那种ProducerConfig values的输出

先把镜像tag换成你实际推送成功的那个(记得核对一下policy-service当时打的tag)，跑起来后把日志贴给我，我们逐段核对这几个依赖是否都连通成功。