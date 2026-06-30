<template>
  <div>
    <div style="margin: 20px 0">
      <el-select class="input" v-model="timeRange" placeholder="请选择" @change="load"> // 从后台加载最新的数据
        <el-option
            v-for="item in options"
            :key="item.value"
            :label="item.label"
            :value="item.value">
        </el-option>
      </el-select>
    </div>
    <el-card>
      <div id="line" style="width: 100%; height: 400px"></div>
    </el-card>
  </div>
</template>

<script>
import Cookies from 'js-cookie'
import request from "@/utils/request";
import * as echarts from 'echarts'

const option = {
  title: {
    text: '图书借还统计',
    left: 'left',    // 可选 left / center / right
    top: '10px',     // 距离顶部距离
  },
  tooltip: {
    trigger: 'axis'
  },
  legend: {
    data: ['借书数量','还书数量'],
    top: '5%',    // 距离顶部多高
    right: '5%',  // 距离右侧多远
    left: 'auto'
  },
  grid: {
    left: '3%',
    right: '4%',
    bottom: '3%',
    containLabel: true
  },
  toolbox: {
    feature: {
      saveAsImage: {}
    }
  },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: []
  },
  yAxis: {
    type: 'value'
  },
  series: [
    {
      name: '借书数量',
      type: 'line',
      smooth: true,
      data: [] // 从后台动态获取
    },
    {
      name: '还书数量',
      type: 'line',
      stack: 'Total',
      smooth: true,
      data: [] // 从后台动态获取
    },
  ]
};

  export default {
    data() {
      return {
        admin: Cookies.get('admin') ? JSON.parse(Cookies.get('admin')) : {},
        lineBox: null,
        timeRange: 'week',
        options: [
          {label: '最近一周', value: 'week'},
          {label: '最近一个月', value: 'month'},
          {label: '最近两个月', value: 'month2'},
          {label: '最近三个月', value: 'month3'},
        ]
      }
    },
    mounted() { // 等页面元素全部初始化好后，才开始加载mounted函数
      this.load()
    },
    methods: {
      renderLineChart(data = {}) {
        option.xAxis.data = Array.isArray(data.date) ? data.date : []
        option.series[0].data = Array.isArray(data.borrow) ? data.borrow : []
        option.series[1].data = Array.isArray(data.restitution) ? data.restitution : []
        this.lineBox.setOption(option)
      },
      load() {
        if (!this.lineBox) {
          this.lineBox = echarts.init(document.getElementById('line')) //初始化echarts容器
        }
        // 从后台获取数据
        request.get('/borrow/lineCharts/' + this.timeRange).then(res => {
          if (res.code !== '200' || !res.data) {
            this.renderLineChart()
            this.$message.error(res.msg || '图表数据加载失败')
            return
          }
          this.renderLineChart(res.data)
        }).catch(() => {
          this.renderLineChart()
          this.$message.error('图表数据加载失败')
        })
      }
    }
  }
</script>

<style>
.input {
  width: 300px;
}
</style>
