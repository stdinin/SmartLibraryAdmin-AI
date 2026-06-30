<template>
  <div>
<!--    搜索表单-->
    <div style="margin-bottom: 20px">
      <el-input style="width: 240px" placeholder="请输入图书名称" v-model="params.name"></el-input>
      <el-input style="width: 240px; margin-left: 5px" placeholder="请输入图书标准码" v-model="params.bookNo"></el-input>
      <el-button style="margin-left: 5px" type="primary" @click="load"><i class="el-icon-search"></i>搜索</el-button>
      <el-button style="margin-left: 5px" type="warning" @click="reset"><i class="el-icon-refresh"></i>重置</el-button>
      <el-button style="margin-left: 5px" type="success" :loading="qualityLoading" @click="runQualityCheck">
        <i class="el-icon-data-analysis"></i>数据质检
      </el-button>
    </div>

    <el-table :data="tableData" stripe row-key="id" default-expand-all fit>
      <el-table-column prop="id" label="编号" width="50"></el-table-column>
      <el-table-column prop="name" label="图书名称"></el-table-column>
      <el-table-column prop="bookNo" label="标准码"></el-table-column>
      <el-table-column prop="description" label="描述" width="200"></el-table-column>
      <el-table-column prop="publishDate" label="出版日期"></el-table-column>
      <el-table-column prop="author" label="作者"></el-table-column>
      <el-table-column prop="publisher" label="出版社"></el-table-column>
      <el-table-column prop="category" label="分类"></el-table-column>
      <el-table-column prop="score" label="借书积分"></el-table-column>
      <el-table-column prop="nums" label="数量"></el-table-column>
      <el-table-column prop="cover" label="封面">
        <template v-slot="scope">
          <el-image :src="scope.row.cover" :preview-src-list="[scope.row.cover]">

          </el-image>
        </template>
      </el-table-column>
      <el-table-column prop="createtime" label="创建时间"></el-table-column>
      <el-table-column prop="updatetime" label="更新时间"></el-table-column>
      <el-table-column label="操作" width="150">
        <template v-slot="scope">
<!--          scope.row就是当前行数据-->
          <el-button type="primary" @click="$router.push('/editBook?id=' + scope.row.id)">编辑</el-button>
          <el-popconfirm
              style="margin-left: 5px"
              title="您确定删除这行数据吗？"
              @confirm="del(scope.row.id)"
          >
            <el-button type="danger" slot="reference">删除</el-button>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

<!--    分页-->
    <div style="margin-top: 20px">
      <el-pagination
          background
          :current-page="params.pageNum"
          :page-size="params.pageSize"
          layout="prev, pager, next"
          @current-change="handleCurrentChange"
          :total="total">
      </el-pagination>
    </div>

    <el-dialog title="馆藏数据智能质检" :visible.sync="qualityVisible" width="760px">
      <div v-if="qualityResult">
        <el-alert :title="qualityResult.aiSummary || qualityResult.summary" type="info" :closable="false" style="margin-bottom: 12px"></el-alert>
        <h3>缺失字段统计</h3>
        <el-table :data="missingFieldRows" size="small" border>
          <el-table-column prop="field" label="字段"></el-table-column>
          <el-table-column prop="count" label="数量"></el-table-column>
        </el-table>
        <h3>库存异常</h3>
        <el-table :data="qualityResult.stockAbnormalBooks || []" size="small" border>
          <el-table-column prop="name" label="图书"></el-table-column>
          <el-table-column prop="bookNo" label="标准码"></el-table-column>
          <el-table-column prop="nums" label="库存"></el-table-column>
          <el-table-column prop="reason" label="原因"></el-table-column>
        </el-table>
        <h3>分类异常</h3>
        <el-table :data="qualityResult.categoryAbnormalBooks || []" size="small" border>
          <el-table-column prop="name" label="图书"></el-table-column>
          <el-table-column prop="category" label="分类"></el-table-column>
          <el-table-column prop="reason" label="原因"></el-table-column>
        </el-table>
        <h3>整改建议</h3>
        <el-alert
            v-for="(item, index) in qualityResult.suggestions || []"
            :key="index"
            :title="item"
            type="success"
            :closable="false"
            style="margin-bottom: 8px">
        </el-alert>
      </div>
    </el-dialog>

  </div>
</template>

<script>
import request from "@/utils/request";
import Cookies from "js-cookie";
import { qualityCheckBooks } from "@/api/ai";

export default {
  name: 'BookList',
  data() {
    return {
      admin: Cookies.get('admin') ? JSON.parse(Cookies.get('admin')) : {},
      tableData: [],
      total: 0,
      qualityLoading: false,
      qualityVisible: false,
      qualityResult: null,
      params: {
        pageNum: 1,
        pageSize: 10,
        name: '',
        bookNo: ''
      },
    }
  },
  computed: {
    missingFieldRows() {
      const stats = this.qualityResult && this.qualityResult.missingFieldStats ? this.qualityResult.missingFieldStats : {}
      return Object.keys(stats).map(key => ({ field: key, count: stats[key] }))
    }
  },
  created() {
    this.load()
  },
  methods: {
    load() {
      request.get('/book/page', {
        params: this.params
      }).then(res => {
        if(res.code === '200') {
          this.tableData = res.data.list
          this.total = res.data.total
        }
      })
    },
    reset() {
      this.params = {
        pageNum: 1,
        pageSize: 10,
        name: '',
        bookNo: ''
      }
      this.load()
    },
    handleCurrentChange(pageNum) {
      this.params.pageNum = pageNum
      this.load()
    },
    runQualityCheck() {
      this.qualityLoading = true
      qualityCheckBooks().then(res => {
        if(res.code === '200') {
          this.qualityResult = res.data
          this.qualityVisible = true
        } else {
          this.$notify.error(res.msg || '数据质检失败')
        }
      }).finally(() => {
        this.qualityLoading = false
      })
    },
    del(id) {
      request.delete("/book/delete/" + id).then(res => {
        if(res.code === '200') {
          this.$notify.success('删除成功')
          this.load()
        } else {
          this.$notify.error(res.msg)
        }
      })
    }
  }
}
</script>

<style scoped>

</style>
