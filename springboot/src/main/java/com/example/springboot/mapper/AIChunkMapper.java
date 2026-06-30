package com.example.springboot.mapper;

import com.example.springboot.entity.AIChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AIChunkMapper {
    List<AIChunk> listByKnowledgeId(Long knowledgeId);

    List<AIChunk> searchByKeyword(@Param("keyword") String keyword, @Param("limit") Integer limit);

    void save(AIChunk chunk);

    void deleteByKnowledgeId(Long knowledgeId);
}
