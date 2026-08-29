# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# 先复制根pom + 4个子模块各自的pom.xml(父pom解析多模块结构时，这4个都得能找到)
COPY pom.xml .
COPY event-contracts/pom.xml event-contracts/pom.xml
COPY policy-service/pom.xml policy-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY search-service/pom.xml search-service/pom.xml

# -am(also make)现在是真正需要的：search-service依赖event-contracts，
# 必须先把event-contracts的依赖也下载好
RUN mvn -pl search-service -am dependency:go-offline -B

# event-contracts是被依赖的一方，它的源码也要复制进来才能被编译成jar
COPY event-contracts/src ./event-contracts/src
COPY search-service/src ./search-service/src

# -am会先编译event-contracts、安装到本地.m2仓库，再编译search-service
RUN mvn -pl search-service -am clean package -DskipTests -B

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/search-service/target/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]