import { createRouter, createWebHashHistory, createWebHistory } from 'vue-router'
import Disk from '@/views/Disk.vue';
import AITools from '@/views/AITools.vue';



export const routes = [
  {
    path: '/',
    component: Disk,
    children: [
      {
        path: '',
        redirect: '/home',
        meta: {
          showInMenu: false
        }
      },
      {
        path: 'home',
        name: 'home',
        component: () => import('@/modules/file-system/components/FilePage.vue'),
        meta: {
          title: '首页',
          icon: 'home',
        }
      },
      {
        path: 'share',
        name: 'share',
        component: () => import('@/modules/share-system/components/SharePage.vue'),
        meta: {
          title: '分享',
          icon: 'share',
        }
      },
      {
        path: 'recycle',
        name: 'recycle',
        component: () => import('@/modules/trash-system/components/TrashPage.vue'),
        meta: {
          title: '回收站',
          icon: 'trash',
        }
      },
    ]

  },
  {
    path: '/testUpload',
    name: 'testUP',
    component: () => import('@/components/Disk/testUploader.vue')
  },
  {
    path: '/testSSE',
    name: 'testSSE',
    component: () => import('@/components/AITools/testSSE.vue')
  },
  {
    path: '/ai',
    name: 'AITools',
    component: AITools
  },
  {
    path: '/share/:shareCode',
    name: 'ShareView',
    component: () => import('@/modules/share-system/components/ShareDetailPage.vue'),
    meta: { public: true } // 无需登录
  }
];



const router = createRouter({
  history: createWebHistory(),
  routes
})


export default router
