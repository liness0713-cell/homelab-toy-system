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
kubectl apply -f policy-configmap.yaml

（notification/search两个服务目前application.yml里除了共享值，没有额外的独有配置项，暂时不需要单独ConfigMap，等以后有需要再加）

Secret：敏感凭证
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