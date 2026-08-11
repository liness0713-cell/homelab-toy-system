package com.toysystem.policy.sharding;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;

/**
 * 从 shardingsphere-config.yaml 构造ShardingSphere包装过的DataSource，对外暴露出来的
 * 还是标准的javax.sql.DataSource——MyBatis不用感知底层是ShardingSphere在做路由，直接当成
 * 普通DataSource用。数据源连接信息（URL/账号/密码）通过字符串替换把环境变量塞进YAML模板里，
 * 而不是硬编码，跟改造前的行为保持一致。
 *
 * Liquibase现在单独直连真实MySQL（见application.yml的spring.liquibase.url），不经过这个
 * DataSource——这带来一个隐藏的启动顺序问题：ShardingSphere构造DataSource时会扫描一次
 * "actualDataNodes里配的物理表实际存不存在"，如果这个扫描发生在Liquibase建表之前，
 * ShardingSphere会永久记住"这些表不存在"，后续所有查询都报TableNotFoundException，即使
 * 表后来确实被Liquibase建出来了也不会重新扫描。
 *
 * 试过两种"强制先跑Liquibase"的方案，都因为Spring的循环依赖检测失败（Liquibase自动配置类
 * 构造方法里有个从来不会真正用到的ObjectProvider&lt;DataSource&gt;兜底参数，会被当成一条边）：
 * @DependsOn("liquibase")、以及在方法体里手动applicationContext.getBean(SpringLiquibase.class)——
 * 后者虽然是命令式调用，但Spring的循环检测是基于"当前正在创建哪些bean"的运行时栈跟踪，
 * 不区分声明式/命令式，一样会被抓到。spring.main.allow-circular-references=true也救不了，
 * 那个开关只对属性/字段注入的环有效，对@DependsOn这种硬顺序约束没用。
 *
 * 最终方案：把真正构造ShardingSphereDataSource这件事包进 LazyDataSource，推迟到第一次
 * 真的有人调用getConnection()才做，不在Spring装配这个bean的时候做——这样它就不需要对
 * liquibase声明任何依赖了。mybatis-spring-boot-starter自己通过Spring Boot的
 * DependsOnDatabaseInitializationDetector这套SPI，已经保证了MyBatis相关bean会在Liquibase
 * 迁移跑完之后才创建；等到应用第一次真的发起一次数据库查询时，Liquibase必然早就跑完了。
 */
@Configuration
public class ShardingSphereConfig {

    @Value("${SPRING_DATASOURCE_URL:jdbc:mysql://localhost:3306/toy_policy_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC}")
    private String jdbcUrl;

    @Value("${SPRING_DATASOURCE_USERNAME:toy_app}")
    private String username;

    @Value("${SPRING_DATASOURCE_PASSWORD:toy_app_pw}")
    private String password;

    @Primary
    @Bean
    public DataSource dataSource() {
        return new LazyDataSource(this::buildShardingSphereDataSource);
    }

    /**
     * P4.5对比路径专用：不经过ShardingSphere包装的原生DataSource，直连同一个MySQL实例/schema。
     *
     * 本来想让shardAware=true那条路径直接对物理表名（如policy_3）发SQL，但实测走
     * ShardingSphere包装过的DataSource行不通——policy_0~policy_3是policy这张逻辑表的
     * actualDataNodes，被!SHARDING规则"占用"了，ShardingSphere的元数据里不会再把它们
     * 当成可以被直接寻址的独立表（哪怕加了!SINGLE的"*.*"通配也不行，单表加载器会跳过已经
     * 被其他规则接管的表名），于是无论物理表是否真实存在，SQL解析/路由层直接报
     * TableNotFoundException，根本到不了真正执行阶段。
     *
     * 换个角度看这反而更贴合这条对比路径本来的教学目的："已经知道该打哪个分片，绕开
     * ShardingSphere的路由决策，直接连过去查"——用一个完全独立、不经过ShardingSphere的
     * 原生连接来做这件事，语义上也更准确，不是在跟ShardingSphere的内部限制较劲。
     */
    @Bean
    public DataSource rawMySqlDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("raw-mysql-shard-aware");
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername(username);
        ds.setPassword(password);
        // 这个连接池只服务shardAware=true这一条低频对比路径，没必要跟主池一样大。
        ds.setMaximumPoolSize(3);
        return ds;
    }

    private DataSource buildShardingSphereDataSource() {
        try {
            String yaml;
            try (var input = new ClassPathResource("shardingsphere-config.yaml").getInputStream()) {
                yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            yaml = yaml.replace("@SPRING_DATASOURCE_URL@", jdbcUrl)
                    .replace("@SPRING_DATASOURCE_USERNAME@", username)
                    .replace("@SPRING_DATASOURCE_PASSWORD@", password);

            return YamlShardingSphereDataSourceFactory.createDataSource(yaml.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build ShardingSphere DataSource", e);
        }
    }
}
