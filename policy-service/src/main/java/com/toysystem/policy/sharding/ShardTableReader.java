package com.toysystem.policy.sharding;

import com.toysystem.policy.model.Policy;
import com.toysystem.policy.model.PolicyStatus;
import com.toysystem.policy.model.ProductType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

/**
 * P4.5对比路径专用：直接对指定物理表（policy_0~policy_3之一）发SQL，走的是
 * rawMySqlDataSource（见ShardingSphereConfig），完全绕开ShardingSphere——不是MyBatis+
 * ShardingSphere包装的DataSource。之所以不干脆让MyBatis的mapper直接拼物理表名查，
 * 是因为实测行不通：policy_0~policy_3是policy逻辑表的actualDataNodes，被!SHARDING规则
 * "占用"了，ShardingSphere的SQL路由层直接认为这些表名不存在（TableNotFoundException），
 * 压根到不了真正执行阶段，跟!SINGLE的"*.*"通配也没关系（单表加载器会跳过已经被其他规则
 * 接管的表）。这里改用JdbcTemplate而不是再建一套MyBatis SqlSessionFactory，是因为
 * 只有这一条SELECT，没必要为了一个方法搭第二套MyBatis配置。
 */
@Component
public class ShardTableReader {

    private final JdbcTemplate jdbcTemplate;

    public ShardTableReader(@Qualifier("rawMySqlDataSource") DataSource rawMySqlDataSource) {
        this.jdbcTemplate = new JdbcTemplate(rawMySqlDataSource);
    }

    public Policy findByIdInShard(Long id, int shardIndex) {
        String sql = "SELECT id, policy_no, holder_name, product_type, premium, status, created_at, updated_at "
                + "FROM policy_" + shardIndex + " WHERE id = ?";
        List<Policy> results = jdbcTemplate.query(sql, (rs, rowNum) -> {
            Policy policy = new Policy();
            policy.setId(rs.getLong("id"));
            policy.setPolicyNo(rs.getString("policy_no"));
            policy.setHolderName(rs.getString("holder_name"));
            policy.setProductType(ProductType.valueOf(rs.getString("product_type")));
            policy.setPremium(rs.getBigDecimal("premium"));
            policy.setStatus(PolicyStatus.valueOf(rs.getString("status")));
            policy.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            policy.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
            return policy;
        }, id);
        return results.isEmpty() ? null : results.get(0);
    }
}
