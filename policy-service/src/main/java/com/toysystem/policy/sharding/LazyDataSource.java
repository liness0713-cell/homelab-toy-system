package com.toysystem.policy.sharding;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.function.Supplier;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * 把真正构造ShardingSphereDataSource这件事推迟到第一次真的有人要拿Connection的时候，
 * 而不是在Spring装配这个bean的时候就做。
 *
 * 背景：Liquibase现在直连真实MySQL、不经过这个DataSource（见ShardingSphereConfig类注释）。
 * 试过用@DependsOn("liquibase")强制"先跑完Liquibase迁移、再构造这个DataSource"，结果
 * Spring报循环依赖（Liquibase自动配置类构造方法里有个从来用不到的ObjectProvider&lt;DataSource&gt;
 * 兜底参数，被静态依赖图分析当成了一条边），而且这种基于@DependsOn的环，
 * spring.main.allow-circular-references=true也解不开（那个开关只对属性/字段注入的环有效，
 * @DependsOn是纯粹的初始化顺序约束，没法靠"先给个早期引用"来打破）。
 *
 * 换成这个懒加载包装类，彻底不声明任何跟liquibase相关的依赖——Spring创建这个bean本身
 * 很轻量（不连接真实数据库，不触发ShardingSphere的表结构扫描）。真正的构造被推迟到
 * MyBatis第一次调用getConnection()那一刻，而Spring Boot/mybatis-spring-boot-starter
 * 自己的自动配置本来就通过DependsOnDatabaseInitializationDetector这套SPI机制保证了
 * MyBatis相关bean会在Liquibase迁移跑完之后才创建——等到真正发起第一次查询时，
 * Liquibase必然早就跑完了，ShardingSphere这时候再去扫"物理表存不存在"，扫到的就是
 * 建好之后的真实状态。
 */
public class LazyDataSource implements DataSource {

    private final Supplier<DataSource> factory;
    private volatile DataSource delegate;

    public LazyDataSource(Supplier<DataSource> factory) {
        this.factory = factory;
    }

    private DataSource delegate() {
        DataSource result = delegate;
        if (result == null) {
            synchronized (this) {
                result = delegate;
                if (result == null) {
                    delegate = result = factory.get();
                }
            }
        }
        return result;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return delegate().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return delegate().getConnection(username, password);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate().getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate().setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate().setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate().getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate().getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate().unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate().isWrapperFor(iface);
    }
}
