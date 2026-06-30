<template>
  <div style="width: 80%">
    <div style="margin-bottom: 30px">新增图书</div>
    <el-form :inline="true" :rules="rules" ref="ruleForm" :model="form" label-width="100px">
      <el-form-item label="名称" prop="name">
        <el-input v-model="form.name" placeholder="请输入名称"></el-input>
      </el-form-item>
      <el-form-item label="描述" prop="description">
        <el-input style="width: 400px" type="textarea" v-model="form.description" placeholder="请输入描述"></el-input>
      </el-form-item>
      <el-form-item label="出版日期" prop="publishDate">
        <el-date-picker
          v-model="form.publishDate"
          type="date"
          value-format="yyyy-MM-dd"
          placeholder="请选择出版日期">
        </el-date-picker>
      </el-form-item>
      <el-form-item label="作者" prop="author">
        <el-input v-model="form.author" placeholder="请输入名称"></el-input>
      </el-form-item>
      <el-form-item label="出版社" prop="publisher">
        <el-input v-model="form.publisher" placeholder="请输入出版社"></el-input>
      </el-form-item>
      <el-form-item label="分类" prop="category">
        <el-cascader
          style="width: 220px"
          :props="{ value: 'name', label: 'name'}"
          v-model="form.categories"
          :options="categories"></el-cascader>
      </el-form-item>
      <el-form-item label="标准码" prop="bookNo">
        <el-input v-model="form.bookNo" placeholder="请输入标准码"></el-input>
      </el-form-item>
      <el-form-item label="借书积分" prop="cover">
        <el-input-number v-model="form.score" :min="10" :max="30" label="所需积分"></el-input-number>
      </el-form-item>
      <el-form-item label="数量" prop="nums">
        <el-input v-model="form.nums" placeholder="请输入数量"></el-input>
      </el-form-item>
      <br>
      <el-form-item label="封面" prop="cover">
        <el-input v-model="form.cover" placeholder="请输入封面"></el-input>
        <el-upload
            class="avatar-uploader"
            :action="'http://localhost:9090/api/book/file/upload?token=' + this.admin.token"
            :show-file-list="false"
            :on-success="handleCoverSuccess"
        >
          <img v-if="form.cover" :src="form.cover" class="avatar">
          <i v-else class="el-icon-plus avatar-uploader-icon"></i>
        </el-upload>
      </el-form-item>
    </el-form>

    <div style="text-align: center; margin-top: 30px">
      <el-button type="success" @click="generateAiCatalog" :loading="aiLoading" size="medium">AI 智能编目</el-button>
      <el-button type="primary" @click="save" size="medium">提交</el-button>
    </div>

    <el-dialog title="AI 编目建议" :visible.sync="aiDialogVisible" width="560px">
      <el-descriptions :column="1" border v-if="aiSuggestion">
        <el-descriptions-item label="建议分类">{{ aiSuggestion.category }}</el-descriptions-item>
        <el-descriptions-item label="关键词">
          <el-tag v-for="tag in aiSuggestion.tags || []" :key="tag" style="margin-right: 6px">{{ tag }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="简介">{{ aiSuggestion.summary }}</el-descriptions-item>
        <el-descriptions-item label="适合读者">{{ aiSuggestion.suitableReaders }}</el-descriptions-item>
        <el-descriptions-item label="推荐理由">{{ aiSuggestion.recommendReason }}</el-descriptions-item>
      </el-descriptions>
      <span slot="footer">
        <el-button @click="aiDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="applyAiSuggestion">应用建议</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import request from "@/utils/request";
import Cookies from "js-cookie";
import { generateCatalog } from "@/api/ai";
export default {
  name: 'AddBook',
  data() {
    const checkNums = (rule, value, callback) => {
      value = parseInt(value)
      if (value < 0 || value >= 1000) {
        callback(new Error('请输入大于等于0小于1000的整数'));
      }
      callback()
    };
    return {
      admin: Cookies.get('admin') ? JSON.parse(Cookies.get('admin')) : {},
      form: { score: 10, cover: '' },
      categories: [],
      aiLoading: false,
      aiDialogVisible: false,
      aiSuggestion: null,
      rules: {
        name: [
          {required: true, message: '请输入图书名称', trigger: 'blur'},
        ],
        bookNo: [
          {required: true, message: '请输入图书标准码', trigger: 'blur'},
        ],
        score: [
          {validator: checkNums, trigger: 'blur'},
        ],
        nums: [
          {required: true, message: '请输入数量', trigger: 'blur'},
          {validator: checkNums, trigger: 'blur'},
        ]
      }
    }
  },
  created() {
    request.get('/category/tree').then(res => {
      this.categories = res.data
    })
  },
  methods: {
    handleCoverSuccess(res) {
      if(res.code === '200') {
        console.log(res.data)
        // this.$set(this.form, 'cover', res.data)
        this.form.cover = res.data
      }
    },
    generateAiCatalog() {
      if(!this.form.name) {
        this.$message.warning('请先输入图书名称')
        return
      }
      this.aiLoading = true
      generateCatalog({
        title: this.form.name,
        author: this.form.author,
        publisher: this.form.publisher,
        isbn: this.form.bookNo,
        description: this.form.description
      }).then(res => {
        if(res.code === '200') {
          this.aiSuggestion = res.data
          this.aiDialogVisible = true
        } else {
          this.$notify.error(res.msg || 'AI编目失败')
        }
      }).finally(() => {
        this.aiLoading = false
      })
    },
    applyAiSuggestion() {
      if(!this.aiSuggestion) return
      if(this.aiSuggestion.summary) {
        this.form.description = this.aiSuggestion.summary
      }
      if(this.aiSuggestion.category) {
        this.form.categories = this.aiSuggestion.category.split(' > ')
      }
      this.aiDialogVisible = false
      this.$notify.success('已应用AI建议，请确认后提交')
    },
    save() {
      this.$refs['ruleForm'].validate((valid) => {
        if(valid) {
          request.post('/book/save', this.form).then(res => {
            if(res.code === '200') {
              this.$notify.success('新增成功')
              this.$refs['ruleForm'].resetFields()
            } else {
              this.$notify.error(res.msg)
            }
          })
        }
      })
    },
  }
}

</script>

<style>
.avatar-uploader .el-upload {
  border: 1px dashed #d9d9d9;
  border-radius: 6px;
  cursor: pointer;
  position: relative;
  overflow: hidden;
}
.avatar-uploader .el-upload:hover {
  border-color: #409EFF;
}
.avatar-uploader-icon {
  font-size: 28px;
  color: #8c939d;
  width: 178px;
  height: 178px;
  line-height: 178px;
  text-align: center;
}
.avatar {
  width: 178px;
  height: 178px;
  display: block;
}
</style>
