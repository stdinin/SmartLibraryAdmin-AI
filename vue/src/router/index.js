import Vue from 'vue'
import VueRouter from 'vue-router'
import Layout from '../views/Layout.vue'
import Cookies from "js-cookie";

Vue.use(VueRouter)

function isIgnoredNavigationError(error) {
  return error && (
    error.name === 'NavigationDuplicated' ||
    error.name === 'NavigationCancelled' ||
    /Navigation (cancelled|duplicated|aborted)/i.test(error.message || '')
  )
}

const originalPush = VueRouter.prototype.push
VueRouter.prototype.push = function push(location, onResolve, onReject) {
  if (onResolve || onReject) {
    return originalPush.call(this, location, onResolve, onReject)
  }
  return originalPush.call(this, location).catch(error => {
    if (isIgnoredNavigationError(error)) return error
    return Promise.reject(error)
  })
}

const originalReplace = VueRouter.prototype.replace
VueRouter.prototype.replace = function replace(location, onResolve, onReject) {
  if (onResolve || onReject) {
    return originalReplace.call(this, location, onResolve, onReject)
  }
  return originalReplace.call(this, location).catch(error => {
    if (isIgnoredNavigationError(error)) return error
    return Promise.reject(error)
  })
}

const routes = [
  // ==== 登录 ====
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/Login.vue'),
  },
  // ==== 主页 ====
  {
    path: '/',
    name: 'Layout',
    component: Layout,
    redirect: '/home',
    children: [ //子路由
      {
        path: 'home',
        name: 'Home',
        component: () => import('@/views/home/HomeView.vue')
      },
      //  ====  User  ====
      {
        path: 'userList',
        name: 'UserList',
        component: () => import('@/views/user/User.vue')
      },
      {
        path: 'addUser',
        name: 'AddUser',
        component: () => import('@/views/user/AddUser.vue')
      },
      {
        path: 'editUser',
        name: 'EditUser',
        component: () => import('@/views/user/EditUser.vue')
      },
      //  ====  Admin  ====
      {
        path: 'adminList',
        name: 'AdminList',
        component: () => import('@/views/admin/List.vue')
      },
      {
        path: 'addAdmin',
        name: 'AddAdmin',
        component: () => import('@/views/admin/Add.vue')
      },
      {
        path: 'editAdmin',
        name: 'EditAdmin',
        component: () => import('@/views/admin/Edit.vue') },
      //  ====  Category  ====
      { path: 'categoryList', name: 'CategoryList', component: () => import('@/views/category/List.vue') },
      { path: 'addCategory', name: 'AddCategory', component: () => import('@/views/category/Add.vue') },
      { path: 'editCategory', name: 'EditCategory', component: () => import('@/views/category/Edit.vue') },
      //  ====  Book  ====
      { path: 'bookList', name: 'BookList', component: () => import('@/views/book/List.vue') },
      { path: 'addBook', name: 'AddBook', component: () => import('@/views/book/Add.vue') },
      { path: 'editBook', name: 'EditBook', component: () => import('@/views/book/Edit.vue') },
      //  ====  Borrow  ====
      { path: 'borrowList', name: 'BorrowList', component: () => import('@/views/borrow/List.vue') },
      { path: 'addBorrow', name: 'AddBorrow', component: () => import('@/views/borrow/Add.vue') },
      { path: 'editBorrow', name: 'EditBorrow', component: () => import('@/views/borrow/Edit.vue') },
      //  ====  Return  ====
      { path: 'restitutionList', name: 'RestitutionList', component: () => import('@/views/restitution/List.vue') },
      //  ====  AI  ====
      { path: 'aiAssistant', name: 'AIAssistant', component: () => import('@/views/ai/Assistant.vue') },
    ]
  },
  {
    path: "*",
    component: () => import('@/views/404')
  }
]

const router = new VueRouter({
  mode: 'hash',
  base: process.env.BASE_URL,
  routes
})

router.beforeEach((to, from, next) => {
  if (to.path === '/login') {
    return next()
  }
  const adminJson = Cookies.get("admin")
  if (!adminJson) {
    return next({ path: "/login", query: { redirect: to.fullPath } })
  }
  try {
    const admin = JSON.parse(adminJson)
    if (!admin || !admin.token) {
      Cookies.remove("admin")
      return next({ path: "/login", query: { redirect: to.fullPath } })
    }
  } catch (e) {
    Cookies.remove("admin")
    return next({ path: "/login", query: { redirect: to.fullPath } })
  }
  return next()
})

export default router
