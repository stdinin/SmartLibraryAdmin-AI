<template>
  <div class="ai-assistant">
    <el-row :gutter="16">
      <el-col :span="8">
        <el-card shadow="never">
          <div slot="header">管理员智能助手</div>
          <el-tabs v-model="mode">
            <el-tab-pane label="知识问答" name="chat">
              <el-input
                  type="textarea"
                  :rows="8"
                  v-model="question"
                  placeholder="请输入关于借阅规则、分类规范、系统操作的问题">
              </el-input>
              <el-button type="primary" style="margin-top: 12px; width: 100%" :loading="chatLoading" @click="sendQuestion">
                提问
              </el-button>
            </el-tab-pane>
            <el-tab-pane label="任务规划" name="agent">
              <el-input
                  type="textarea"
                  :rows="8"
                  v-model="task"
                  placeholder="请输入后台任务，例如：帮我找出库存为0的图书">
              </el-input>
              <el-button type="success" style="margin-top: 12px; width: 100%" :loading="agentLoading" @click="createPlan">
                生成方案
              </el-button>
            </el-tab-pane>
          </el-tabs>
        </el-card>
      </el-col>

      <el-col :span="16">
        <el-card shadow="never">
          <div slot="header">AI 结果</div>
          <el-empty v-if="!chatResult && !agentResult" description="暂无结果"></el-empty>

          <div v-if="chatResult">
            <h3>回答</h3>
            <div class="answer">{{ chatResult.answer }}</div>
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

          <div v-if="agentResult" style="margin-top: 20px">
            <h3>操作方案</h3>
            <el-descriptions :column="2" border>
              <el-descriptions-item label="意图">{{ agentResult.intent }}</el-descriptions-item>
              <el-descriptions-item label="风险">{{ agentResult.risk_level }}</el-descriptions-item>
              <el-descriptions-item label="需确认">{{ agentResult.need_confirm ? '是' : '否' }}</el-descriptions-item>
              <el-descriptions-item label="阻断原因">{{ agentResult.blockedReason || '无' }}</el-descriptions-item>
            </el-descriptions>
            <h3>步骤</h3>
            <el-steps direction="vertical" :active="(agentResult.steps || []).length">
              <el-step v-for="(step, index) in agentResult.steps || []" :key="index" :title="step"></el-step>
            </el-steps>
            <h3>工具</h3>
            <el-tag v-for="tool in agentResult.tools_to_call || []" :key="tool" style="margin-right: 8px">
              {{ tool }}
            </el-tag>
            <div v-if="agentResult.draft_result" style="margin-top: 16px">
              <h3>草案/分析结果</h3>
              <pre>{{ agentResult.draft_result }}</pre>
            </div>
            <el-button
                v-if="agentResult.need_confirm"
                type="warning"
                style="margin-top: 12px"
                :loading="confirmLoading"
                @click="confirmPlan">
              确认方案
            </el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script>
import { agentConfirm, agentPlan, ragChat } from '@/api/ai'

export default {
  name: 'Assistant',
  data() {
    return {
      mode: 'chat',
      question: '',
      task: '',
      chatResult: null,
      agentResult: null,
      chatLoading: false,
      agentLoading: false,
      confirmLoading: false
    }
  },
  methods: {
    sendQuestion() {
      if (!this.question) {
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
      }).finally(() => {
        this.chatLoading = false
      })
    },
    createPlan() {
      if (!this.task) {
        this.$message.warning('请输入任务')
        return
      }
      this.agentLoading = true
      agentPlan({ task: this.task }).then(res => {
        if (res.code === '200') {
          this.agentResult = res.data
          this.chatResult = null
        } else {
          this.$message.error(res.msg || '方案生成失败')
        }
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
          this.$alert(res.data.blockedReason || '已处理', '确认结果')
        } else {
          this.$message.error(res.msg || '确认失败')
        }
      }).finally(() => {
        this.confirmLoading = false
      })
    }
  }
}
</script>

<style scoped>
.ai-assistant h3 {
  margin: 16px 0 10px;
  font-size: 15px;
}
.answer {
  line-height: 1.8;
  color: #303133;
}
pre {
  padding: 12px;
  background: #f6f8fa;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
