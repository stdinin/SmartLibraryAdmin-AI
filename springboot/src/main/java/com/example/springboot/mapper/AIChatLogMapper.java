package com.example.springboot.mapper;

import com.example.springboot.entity.AIChatLog;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AIChatLogMapper {
    void save(AIChatLog chatLog);

    List<AIChatLog> listByAdminId(Integer adminId);
}
