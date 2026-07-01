import axios from "axios";
import router from "@/router";
import Cookies from "js-cookie";

const request = axios.create({
    baseURL: 'http://localhost:9090/api',
    timeout: 5000
})

// request 拦截器
// 可以自请求发送前对请求最一些处理

request.interceptors.request.use(config => {
    config.headers['Content-Type'] = 'application/json;charset=utf-8';

    const adminJson = Cookies.get('admin')
    if(adminJson) {
        // 设置请求头
        config.headers['token'] = JSON.parse(adminJson).token
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
                return {
                    code: '-1',
                    data: null,
                    msg: '接口响应格式异常，请刷新后重试'
                }
            }
        }
        if(res.code === '401') {
            router.push('/login')
        }
        return res;
    },
    error => {
        console.log('err' + error)
        return Promise.reject(error)
    }
)

export default request
