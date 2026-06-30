package com.example.springboot.mapper;

import com.example.springboot.entity.AITaskLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AITaskLogMapper {
    void save(AITaskLog taskLog);

    AITaskLog getById(Long id);

    List<AITaskLog> listByAdminId(@Param("adminId") Integer adminId, @Param("limit") Integer limit);

    void updateStatus(@Param("id") Long id, @Param("status") String status);
}
