package com.example.springboot.mapper;

import com.example.springboot.entity.AITaskLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AITaskLogMapper {
    void save(AITaskLog taskLog);

    AITaskLog getById(Long id);

    void updateStatus(@Param("id") Long id, @Param("status") String status);
}
