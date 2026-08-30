  <!-- components/ChatInput.vue -->
  <template>
    <div class="chat-input-area">
      <div class="input-wrapper">
        <textarea v-model="inputMessage" @keydown.enter.prevent="handleEnter" :disabled="disabled"
          placeholder="输入您的问题... (Enter 发送，Shift+Enter 换行)" ref="textareaRef"></textarea>
        <div class="input-actions">
          <button class="send-btn" @click="handleSend" :disabled="!inputMessage.trim() || disabled">
            <font-awesome-icon :icon="['fas', 'paper-plane']" />
          </button>
        </div>
      </div>
    </div>
  </template>

<script setup>
import { ref, watch, nextTick } from 'vue';

const props = defineProps({
  disabled: Boolean
});
const emit = defineEmits(['send']);

const inputMessage = ref('');
const textareaRef = ref(null);

// 自动高度调整
const autoResize = () => {
  if (textareaRef.value) {
    textareaRef.value.style.height = 'auto';
    textareaRef.value.style.height = textareaRef.value.scrollHeight + 'px';
  }
};

watch(inputMessage, () => {
  nextTick(autoResize);
});

const handleEnter = (e) => {
  if (!e.shiftKey) {
    handleSend();
  }
};

const handleSend = () => {
  const content = inputMessage.value.trim();
  if (content) {
    emit('send', content);
    inputMessage.value = '';
    nextTick(autoResize); // 发送后重置高度
  }
};
</script>

<style scoped>
/* 把 ChatMain 里原来的 .chat-input-area 等样式复制过来 */
.chat-input-area {
  border-top: 1px solid #edf1f9;
  padding: 16px 24px;
  background: white;
  flex-shrink: 0;
}

.input-wrapper {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  background: #f8faff;
  border-radius: 16px;
  padding: 8px 12px;
  border: 2px solid transparent;
  transition: all 0.2s;
}

.input-wrapper:focus-within {
  border-color: #4f7cff;
  background: white;
}

.input-wrapper textarea {
  flex: 1;
  border: none;
  background: transparent;
  outline: none;
  resize: none;
  font-size: 14px;
  color: #1a2332;
  line-height: 1.5;
  max-height: 120px;
  min-height: 24px;
  padding: 4px 0;
  font-family: inherit;
}

.input-actions {
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}

.send-btn {
  background: #4f7cff;
  color: white;
  border: none;
  padding: 6px 14px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 18px;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
}

.send-btn:hover:not(:disabled) {
  background: #3a66e0;
  transform: scale(1.05);
}

.send-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
</style>