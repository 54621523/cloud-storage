import './assets/main.css'
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import 'element-plus/dist/index.css'
import request from './utils/request.ts'
import ElementPlus from 'element-plus';
import { VueQueryPlugin } from '@tanstack/vue-query'

// ============ FontAwesome 全量导入 ============
import { library } from '@fortawesome/fontawesome-svg-core'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { fas } from '@fortawesome/free-solid-svg-icons'
import { far } from '@fortawesome/free-regular-svg-icons'

// 一次性导入所有 solid, regular 图标
library.add(fas, far)
// ==========================================

const app = createApp(App)
app.use(createPinia());
app.use(router)
app.use(ElementPlus)
app.use(VueQueryPlugin)
app.component('font-awesome-icon', FontAwesomeIcon)
app.mount("#app")
app.config.globalProperties.$http = request;
