package com.example.springboot.mapper;

import com.example.springboot.entity.AIKnowledge;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AIKnowledgeMapper {
    List<AIKnowledge> list();

    AIKnowledge getById(Long id);

    List<AIKnowledge> searchByKeyword(@Param("keyword") String keyword, @Param("limit") Integer limit);

    void save(AIKnowledge knowledge);

    void updateById(AIKnowledge knowledge);

    void deleteById(Long id);
}
