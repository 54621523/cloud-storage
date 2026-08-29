<!-- src/components/Sidebar.vue -->
<template>
  <aside class="sidebar">
    <div class="logo-area">
      <div class="logo-icon"><font-awesome-icon :icon="['fas', 'cloud']" /></div>
      <div class="logo-text">云<span>盘</span></div>
    </div>

    <div class="menu-list">
      <div class="menu-item" v-for="item in menuItems" :key="item.path" :class="{ active: isActive(item.path) }"
        @click="navigateTo(item.path)">
        <font-awesome-icon :icon="getIcon(item.icon)" class="menu-icon" />
        <span class="menu-text">{{ item.displayName }}</span>
      </div>
    </div>


    <div class="sidebar-footer" @click="handleUserAreaClick">
      <div class="avatar"><font-awesome-icon :icon="['fas', isLoggedIn ? 'user' : 'sign-in-alt']" /></div>
      <div class="user-info">
        <div class="name">{{ displayName }}</div>
        <div style="font-size: 12px; color: #6a7f9c;">{{ displaySub }}</div>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useUserStore } from '@/stores/userstore';
import { storeToRefs } from 'pinia';
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome';
import {
  faHome,
  faShareAlt,
  faTrash
} from '@fortawesome/free-solid-svg-icons';


const router = useRouter();
const route = useRoute()
const userStore = useUserStore();
const { isLoggedIn, userInfo } = storeToRefs(userStore);


const menuItems = ref([])
const fetchMenuItems = async () => {
  const rootRoute = router.options.routes.find(r => r.path === '/')
  const routes = rootRoute?.children || []
  menuItems.value = routes
    .filter(child =>
      child.meta?.showInMenu !== false
    )
    .map(child => ({
      path: child.path,
      displayName: child.meta?.title || child.name || '未命名',
      icon: child.meta?.icon,
      meta: child.meta
    }))
}

const navigateTo = (path) => {
  router.push(path)
}

const isActive = (path) => {
  return route.path === `/${path}`
}

const iconMap = {
  'home': faHome,
  'share': faShareAlt,
  'trash': faTrash
}

const getIcon = (iconName) => {
  return iconMap[iconName] || faHome
}



const props = defineProps({
  currentPage: {
    type: String,
    default: 'home'
  }
});

const emit = defineEmits(['page-change']);


const displayName = computed(() => {
  return isLoggedIn.value ? userInfo.value?.name || '用户' : '登录 / 注册';
});

const displaySub = computed(() => {
  if (isLoggedIn.value && userInfo.value) {
    const used = (userInfo.value.usedSpace / 1024).toFixed(1); // 转为 GB
    const total = (userInfo.value.totalSpace / 1024).toFixed(1);
    return `已用 ${used}G / ${total}G`;
  }
  return '点击登录';
})

const handleUserAreaClick = () => {
  userStore.toggleLoginModal(true);
};

onMounted(async () => {
  fetchMenuItems()
})
</script>

<style scoped>
.sidebar {
  width: 200px;
  background: white;
  padding: 28px 16px;
  display: flex;
  flex-direction: column;
  border-right: 1px solid #edf1f9;
  flex-shrink: 0;
}

.logo-area {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 28px;
  margin-bottom: 10px;
  border-bottom: 1px solid #edf1f9;
}

.logo-icon {
  width: 36px;
  height: 36px;
  background: #4f7cff;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-size: 18px;
}

.logo-text {
  font-size: 20px;
  font-weight: 600;
  color: #1e2d46;
}

.logo-text span {
  color: #4f7cff;
}

.sidebar-footer {
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 12px;
  padding-top: 20px;
  border-top: 1px solid #edf1f9;
  margin-top: auto;
  user-select: none;
}

.menu-list {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-top: 4px;
}

.menu-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-radius: 10px;
  color: #3a5270;
  font-weight: 500;
  font-size: 15px;
  cursor: pointer;
  transition: all 0.15s;
  position: relative;
}

.menu-item:hover {
  background: #f0f4fc;
}

.menu-item.active {
  background: #eef3fe;
  color: #1a5cff;
}

.menu-item svg {
  width: 18px;
}

.menu-item .badge {
  font-size: 10px;
  background: #4f7cff;
  color: white;
  padding: 1px 8px;
  border-radius: 10px;
  margin-left: auto;
  font-weight: 600;
}

.avatar {
  width: 36px;
  height: 36px;
  background: #4f7cff;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-size: 16px;
}

.user-info .name {
  font-weight: 500;
  color: #1a2332;
}
</style>