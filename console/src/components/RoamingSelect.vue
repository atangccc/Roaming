<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";

interface Option { label: string; value: string }

const props = withDefaults(defineProps<{
  modelValue: string;
  options: Option[];
  placeholder?: string;
}>(), { placeholder: "请选择" });

const emit = defineEmits<{ "update:modelValue": [value: string] }>();
const root = ref<HTMLElement>();
const trigger = ref<HTMLButtonElement>();
const open = ref(false);
const selectedLabel = computed(() => props.options.find((option) => option.value === props.modelValue)?.label || props.placeholder);

function toggle() {
  open.value = !open.value;
}

function choose(value: string) {
  emit("update:modelValue", value);
  open.value = false;
  nextTick(() => trigger.value?.focus());
}

function closeFromOutside(event: PointerEvent) {
  if (root.value && !event.composedPath().includes(root.value)) open.value = false;
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === "Escape") {
    open.value = false;
    trigger.value?.focus();
  }
}

onMounted(() => document.addEventListener("pointerdown", closeFromOutside, true));
onBeforeUnmount(() => document.removeEventListener("pointerdown", closeFromOutside, true));
</script>

<template>
  <div ref="root" class="roaming-select" :class="{ open }" @keydown="handleKeydown">
    <button
      ref="trigger"
      class="roaming-select__trigger"
      type="button"
      role="combobox"
      aria-haspopup="listbox"
      :aria-expanded="open"
      @click="toggle"
    >
      <span :class="{ placeholder: !modelValue }">{{ selectedLabel }}</span>
      <svg viewBox="0 0 20 20" aria-hidden="true"><path d="m6 8 4 4 4-4"/></svg>
    </button>
    <Transition name="roaming-options">
      <div v-if="open" class="roaming-select__options" role="listbox">
        <button
          v-for="option in options"
          :key="option.value"
          type="button"
          role="option"
          :aria-selected="option.value === modelValue"
          :class="{ selected: option.value === modelValue }"
          @click="choose(option.value)"
        >
          <span>{{ option.label }}</span>
          <svg v-if="option.value === modelValue" viewBox="0 0 20 20" aria-hidden="true"><path d="m5.5 10 3 3 6-6"/></svg>
        </button>
        <div v-if="!options.length" class="roaming-select__empty">暂无可选项</div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.roaming-select{position:relative;width:100%}.roaming-select__trigger{display:flex;width:100%;height:44px;align-items:center;justify-content:space-between;gap:12px;padding:0 13px 0 14px;border:1px solid #dadddf;border-radius:12px;background:#f8f9f9;color:#34383c;text-align:left;cursor:pointer;transition:border-color .16s ease,background .16s ease,box-shadow .16s ease}.roaming-select__trigger:hover,.open .roaming-select__trigger{border-color:#bfc4c8;background:#fff}.open .roaming-select__trigger{box-shadow:0 0 0 4px rgba(35,39,43,.07)}.roaming-select__trigger span{overflow:hidden;font-size:12px;font-weight:650;text-overflow:ellipsis;white-space:nowrap}.roaming-select__trigger span.placeholder{color:#a1a6ab;font-weight:500}.roaming-select__trigger svg{width:17px;flex:none;fill:none;stroke:#656b71;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round;transition:transform .16s ease}.open .roaming-select__trigger>svg{transform:rotate(180deg)}.roaming-select__options{position:absolute;z-index:60;top:calc(100% + 7px);right:0;left:0;max-height:240px;padding:6px;overflow:auto;border:1px solid #dfe2e4;border-radius:14px;background:rgba(255,255,255,.98);box-shadow:0 16px 38px rgba(20,23,27,.14),inset 0 1px #fff;scrollbar-width:none;backdrop-filter:blur(18px)}.roaming-select__options::-webkit-scrollbar{display:none}.roaming-select__options button{display:flex;width:100%;min-height:36px;align-items:center;justify-content:space-between;gap:10px;padding:7px 10px;border:0;border-radius:9px;background:transparent;color:#5d6369;text-align:left;cursor:pointer}.roaming-select__options button:hover{background:#f0f2f3;color:#262a2e}.roaming-select__options button.selected{background:#e8eaec;color:#24282c;font-weight:700}.roaming-select__options button span{overflow:hidden;font-size:11px;text-overflow:ellipsis;white-space:nowrap}.roaming-select__options button svg{width:15px;flex:none;fill:none;stroke:currentColor;stroke-width:1.9;stroke-linecap:round;stroke-linejoin:round}.roaming-select__empty{padding:12px;color:#9ba0a5;text-align:center;font-size:10px}.roaming-options-enter-active,.roaming-options-leave-active{transition:opacity .14s ease,transform .14s ease}.roaming-options-enter-from,.roaming-options-leave-to{opacity:0;transform:translateY(-4px) scale(.985)}
</style>
