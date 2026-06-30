CREATE TABLE IF NOT EXISTS ai_knowledge (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  title VARCHAR(200) NOT NULL,
  content TEXT NOT NULL,
  source_type VARCHAR(50) DEFAULT 'manual',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai_chat_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  admin_id INT NULL,
  question TEXT NOT NULL,
  answer TEXT NOT NULL,
  retrieved_context MEDIUMTEXT NULL,
  intent VARCHAR(100) NULL,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai_task_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  admin_id INT NULL,
  task_type VARCHAR(100) NOT NULL,
  input_json MEDIUMTEXT NOT NULL,
  output_json MEDIUMTEXT NULL,
  status VARCHAR(30) DEFAULT 'success',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai_chunk (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  knowledge_id BIGINT NOT NULL,
  chunk_text MEDIUMTEXT NOT NULL,
  embedding_id VARCHAR(255) NULL,
  embedding_text MEDIUMTEXT NULL,
  metadata JSON NULL,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_ai_chunk_knowledge_id (knowledge_id)
);

INSERT INTO ai_knowledge(title, content, source_type)
SELECT '借阅与逾期规则', '管理员应查看借阅列表中的归还日期和过期提醒。逾期记录需要联系读者归还，涉及扣减积分时应按系统现有借还流程处理。', 'seed'
WHERE NOT EXISTS (SELECT 1 FROM ai_knowledge WHERE title = '借阅与逾期规则');

INSERT INTO ai_knowledge(title, content, source_type)
SELECT '图书分类规范', '图书分类应优先使用系统已有分类树。AI智能编目只能生成建议，管理员确认后才可以写入图书表。', 'seed'
WHERE NOT EXISTS (SELECT 1 FROM ai_knowledge WHERE title = '图书分类规范');

INSERT INTO ai_knowledge(title, content, source_type)
SELECT '后台操作安全规则', 'AI助手不得自动删除数据。新增或修改类操作必须生成待确认方案，由管理员确认后再通过原有业务接口执行。', 'seed'
WHERE NOT EXISTS (SELECT 1 FROM ai_knowledge WHERE title = '后台操作安全规则');
