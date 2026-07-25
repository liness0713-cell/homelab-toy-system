package com.toysystem.policy.mapper;

import com.toysystem.policy.model.Policy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PolicyMapper {

    int insert(Policy policy);

    Policy findById(@Param("id") Long id);

    List<Policy> findAll();

    int update(Policy policy);

    int deleteById(@Param("id") Long id);
}
