<template>
  <div class="ai-assistant">
    <el-row :gutter="16">
      <el-col :span="8">
        <el-card shadow="never" class="panel">
          <div slot="header" class="panel-title">管理员智能助手</div>
          <el-tabs v-model="mode">
            <el-tab-pane label="知识问答" name="chat">
              <div class="quick-row">
                <el-tag
                    v-for="item in chatExamples"
                    :key="item"
                    size="small"
                    @click="question = item">
                  {{ item }}
                </el-tag>
              </div>
              <el-input
                  type="textarea"
                  :rows="8"
                  v-model="question"
                  placeholder="例如：有没有物理相关的书？借阅逾期怎么处理？">
              </el-input>
              <el-button type="primary" class="full-button" :loading="chatLoading" @click="sendQuestion">
                提问
              </el-button>
            </el-tab-pane>
            <el-tab-pane label="任务规划" name="agent">
              <el-alert
                  title="执行规则：只读查询可直接执行；新增/修改只生成待确认草案；删除、清空等危险操作会被阻断。"
                  type="info"
                  :closable="false"
                  class="task-policy">
              </el-alert>
              <div
                  v-for="group in taskExampleGroups"
                  :key="group.title"
                  class="task-example-group">
                <div class="example-title">
                  <el-tag size="mini" :type="group.type">{{ group.title }}</el-tag>
                  <span>{{ group.desc }}</span>
                </div>
                <div class="quick-row">
                  <el-tag
                      v-for="item in group.items"
                      :key="item"
                      size="small"
                      :type="group.type"
                      @click="task = item">
                    {{ item }}
                  </el-tag>
                </div>
              </div>
              <el-input
                  type="textarea"
                  :rows="8"
                  v-model="task"
                  placeholder="例如：执行：筛选库存为0且长期没人借的书">
              </el-input>
              <el-button type="success" class="full-button" :loading="agentLoading" @click="createPlan">
                执行/生成方案
              </el-button>
            </el-tab-pane>
            <el-tab-pane label="知识库" name="knowledge">
              <div class="knowledge-actions">
                <el-button type="primary" plain size="small" @click="openKnowledgeDialog()">新增知识</el-button>
                <el-button type="success" plain size="small" :loading="knowledgeSeeding" @click="seedKnowledge">初始化规则</el-button>
              </div>
              <el-input
                  v-model="knowledgeFilter"
                  placeholder="筛选知识标题"
                  clearable
                  style="margin: 12px 0">
              </el-input>
              <el-table :data="filteredKnowledge" size="small" height="260" border>
                <el-table-column prop="title" label="标题"></el-table-column>
                <el-table-column prop="sourceType" label="来源" width="88"></el-table-column>
                <el-table-column prop="content" label="内容摘要" show-overflow-tooltip></el-table-column>
                <el-table-column label="操作" width="120">
                  <template slot-scope="scope">
                    <el-button type="text" size="mini" @click="openKnowledgeDialog(scope.row)">编辑</el-button>
                    <el-button type="text" size="mini" class="danger-text" @click="removeKnowledge(scope.row)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>
          </el-tabs>
        </el-card>
      </el-col>

      <el-col :span="16">
        <el-card shadow="never" class="panel">
          <div slot="header" class="panel-title">AI 结果</div>
          <el-empty v-if="!chatResult && !agentResult" description="暂无结果"></el-empty>

          <div v-if="chatResult" class="result-block">
            <h3>回答</h3>
            <div class="answer-card" :class="{ uncertain: chatResult.uncertain }">
              {{ chatResult.answer }}
            </div>

            <h3>数据库事实</h3>
            <div v-if="hasDbFacts">
              <el-tag type="info">匹配图书 {{ chatResult.db_facts.totalMatched }} 本</el-tag>
              <el-table :data="chatResult.db_facts.matchedBooks || []" size="small" border style="margin-top: 10px">
                <el-table-column prop="name" label="书名"></el-table-column>
                <el-table-column prop="author" label="作者" width="120"></el-table-column>
                <el-table-column prop="category" label="分类" width="130"></el-table-column>
                <el-table-column prop="nums" label="库存" width="70"></el-table-column>
              </el-table>
            </div>
            <el-alert v-else title="本次没有命中馆藏数据库事实" type="info" :closable="false"></el-alert>

            <h3>引用来源</h3>
            <el-table :data="chatResult.source_list || []" size="small" border>
              <el-table-column prop="title" label="标题"></el-table-column>
              <el-table-column prop="sourceType" label="来源" width="120"></el-table-column>
            </el-table>

            <h3>检索内容</h3>
            <el-alert
                v-for="(item, index) in chatResult.retrieved_context || []"
                :key="index"
                :title="item"
                type="info"
                :closable="false"
                style="margin-bottom: 8px">
            </el-alert>
          </div>

          <div v-if="agentResult" class="result-block">
            <h3>操作方案</h3>
            <el-descriptions :column="2" border>
              <el-descriptions-item label="任务编号">{{ agentResult.taskLogId || '-' }}</el-descriptions-item>
              <el-descriptions-item label="意图">{{ agentResult.intent }}</el-descriptions-item>
              <el-descriptions-item label="风险">{{ agentResult.risk_level }}</el-descriptions-item>
              <el-descriptions-item label="需确认">{{ agentResult.need_confirm ? '是' : '否' }}</el-descriptions-item>
              <el-descriptions-item label="阻断原因">{{ agentResult.blockedReason || '无' }}</el-descriptions-item>
            </el-descriptions>

            <el-progress :percentage="agentProgress" :status="agentProgress === 100 ? 'success' : undefined" class="task-progress"></el-progress>

            <el-alert
                v-if="agentExecutionResult"
                :title="agentExecutionResult.message || agentExecutionResult.blockedReason"
                :type="agentExecutionResult.executed ? 'success' : 'warning'"
                :closable="false"
                class="execution-alert">
            </el-alert>

            <h3>执行工具</h3>
            <el-timeline>
              <el-timeline-item
                  v-for="(item, index) in agentSubTasks"
                  :key="index"
                  :type="timelineType(item.status)"
                  :timestamp="item.status">
                {{ item.name || item.type }}
              </el-timeline-item>
            </el-timeline>

            <h3>步骤</h3>
            <el-steps direction="vertical" :active="(agentResult.steps || []).length">
              <el-step v-for="(step, index) in agentResult.steps || []" :key="index" :title="step"></el-step>
            </el-steps>

            <div v-if="agentResult.draft_result" class="draft-box">
              <h3>草案/分析结果</h3>
              <el-alert
                  v-if="agentResult.draft_result.summary"
                  :title="agentResult.draft_result.summary"
                  type="info"
                  :closable="false"
                  style="margin-bottom: 12px">
              </el-alert>
              <el-table
                  v-if="isBookSearchDraft"
                  :data="agentResult.draft_result.matchedBooks || []"
                  size="small"
                  border>
                <el-table-column prop="name" label="书名" show-overflow-tooltip></el-table-column>
                <el-table-column prop="author" label="作者" width="130" show-overflow-tooltip></el-table-column>
                <el-table-column prop="category" label="分类" width="130"></el-table-column>
                <el-table-column prop="nums" label="库存" width="70"></el-table-column>
              </el-table>
              <el-table
                  v-else-if="isBorrowDraft"
                  :data="agentResult.draft_result.overdueRisks || []"
                  size="small"
                  border>
                <el-table-column prop="bookName" label="逾期图书" show-overflow-tooltip></el-table-column>
                <el-table-column prop="userName" label="读者" width="100"></el-table-column>
                <el-table-column prop="userPhone" label="电话" width="130"></el-table-column>
                <el-table-column prop="returnDate" label="应还日期" width="120"></el-table-column>
              </el-table>
              <div v-else-if="isQualityDraft">
                <el-row :gutter="10">
                  <el-col :span="6">
                    <div class="mini-stat">缺字段 {{ (agentResult.draft_result.missingBooks || []).length }}</div>
                  </el-col>
                  <el-col :span="6">
                    <div class="mini-stat">疑似重复 {{ (agentResult.draft_result.duplicateBooks || []).length }}</div>
                  </el-col>
                  <el-col :span="6">
                    <div class="mini-stat">库存异常 {{ (agentResult.draft_result.stockAbnormalBooks || []).length }}</div>
                  </el-col>
                  <el-col :span="6">
                    <div class="mini-stat">分类异常 {{ (agentResult.draft_result.categoryAbnormalBooks || []).length }}</div>
                  </el-col>
                </el-row>
              </div>
              <div v-else-if="isBookWriteDraft">
                <el-alert
                    :title="agentResult.draft_result.nextStep || agentResult.draft_result.notice"
                    type="warning"
                    :closable="false"
                    style="margin-bottom: 12px">
                </el-alert>
                <el-descriptions :column="2" border>
                  <el-descriptions-item label="操作">{{ agentResult.draft_result.operation }}</el-descriptions-item>
                  <el-descriptions-item label="书名">{{ agentResult.draft_result.bookDraft.name || '未识别' }}</el-descriptions-item>
                  <el-descriptions-item label="作者">{{ agentResult.draft_result.bookDraft.author || '未识别' }}</el-descriptions-item>
                  <el-descriptions-item label="出版社">{{ agentResult.draft_result.bookDraft.publisher || '未识别' }}</el-descriptions-item>
                  <el-descriptions-item label="分类">{{ agentResult.draft_result.bookDraft.category || '未识别' }}</el-descriptions-item>
                  <el-descriptions-item label="库存">{{ agentResult.draft_result.bookDraft.nums || '未识别' }}</el-descriptions-item>
                  <el-descriptions-item label="简介" :span="2">{{ agentResult.draft_result.bookDraft.description || '未识别' }}</el-descriptions-item>
                </el-descriptions>
                <div v-if="agentResult.draft_result.missingFields && agentResult.draft_result.missingFields.length" class="missing-fields">
                  <span>缺少字段：</span>
                  <el-tag
                      v-for="item in agentResult.draft_result.missingFields"
                      :key="item"
                      size="mini"
                      type="warning">
                    {{ item }}
                  </el-tag>
                </div>
              </div>
              <pre v-else>{{ pretty(agentResult.draft_result) }}</pre>
              <div v-if="agentResult.draft_result.suggestions && agentResult.draft_result.suggestions.length" class="suggestions">
                <el-alert
                    v-for="(item, index) in agentResult.draft_result.suggestions"
                    :key="index"
                    :title="item"
                    type="success"
                    :closable="false">
                </el-alert>
              </div>
            </div>
            <el-button
                v-if="agentResult.need_confirm"
                type="warning"
                :loading="confirmLoading"
                @click="confirmPlan">
              确认方案
            </el-button>
            <el-button
                v-if="agentResult.need_confirm"
                plain
                :loading="cancelLoading"
                @click="cancelPlan">
              取消方案
            </el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :span="24">
        <el-card shadow="never" class="panel">
          <div slot="header" class="panel-header">
            <span class="panel-title">最近 AI 任务</span>
            <el-button size="mini" @click="loadTasks">刷新</el-button>
          </div>
          <el-table :data="taskHistory" size="small" border>
            <el-table-column prop="taskLogId" label="编号" width="80"></el-table-column>
            <el-table-column prop="taskType" label="类型" width="150"></el-table-column>
            <el-table-column prop="intent" label="意图"></el-table-column>
            <el-table-column prop="status" label="状态" width="120"></el-table-column>
            <el-table-column prop="progress" label="进度" width="160">
              <template slot-scope="scope">
                <el-progress :percentage="scope.row.progress || 0" :stroke-width="10"></el-progress>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="130">
              <template slot-scope="scope">
                <el-button type="text" size="mini" @click="openTask(scope.row)">查看</el-button>
                <el-button
                    v-if="scope.row.status === 'pending_confirm'"
                    type="text"
                    size="mini"
                    class="danger-text"
                    @click="cancelHistoryTask(scope.row)">
                  取消
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog title="知识库内容" :visible.sync="knowledgeDialogVisible" width="640px">
      <el-form :model="knowledgeForm" label-width="80px">
        <el-form-item label="标题" required>
          <el-input v-model="knowledgeForm.title" placeholder="例如：借阅逾期处理规则"></el-input>
        </el-form-item>
        <el-form-item label="来源">
          <el-input v-model="knowledgeForm.sourceType" placeholder="manual"></el-input>
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input type="textarea" :rows="8" v-model="knowledgeForm.content" placeholder="请输入可作为回答依据的管理规则或说明"></el-input>
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="knowledgeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="knowledgeSaving" @click="submitKnowledge">保存</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import {
  agentConfirm,
  agentPlan,
  cancelAgentTask,
  deleteKnowledge,
  getAgentTask,
  listAgentTasks,
  listKnowledge,
  ragChat,
  seedDefaultKnowledge,
  saveKnowledge
} from '@/api/ai'

export default {
  name: 'Assistant',
  data() {
    return {
      mode: 'chat',
      question: '',
      task: '',
      chatResult: null,
      agentResult: null,
      taskHistory: [],
      knowledgeList: [],
      knowledgeFilter: '',
      chatExamples: ['借阅逾期怎么处理？', '有没有小说相关的书？', '图书分类规范是什么？'],
      taskExampleGroups: [
        {
          title: '只读执行',
          type: 'success',
          desc: '自动查询并返回结果，不改业务数据',
          items: ['执行：筛选库存为0且长期没人借的书', '执行：生成本月逾期提醒清单', '执行：检查馆藏数据质量']
        },
        {
          title: '需确认执行',
          type: 'warning',
          desc: '生成新增/修改草案，管理员确认后再去原页面保存',
          items: ['执行：新增一本《任务规划演示书》作者演示作者出版社演示出版社分类计算机库存5本简介用于演示AI任务规划确认执行', '执行：修改《任务规划演示书》的分类为人工智能']
        },
        {
          title: '禁止执行',
          type: 'danger',
          desc: '删除和清空类任务只提示风险，不自动处理',
          items: ['执行：删除所有库存为0的书']
        }
      ],
      knowledgeDialogVisible: false,
      knowledgeForm: this.emptyKnowledgeForm(),
      chatLoading: false,
      agentLoading: false,
      confirmLoading: false,
      cancelLoading: false,
      knowledgeSaving: false,
      knowledgeSeeding: false,
      pollTimer: null
    }
  },
  computed: {
    hasDbFacts() {
      return this.chatResult && this.chatResult.db_facts && this.chatResult.db_facts.totalMatched > 0
    },
    agentProgress() {
      return this.agentResult && this.agentResult.progress !== undefined ? this.agentResult.progress : 100
    },
    agentSubTasks() {
      if (this.agentResult && this.agentResult.subTasks && this.agentResult.subTasks.length) {
        return this.agentResult.subTasks
      }
      return (this.agentResult && this.agentResult.tools_to_call || []).map(tool => ({
        type: tool,
        name: tool,
        status: 'COMPLETED'
      }))
    },
    filteredKnowledge() {
      const keyword = (this.knowledgeFilter || '').trim()
      if (!keyword) return this.knowledgeList
      return this.knowledgeList.filter(item => (item.title || '').indexOf(keyword) >= 0)
    },
    isBookSearchDraft() {
      return this.agentResult && this.agentResult.draft_result && Array.isArray(this.agentResult.draft_result.matchedBooks)
    },
    isBorrowDraft() {
      return this.agentResult && this.agentResult.draft_result && Array.isArray(this.agentResult.draft_result.overdueRisks)
    },
    isQualityDraft() {
      return this.agentResult && this.agentResult.draft_result && this.agentResult.draft_result.missingFieldStats
    },
    isBookWriteDraft() {
      return this.agentResult && this.agentResult.draft_result && this.agentResult.draft_result.bookDraft
    },
    agentExecutionResult() {
      return this.agentResult && this.agentResult.execution_result
    }
  },
  mounted() {
    this.loadKnowledge()
    this.loadTasks()
  },
  beforeDestroy() {
    this.stopPolling()
  },
  methods: {
    emptyKnowledgeForm() {
      return { id: null, title: '', content: '', sourceType: 'manual' }
    },
    sendQuestion() {
      if (!this.question.trim()) {
        this.$message.warning('请输入问题')
        return
      }
      this.chatLoading = true
      ragChat({ question: this.question }).then(res => {
        if (res.code === '200') {
          this.chatResult = res.data
          this.agentResult = null
        } else {
          this.$message.error(res.msg || 'AI问答失败')
        }
      }).catch(err => {
        this.$message.error(err.message || 'AI问答失败')
      }).finally(() => {
        this.chatLoading = false
      })
    },
    createPlan() {
      if (!this.task.trim()) {
        this.$message.warning('请输入任务')
        return
      }
      this.agentLoading = true
      agentPlan({ task: this.task }).then(res => {
        if (res.code === '200') {
          this.agentResult = res.data
          this.chatResult = null
          this.loadTasks()
          this.startPolling(this.agentResult.taskLogId)
        } else {
          this.$message.error(res.msg || '方案生成失败')
        }
      }).catch(err => {
        this.$message.error(err.message || '方案生成失败')
      }).finally(() => {
        this.agentLoading = false
      })
    },
    confirmPlan() {
      if (!this.agentResult || !this.agentResult.taskLogId) {
        this.$message.warning('当前方案没有可确认的任务编号')
        return
      }
      this.confirmLoading = true
      agentConfirm({ taskLogId: this.agentResult.taskLogId, confirm: true }).then(res => {
        if (res.code === '200') {
          const result = res.data.result || {}
          const execution = result.execution_result || {}
          if (execution.executed) {
            this.$message.success(execution.message || '已执行')
            this.agentResult = Object.assign({}, this.agentResult, result)
          } else {
            this.$alert(execution.blockedReason || res.data.blockedReason || '未执行', '确认结果')
          }
          this.refreshTask(this.agentResult.taskLogId)
        } else {
          this.$message.error(res.msg || '确认失败')
        }
      }).finally(() => {
        this.confirmLoading = false
      })
    },
    cancelPlan() {
      if (!this.agentResult || !this.agentResult.taskLogId) return
      this.cancelLoading = true
      cancelAgentTask(this.agentResult.taskLogId).then(res => {
        if (res.code === '200') {
          this.agentResult = res.data.task || this.agentResult
          this.loadTasks()
          this.$message.success(res.data.cancelled ? '已取消方案' : (res.data.blockedReason || '无法取消'))
        } else {
          this.$message.error(res.msg || '取消失败')
        }
      }).finally(() => {
        this.cancelLoading = false
      })
    },
    cancelHistoryTask(row) {
      cancelAgentTask(row.taskLogId).then(() => {
        this.loadTasks()
        if (this.agentResult && this.agentResult.taskLogId === row.taskLogId) {
          this.refreshTask(row.taskLogId)
        }
      })
    },
    loadTasks() {
      listAgentTasks(10).then(res => {
        if (res.code === '200') {
          this.taskHistory = res.data || []
        }
      })
    },
    openTask(row) {
      this.refreshTask(row.taskLogId)
      this.chatResult = null
    },
    refreshTask(taskLogId) {
      if (!taskLogId) return
      getAgentTask(taskLogId).then(res => {
        if (res.code === '200') {
          this.agentResult = res.data
          this.loadTasks()
        }
      })
    },
    startPolling(taskLogId) {
      this.stopPolling()
      if (!taskLogId) return
      this.pollTimer = setInterval(() => {
        this.refreshTask(taskLogId)
      }, 3000)
      setTimeout(() => this.stopPolling(), 12000)
    },
    stopPolling() {
      if (this.pollTimer) {
        clearInterval(this.pollTimer)
        this.pollTimer = null
      }
    },
    loadKnowledge() {
      listKnowledge().then(res => {
        if (res.code === '200') {
          this.knowledgeList = res.data || []
        }
      })
    },
    seedKnowledge() {
      this.knowledgeSeeding = true
      seedDefaultKnowledge().then(res => {
        if (res.code === '200') {
          this.$message.success(`已补充 ${res.data.inserted || 0} 条默认知识`)
          this.loadKnowledge()
        } else {
          this.$message.error(res.msg || '初始化失败')
        }
      }).finally(() => {
        this.knowledgeSeeding = false
      })
    },
    openKnowledgeDialog(row) {
      this.knowledgeForm = row ? Object.assign({}, row) : this.emptyKnowledgeForm()
      this.knowledgeDialogVisible = true
    },
    submitKnowledge() {
      if (!this.knowledgeForm.title || !this.knowledgeForm.content) {
        this.$message.warning('标题和内容不能为空')
        return
      }
      this.knowledgeSaving = true
      saveKnowledge(this.knowledgeForm).then(res => {
        if (res.code === '200') {
          this.$message.success('知识已保存')
          this.knowledgeDialogVisible = false
          this.loadKnowledge()
        } else {
          this.$message.error(res.msg || '保存失败')
        }
      }).finally(() => {
        this.knowledgeSaving = false
      })
    },
    removeKnowledge(row) {
      this.$confirm(`确认删除知识「${row.title}」？`, '提示', { type: 'warning' }).then(() => {
        return deleteKnowledge(row.id)
      }).then(res => {
        if (res.code === '200') {
          this.$message.success('已删除')
          this.loadKnowledge()
        } else {
          this.$message.error(res.msg || '删除失败')
        }
      }).catch(() => {})
    },
    timelineType(status) {
      const map = {
        COMPLETED: 'success',
        WAIT_CONFIRM: 'warning',
        BLOCKED: 'danger',
        CANCELLED: 'info'
      }
      return map[status] || 'primary'
    },
    pretty(value) {
      if (typeof value === 'string') return value
      return JSON.stringify(value, null, 2)
    }
  }
}
</script>

<style scoped>
.ai-assistant {
  padding-bottom: 16px;
}
.panel {
  border-radius: 6px;
}
.panel-title {
  font-weight: 600;
  color: #303133;
}
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.quick-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}
.quick-row .el-tag {
  cursor: pointer;
}
.task-policy {
  margin-bottom: 10px;
}
.task-example-group {
  padding: 10px 0;
  border-bottom: 1px dashed #ebeef5;
}
.task-example-group:last-of-type {
  border-bottom: none;
}
.example-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  color: #606266;
  font-size: 12px;
}
.knowledge-actions {
  display: flex;
  gap: 8px;
}
.full-button {
  margin-top: 12px;
  width: 100%;
}
.top0 {
  margin-top: 0;
}
.result-block h3 {
  margin: 16px 0 10px;
  font-size: 15px;
}
.answer-card {
  padding: 14px 16px;
  color: #303133;
  line-height: 1.8;
  white-space: pre-wrap;
  background: #f0f9eb;
  border: 1px solid #d9ecff;
  border-radius: 6px;
}
.answer-card.uncertain {
  background: #fdf6ec;
  border-color: #faecd8;
}
.task-progress {
  margin: 16px 0 4px;
}
.execution-alert {
  margin: 12px 0;
}
.draft-box pre {
  padding: 12px;
  background: #f6f8fa;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
}
.mini-stat {
  padding: 12px;
  color: #303133;
  text-align: center;
  background: #f6f8fa;
  border: 1px solid #ebeef5;
  border-radius: 6px;
}
.suggestions {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}
.missing-fields {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  color: #606266;
}
.danger-text {
  color: #f56c6c;
}
</style>
