Java项目是在哪里看的，pom.xml好像并没有

VSCode里看到的"JRE目录"，其实是VSCode插件自己配置的、用来运行/调试代码的JDK路径，跟"这个项目声明自己需要哪个Java版本"是两回事——前者是"你的编辑器现在用哪个JDK跑代码"，后者是"这个项目本身、写进构建配置里、要求必须用哪个版本编译"，两者理论上可能对不上（比如你电脑装了Java 21，但项目其实写的是要求Java 17，只是VSCode没报错而已）。

正规渠道：项目自己在哪里"声明"要用的Java版本

Maven项目里，Java版本通常写在pom.xml的这几个地方之一（你说没看到，可能没细找，或者项目继承了父pom，版本声明在父pom里）：

方式一：<properties>里直接写

xml
<properties>
    <java.version>21</java.version>
</properties>

方式二：maven-compiler-plugin里指定

xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <release>21</release>
    </configuration>
</plugin>

方式三：继承Spring Boot的父pom(你这几个项目大概率是这种，最常见)

xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.4.0</version>
</parent>
<properties>
    <java.version>21</java.version>
</properties>

Spring Boot的父pom会读取这个<java.version>属性，自动帮你把编译器版本设成对应值——这也是你四个pom.xml里如果只有<java.version>21</java.version>这一行、没有单独的compiler-plugin配置，也完全正常，是Spring Boot项目最常见的写法，不是"没写"，只是藏得比较隐蔽，容易被忽略。

如果pom.xml翻遍了真的什么都没找到，还有几个"实测验证"的办法

方式一：直接问Maven自己用的是哪个JDK

bash
mvn -v

输出里会显示当前Maven调用的JDK版本——这是"实际会拿去编译的版本"，比翻pom.xml更直接可靠。

方式二：编译完之后，反查已经生成的class文件

bash
javap -verbose target/classes/com/toysystem/xxx/YourClass.class | grep "major version"

每个Java版本编译出来的.class文件都带一个"major version"号(比如Java 21对应65)，这是最"实锤"的验证方式——不管pom.xml写了什么、VSCode配了什么，编译出来的字节码版本才是唯一的事实。

建议：既然你已经确认是Java 21，Dockerfile先按这个走，但值得花30秒交叉验证一下

用上面mvn -v这条命令跑一下(在你本地能跑mvn命令的项目目录里)，确认输出的JDK版本确实是21——如果对得上，我们之前定的eclipse-temurin:21-jre就是对的，不用改；如果发现其实是别的版本，趁现在改还来得及，比镜像都build完了才发现版本不对省事很多。

ziqiao@ziqiao-ASM100:~/Documents/homelab-toy-system$ mvn -v
Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)
Maven home: /home/ziqiao/.sdkman/candidates/maven/current
Java version: 21.0.5, vendor: Eclipse Adoptium, runtime: /home/ziqiao/.sdkman/candidates/java/21.0.5-tem
Default locale: en_US, platform encoding: UTF-8
OS name: "linux", version: "7.0.0-29-generic", arch: "amd64", family: "unix"




## DockerFile制作思路
架构梳理得很清楚，跟你说的完全对得上——frontend和gateway-service走Ingress对外，policy/notification/search三个纯内部服务只建ClusterIP Service，不建Ingress。这个原则跟我们之前给kafka-ui/kibana建Ingress是同一套逻辑，只是这次网关层通过Path路由(/api/policies/**等)自己去分发内部流量，架构更接近生产实践。

现在缺的东西：这4个Java服务和1个前端项目都还没有Dockerfile

写Dockerfile之前，有几个信息我看application.yml看不出来，需要跟你确认(不能瞎猜，尤其Java版本会直接决定用哪个基础镜像，猜错了跟之前Bitnami/Strimzi版本号踩坑是一类问题)：

Thought for 6s

中文：

统一是Maven+Java 21，那4个服务可以共用同一份Dockerfile模板，只是端口和最终jar包名不同。写之前，按我们已经养成的习惯，先确认一下推荐的Java 21基础镜像现状（避免又是一次版本/镜像名踩坑）。

Searched the web · 1 note

中文：

确认了两件事：eclipse-temurin:21-jre/21-jdk是官方现行维护的tag，Vite 8需要Node 20.19+或22.12+，用node:22-alpine没问题。可以开始写Dockerfile了。

4个Java服务：统一模板(Maven + Java 21，多阶段构建)

结构完全一致，只有EXPOSE端口不同(对应application.yml里的server.port默认值)。以policy-service为例：

dockerfile
# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]

关键设计点：

两阶段构建——构建阶段用带Maven的完整镜像编译，运行阶段只保留jre(不含编译工具)，最终镜像体积小很多，这是Java容器化的标准做法
COPY pom.xml单独一步、先跑mvn dependency:go-offline——这是为了利用Docker的层缓存：只要pom.xml没变(没加新依赖)，以后每次改代码重新build，不用重新下载全部Maven依赖，构建速度快很多
COPY --from=build /app/target/*.jar用通配符——不用你精确报每个服务打出来的jar包名字(带版本号那种)，避免手滑写错

四个服务的差异只在EXPOSE这一行和namespace里最终暴露的端口，对应关系：

服务	EXPOSE
gateway-service	8080
policy-service	8081
notification-service	8082
search-service	8083
Frontend：多阶段构建(Vite build + nginx serve)
dockerfile
# ---------- 构建阶段 ----------
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install
COPY . .
RUN npm run build

# ---------- 运行阶段 ----------
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80

这里需要额外补一份nginx.conf(处理React单页应用的路由回退，不然刷新非首页会404)：

nginx
server {
    listen 80;
    location / {
        root /usr/share/nginx/html;
        try_files $uri $uri/ /index.html;
    }
}
一个必须现在确认的架构问题：VITE_API_BASE_URL这个值在容器化后会失效

你贴的.env.development里写的是：

VITE_API_BASE_URL=http://localhost:8080

这里有个Vite的关键机制要注意：Vite的环境变量是在构建时(npm run build那一刻)被直接写死进打包出来的JS文件里的，不是运行时读取的——也就是说，不管你以后把这个容器部署到哪，这个值一旦打包进去就固定了，localhost:8080在K8s环境里指向的是"访问者自己的电脑"，不是gateway-service，这在生产部署里必然是错的。

所以我们需要在构建镜像时，用一个指向gateway-service实际对外域名的值来覆盖它，而不是继续用localhost:8080。参照我们给kafka-ui/kibana起的xxx.homelab.local这个命名习惯，你想给frontend和gateway各自的对外域名起什么名字？

frontend和gateway-service对外域名想叫什么？
app.homelab.local 和 api.homelab.local
Thought for 5s

中文：

好，域名定了，把frontend的Dockerfile和gateway的CORS配置都补上对应调整。

更新frontend的Dockerfile：用构建参数(ARG)注入正确的API地址
dockerfile
# ---------- 构建阶段 ----------
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install
COPY . .

ARG VITE_API_BASE_URL=https://api.homelab.local
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
RUN npm run build

# ---------- 运行阶段 ----------
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80

构建时这样传参(推送到本地registry时用)：

bash
docker build --build-arg VITE_API_BASE_URL=https://api.homelab.local -t localhost:5000/frontend:latest .

这里ARG给了默认值https://api.homelab.local，所以就算你以后忘了显式传--build-arg，也不会退回到localhost:8080这种明显错误的值，是个安全网。

一个连带要改的地方：gateway-service的CORS配置需要放行app.homelab.local

回顾你贴的application.yml：

yaml
toy-system:
  cors:
    allowed-origins:
      - ${CORS_ALLOWED_ORIGIN_1:http://localhost:5173}
      - ${CORS_ALLOWED_ORIGIN_2:http://127.0.0.1:5173}

这两个默认值是本地开发用的(Vite dev server默认端口5173)，容器化部署后，frontend跑在https://app.homelab.local，如果不放行这个源，浏览器发请求到gateway时会被CORS策略拦截报错。这里不需要改代码，因为这两个值本来就设计成可以被环境变量覆盖——只需要在K8s的Deployment里给gateway-service注入：

yaml
env:
  - name: CORS_ALLOWED_ORIGIN_1
    value: "https://app.homelab.local"

这个先记下来，等我们写gateway-service的Deployment yaml时会用到(属于Step6"ConfigMap/Secret"那一步的内容)。

目前进度小结

Dockerfile这块，5个服务全部齐了：4个Java统一模板(仅端口不同)，frontend这份多阶段构建。下一步该做什么，取决于你现在想不想继续，还是按你最初说的挪到下一轮会话——如果继续，下一步是Step5剩下的部分：build这5个镜像、推送到本地registry(localhost:5000)，需要确认一下你的4个Java项目实际的目录结构（比如是单独4个独立repo/文件夹，还是一个monorepo下4个子模块，这会影响Dockerfile放哪、docker build的执行目录）。你想现在继续，还是先到这里？



先澄清两个前置问题，再进入正题

1. monorepo放法——你的想法是对的，每个子模块根目录(跟pom.xml同级)放一份自己的Dockerfile，docker build时把执行目录切到对应子模块里就行，这是monorepo下多服务分别打包的标准做法，不用额外加配置。

2. 端口"都映射成80"这个理解需要先纠正一下——这是个重要的概念，值得现在讲清楚，不然后面写K8s配置会一直卡在这里。

区分两个层面：

容器内部、应用真正监听的端口——这个不能改，是Spring Boot项目自己在application.yml里写死的(gateway:8080、policy:8081、notification:8082、search:8083)，Dockerfile里的EXPOSE只是"如实声明"这个事实，不是你随便定的数字
K8s Service对外暴露的端口——这个可以在Service这一层做"改名/映射"，比如外部访问Service:80，内部转发到容器实际监听的8080

什么时候需要映射成80、什么时候不需要：

frontend——本身就是nginx serve静态文件，nginx默认监听80，天生就是80，不用映射
gateway-service——因为要走Ingress对外(api.homelab.local)，可以在Service层把"外部看到的80"映射到"容器内部实际的8080"，这样浏览器访问https://api.homelab.local不用带端口号，更美观
policy/notification/search这三个纯内部服务——完全不需要"映射成80"这回事，它们只有ClusterIP Service，没有Ingress，直接用各自的真实端口(8081/8082/8083)就行，因为"调用方"是gateway-service这种"程序"，不是浏览器，不存在"URL里带端口号好不好看"的问题

一句话记住："映射成80"这个便利，只对"要给人在浏览器地址栏直接打开"的服务有意义，纯内部服务用真实端口就好，不用刻意统一成80。

进入正题：从gateway-service开始，逐行精讲Dockerfile
dockerfile
# ---------- 第一阶段：构建 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

# ---------- 第二阶段：运行 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

逐行拆解：

FROM maven:3.9-eclipse-temurin-21 AS build

FROM——每个Dockerfile必须以这个开头，意思是"我要在哪个基础镜像上面盖房子"。这个镜像是Docker Hub上专门给Maven项目准备的，内置了Maven工具本身+Java 21的JDK（注意是JDK不是JRE——JDK包含编译器，JRE只能运行不能编译，构建阶段必须用JDK）。AS build是给这个阶段起个名字叫build，方便后面第二阶段"从这个阶段里拿东西"时能指名道姓。

这就是**"多阶段构建(multi-stage build)"的开端——一份Dockerfile里可以写多个FROM，每个FROM开启一个全新的、互相隔离的阶段，最终镜像只会保留最后一个**FROM之后的内容，前面阶段的东西默认不会带过去（除非你主动COPY --from=xxx）。这也是为什么最终镜像不会因为"用过Maven编译"而变得又大又臃肿。

WORKDIR /app

在容器内部创建（如果不存在）并"切换"到/app这个目录——相当于mkdir -p /app && cd /app，后续所有相对路径操作都以这里为基准。

COPY pom.xml .

把你本地的pom.xml(执行docker build时所在目录下的那份)复制进容器当前目录(.就是刚才WORKDIR定的/app)。这里只复制pom.xml，先不复制源代码——这是故意的，下一步解释为什么。

RUN mvn dependency:go-offline -B

在构建过程中，实际执行这条Maven命令——只根据pom.xml把所有依赖包下载到本地仓库缓存，不编译任何代码(-B是batch mode，非交互式输出，适合CI/容器环境)。

为什么要先单独下载依赖，而不是直接跳到编译：这是利用Docker的**层缓存(layer cache)**机制——Docker会把Dockerfile的每一行都当成一层"快照"，如果某一层的输入(这里是pom.xml)没有变化，Docker会直接复用上次构建时缓存的结果，跳过重新执行。也就是说：只要你没改pom.xml(没加新依赖)，以后每次改Java代码重新build镜像，这一步"下载依赖"会被跳过，直接用缓存，构建速度快很多——如果一开始就把COPY src和pom.xml放一起复制，那源代码任何一次改动都会导致这一层缓存失效，每次都要重新下载全部依赖，非常慢。

COPY src ./src

这时候才复制真正的Java源代码进去。

RUN mvn clean package -DskipTests -B

真正编译打包——clean先清掉上次编译的残留产物，package执行完整的编译+打包流程，最终会在target/目录下生成一个.jar文件。-DskipTests是跳过单元测试(镜像构建阶段一般不在这里跑测试，测试应该在CI流水线的更早阶段就做完，这里重复跑只会拖慢构建速度)。

FROM eclipse-temurin:21-jre

第二阶段开始——注意这次用的是jre不是jdk，因为运行阶段只需要"能跑Java程序"，不需要编译器、不需要Maven，用更精简的运行时镜像，体积小很多(这也是多阶段构建最大的价值：构建工具的体积不会带到最终产物里)。这一行执行后，之前build阶段安装的Maven、下载的依赖、JDK，全部被"忘记"，是一块全新的、干净的地基。

WORKDIR /app

同上，在这个新阶段里再建一次工作目录(两个阶段是完全独立的文件系统，之前那个/app不会自动带过来)。

COPY --from=build /app/target/*.jar app.jar

这是连接两个阶段的关键一行——--from=build指名"去刚才那个叫build的阶段里找东西"，/app/target/*.jar是"那个阶段里，target目录下所有jar文件"(用通配符是因为不想手动打出精确的文件名，比如gateway-service-0.0.1-SNAPSHOT.jar这种带版本号的名字)，复制过来后重命名成app.jar——这样后面的启动命令不用关心具体文件名是什么，简化配置。

EXPOSE 8080

纯粹是声明性的，不会实际改变任何网络行为——只是告诉"看这份Dockerfile的人"(包括K8s、docker-compose这些工具)"这个容器打算用8080端口对外提供服务"，起到文档说明的作用。真正让端口生效的，是容器里Spring Boot应用自己读application.yml里的server.port配置后，实际去bind这个端口监听——EXPOSE这一行删掉，应用照样能在8080上正常工作，只是少了这份"友好提示"。

ENTRYPOINT ["java", "-jar", "app.jar"]

容器启动时实际执行的命令——用的是"exec形式"(方括号+每个参数单独一个字符串)，而不是"shell形式"(ENTRYPOINT java -jar app.jar这种写法)，前者更推荐，因为它不会额外套一层shell进程，容器收到停止信号(比如K8s要优雅关闭Pod时发的SIGTERM)能直接传给Java进程本身，关闭更干净利落。

现在把这份Dockerfile放到gateway-service子模块根目录，build+push

假设你的monorepo结构下，gateway-service子模块路径是(把下面路径换成你实际的，不确定的话告诉我实际路径)：

bash
cd /home/ziqiao/Documents/homelab-toy-system/<monorepo根目录>/gateway-service

# 把上面那份Dockerfile内容存成这个文件
# (可以用之前学过的heredoc方式，或者直接在VSCode里新建文件保存)

docker build -t localhost:5000/gateway-service:latest .
docker push localhost:5000/gateway-service:latest

-t localhost:5000/gateway-service:latest——这个命名格式是Docker约定的规则：<registry地址>/<镜像名>:<tag>，localhost:5000对应你之前跑起来的那个registry:2容器，这样docker push才知道要推去哪；最后的.是"构建上下文"，告诉Docker"以当前目录为基准去找Dockerfile和COPY要用的文件"。

先把这份Dockerfile文件放进gateway-service目录，跑一下docker build那一步，看看能不能顺利编译成功——如果有报错(比如目录结构跟我猜测的不一样导致COPY src ./src找不到文件)，贴给我，我们一起排查；如果顺利，我们再讲下一个服务。