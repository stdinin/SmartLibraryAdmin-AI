import axios from "axios";
import router from "@/router";
import Cookies from "js-cookie";

const request = axios.create({
    baseURL: 'http://localhost:9090/api',
    timeout: 5000
})

function redirectToLogin() {
    Cookies.remove('admin')
    if (router.currentRoute && router.currentRoute.path === '/login') return
    router.replace({
        path: '/login',
        query: { redirect: router.currentRoute ? router.currentRoute.fullPath : '/' }
    }).catch(() => {})
}

function errorResult(msg) {
    return {
        code: '-1',
        data: null,
        msg
    }
}

// request 拦截器
// 可以自请求发送前对请求最一些处理

request.interceptors.request.use(config => {
    config.headers['Content-Type'] = 'application/json;charset=utf-8';

    const adminJson = Cookies.get('admin')
    if(adminJson) {
        try {
            const admin = JSON.parse(adminJson)
            if (admin && admin.token) {
                config.headers['token'] = admin.token
            }
        } catch (e) {
            Cookies.remove('admin')
        }
    }
    return config
}, error => {
    return Promise.reject(error)
});

// response 拦截器
// 可以在接口响应后统一处理结果
request.interceptors.response.use(
    response => {
        let res = response.data;
        if(typeof res === 'string') {
            try {
                res = res ? JSON.parse(res) : res
            } catch (e) {
                console.error('接口响应不是合法JSON', res)
                return errorResult('接口响应格式异常，请刷新后重试')
            }
        }
        if(res.code === '401') {
            redirectToLogin()
            return errorResult('登录已过期，请重新登录')
        }
        return res;
    },
    error => {
        if (error.response && error.response.status === 401) {
            redirectToLogin()
            return Promise.resolve(errorResult('登录已过期，请重新登录'))
        }
        const msg = error.response && error.response.data && error.response.data.msg
            ? error.response.data.msg
            : (error.message || '接口请求失败')
        console.error('接口请求失败', error)
        return Promise.resolve(errorResult(msg))
    }
)

export default request
