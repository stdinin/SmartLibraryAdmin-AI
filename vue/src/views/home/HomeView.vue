<template>
  <div class="home-dashboard">
    <div class="dashboard-toolbar">
      <div>
        <div class="page-title">借阅数据看板</div>
        <div class="page-subtitle">基于借阅、归还、馆藏库存和逾期记录生成管理视图</div>
      </div>
      <el-select class="range-select" v-model="timeRange" placeholder="请选择" @change="load">
        <el-option
            v-for="item in options"
            :key="item.value"
            :label="item.label"
            :value="item.value">
        </el-option>
      </el-select>
    </div>

    <el-row :gutter="16" class="metric-row">
      <el-col :xs="24" :sm="12" :md="6" v-for="item in metricCards" :key="item.label">
        <div class="metric-card" :class="item.type">
          <div class="metric-icon">
            <i :class="item.icon"></i>
          </div>
          <div>
            <div class="metric-value">{{ item.value }}</div>
            <div class="metric-label">{{ item.label }}</div>
          </div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <el-col :xs="24" :lg="16">
        <el-card shadow="never" class="dashboard-card">
          <div slot="header" class="card-header">
            <span>借还趋势</span>
            <el-tag size="mini" type="info">{{ currentRangeLabel }}</el-tag>
          </div>
          <div id="line" class="chart-box"></div>
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="8">
        <el-card shadow="never" class="dashboard-card">
          <div slot="header" class="card-header">
            <span>热门分类</span>
            <el-tag size="mini" type="success">Top 5</el-tag>
          </div>
          <div id="categoryChart" class="chart-box"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="analysis-row">
      <el-col :xs="24" :lg="10">
        <el-card shadow="never" class="dashboard-card">
          <div slot="header" class="card-header">
            <span>热门图书排行</span>
            <el-button type="text" size="mini" :loading="loading" @click="load">刷新</el-button>
          </div>
          <el-table :data="analysis.hotBooks || []" size="small" height="276">
            <el-table-column type="index" label="#" width="44"></el-table-column>
            <el-table-column prop="bookName" label="书名" show-overflow-tooltip></el-table-column>
            <el-table-column prop="count" label="借阅" width="80"></el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="14">
        <el-card shadow="never" class="dashboard-card">
          <div slot="header" class="card-header">
            <span>逾期风险</span>
            <el-tag size="mini" :type="overdueCount > 0 ? 'danger' : 'success'">
              {{ overdueCount }} 条
            </el-tag>
          </div>
          <el-table :data="analysis.overdueRisks || []" size="small" height="276">
            <el-table-column prop="bookName" label="图书" show-overflow-tooltip></el-table-column>
            <el-table-column prop="userName" label="读者" width="100"></el-table-column>
            <el-table-column prop="userPhone" label="电话" width="130"></el-table-column>
            <el-table-column prop="returnDate" label="应还日期" width="120"></el-table-column>
            <el-table-column prop="status" label="状态" width="90"></el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="analysis-row">
      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="dashboard-card">
          <div slot="header" class="card-header">
            <span>冷门馆藏</span>
            <el-tag size="mini" type="warning">{{ coldBookCount }} 本</el-tag>
          </div>
          <el-table :data="analysis.coldBooks || []" size="small" height="260">
            <el-table-column prop="name" label="书名" show-overflow-tooltip></el-table-column>
            <el-table-column prop="author" label="作者" width="130" show-overflow-tooltip></el-table-column>
            <el-table-column prop="category" label="分类" width="120"></el-table-column>
            <el-table-column prop="nums" label="库存" width="70"></el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="dashboard-card suggestion-card">
          <div slot="header" class="card-header">
            <span>管理建议</span>
            <el-tag size="mini" type="info">AI 辅助</el-tag>
          </div>
          <el-alert
              v-if="analysis.summary"
              :title="analysis.summary"
              type="info"
              :closable="false"
              show-icon>
          </el-alert>
          <div class="suggestion-list">
            <div
                class="suggestion-item"
                v-for="(item, index) in analysis.suggestions || []"
                :key="index">
              <span class="suggestion-index">{{ index + 1 }}</span>
              <span>{{ item }}</span>
            </div>
          </div>
          <el-empty
              v-if="!analysis.summary && !(analysis.suggestions && analysis.suggestions.length)"
              description="暂无分析建议">
          </el-empty>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script>
import Cookies from 'js-cookie'
import request from '@/utils/request'
import { analyzeBorrow } from '@/api/ai'
import * as echarts from 'echarts'

export default {
  data() {
    return {
      admin: Cookies.get('admin') ? JSON.parse(Cookies.get('admin')) : {},
      lineBox: null,
      categoryBox: null,
      trendData: {},
      analysis: {},
      loading: false,
      timeRange: 'week',
      options: [
        {label: '最近一周', value: 'week'},
        {label: '最近一个月', value: 'month'},
        {label: '最近两个月', value: 'month2'},
        {label: '最近三个月', value: 'month3'}
      ]
    }
  },
  computed: {
    currentRangeLabel() {
      const item = this.options.find(option => option.value === this.timeRange)
      return item ? item.label : '当前周期'
    },
    borrowTotal() {
      return this.sum(this.trendData.borrow)
    },
    restitutionTotal() {
      return this.sum(this.trendData.restitution)
    },
    overdueCount() {
      return (this.analysis.overdueRisks || []).length
    },
    coldBookCount() {
      return (this.analysis.coldBooks || []).length
    },
    metricCards() {
      return [
        {label: '借阅总量', value: this.borrowTotal, icon: 'el-icon-reading', type: 'blue'},
        {label: '归还总量', value: this.restitutionTotal, icon: 'el-icon-refresh-left', type: 'green'},
        {label: '逾期风险', value: this.overdueCount, icon: 'el-icon-warning-outline', type: 'red'},
        {label: '冷门馆藏', value: this.coldBookCount, icon: 'el-icon-collection', type: 'amber'}
      ]
    }
  },
  mounted() {
    this.lineBox = echarts.init(document.getElementById('line'))
    this.categoryBox = echarts.init(document.getElementById('categoryChart'))
    window.addEventListener('resize', this.resizeCharts)
    this.load()
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.resizeCharts)
    if (this.lineBox) this.lineBox.dispose()
    if (this.categoryBox) this.categoryBox.dispose()
  },
  methods: {
    load() {
      this.loading = true
      Promise.all([this.loadTrend(), this.loadAnalysis()]).finally(() => {
        this.loading = false
      })
    },
    loadTrend() {
      return request.get('/borrow/lineCharts/' + this.timeRange).then(res => {
        if (res.code !== '200' || !res.data) {
          this.trendData = {}
          this.renderLineChart()
          this.$message.error(res.msg || '图表数据加载失败')
          return
        }
        this.trendData = res.data
        this.renderLineChart(res.data)
      }).catch(() => {
        this.trendData = {}
        this.renderLineChart()
        this.$message.error('图表数据加载失败')
      })
    },
    loadAnalysis() {
      return analyzeBorrow(this.normalizeAiRange(this.timeRange)).then(res => {
        if (res.code !== '200' || !res.data) {
          this.analysis = {}
          this.renderCategoryChart()
          this.$message.error(res.msg || '借阅分析加载失败')
          return
        }
        this.analysis = res.data
        this.renderCategoryChart(res.data.hotCategories || [])
      }).catch(() => {
        this.analysis = {}
        this.renderCategoryChart()
        this.$message.error('借阅分析加载失败')
      })
    },
    normalizeAiRange(range) {
      if (range === 'week') return 'week'
      if (range === 'month3') return 'quarter'
      return 'month'
    },
    renderLineChart(data = {}) {
      const date = Array.isArray(data.date) ? data.date : []
      const borrow = Array.isArray(data.borrow) ? data.borrow : []
      const restitution = Array.isArray(data.restitution) ? data.restitution : []
      this.lineBox.setOption({
        color: ['#3a7afe', '#21b978'],
        tooltip: {trigger: 'axis'},
        legend: {data: ['借书数量', '还书数量'], top: 0, right: 8},
        grid: {left: 36, right: 24, top: 48, bottom: 28, containLabel: true},
        xAxis: {
          type: 'category',
          boundaryGap: false,
          data: date,
          axisLine: {lineStyle: {color: '#dcdfe6'}},
          axisLabel: {color: '#606266'}
        },
        yAxis: {
          type: 'value',
          minInterval: 1,
          splitLine: {lineStyle: {color: '#edf0f5'}},
          axisLabel: {color: '#606266'}
        },
        series: [
          {
            name: '借书数量',
            type: 'line',
            smooth: true,
            symbolSize: 7,
            areaStyle: {color: 'rgba(58, 122, 254, 0.12)'},
            data: borrow
          },
          {
            name: '还书数量',
            type: 'line',
            smooth: true,
            symbolSize: 7,
            areaStyle: {color: 'rgba(33, 185, 120, 0.10)'},
            data: restitution
          }
        ]
      })
    },
    renderCategoryChart(rows = []) {
      const data = rows.map(item => ({
        name: item.category || item.name || '未分类',
        value: item.count || 0
      }))
      this.categoryBox.setOption({
        color: ['#3a7afe', '#21b978', '#ffb020', '#f56c6c', '#7c8da6'],
        tooltip: {trigger: 'item'},
        legend: {bottom: 0, type: 'scroll'},
        series: [
          {
            name: '热门分类',
            type: 'pie',
            radius: ['42%', '68%'],
            center: ['50%', '43%'],
            avoidLabelOverlap: true,
            label: {formatter: '{b}\n{c} 次'},
            data
          }
        ]
      })
    },
    resizeCharts() {
      if (this.lineBox) this.lineBox.resize()
      if (this.categoryBox) this.categoryBox.resize()
    },
    sum(values) {
      return Array.isArray(values) ? values.reduce((total, item) => total + Number(item || 0), 0) : 0
    }
  }
}
</script>

<style scoped>
.home-dashboard {
  padding: 4px 0 18px;
}

.dashboard-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 10px 0 18px;
}

.page-title {
  font-size: 22px;
  font-weight: 700;
  color: #202b3c;
}

.page-subtitle {
  margin-top: 6px;
  color: #7c8da6;
  font-size: 13px;
}

.range-select {
  width: 180px;
}

.metric-row {
  margin-bottom: 16px;
}

.metric-card {
  min-height: 94px;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px;
  margin-bottom: 16px;
  background: #fff;
  border: 1px solid #e7ecf3;
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(32, 43, 60, 0.06);
}

.metric-icon {
  width: 46px;
  height: 46px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  font-size: 22px;
}

.metric-card.blue .metric-icon {
  color: #3a7afe;
  background: #edf4ff;
}

.metric-card.green .metric-icon {
  color: #21b978;
  background: #eaf8f1;
}

.metric-card.red .metric-icon {
  color: #f56c6c;
  background: #fff0f0;
}

.metric-card.amber .metric-icon {
  color: #d48806;
  background: #fff7e6;
}

.metric-value {
  color: #202b3c;
  font-size: 28px;
  line-height: 1;
  font-weight: 700;
}

.metric-label {
  margin-top: 8px;
  color: #7c8da6;
  font-size: 13px;
}

.dashboard-card {
  margin-bottom: 16px;
  border-radius: 8px;
  border-color: #e7ecf3;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
  color: #202b3c;
}

.chart-box {
  width: 100%;
  height: 360px;
}

.analysis-row {
  margin-top: 0;
}

.suggestion-card {
  min-height: 344px;
}

.suggestion-list {
  margin-top: 14px;
}

.suggestion-item {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 10px 0;
  color: #364153;
  line-height: 1.6;
  border-bottom: 1px solid #edf0f5;
}

.suggestion-index {
  width: 22px;
  height: 22px;
  flex: 0 0 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: #fff;
  background: #3a7afe;
  font-size: 12px;
  line-height: 22px;
}

@media (max-width: 768px) {
  .dashboard-toolbar {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
  }

  .range-select {
    width: 100%;
  }

  .chart-box {
    height: 300px;
  }
}
</style>
