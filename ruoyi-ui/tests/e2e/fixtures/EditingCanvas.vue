<template>
  <section>
    <el-input :model-value="modelValue" type="textarea" aria-label="画布内容" @update:model-value="edit" />
    <el-button @click="$emit('save', modelValue)">提交正式画布</el-button>
  </section>
</template>

<script setup>
// Explicit test adapter for ProcessDesigner's existing public port. The page,
// HTTP module, Element Plus dialogs and router guards remain production code.
const props = defineProps({ modelValue: { type: String, default: '' } })
const emit = defineEmits(['update:modelValue', 'editing', 'save', 'loaded'])
function edit(xml) { emit('update:modelValue', xml); emit('editing') }
async function flushXml() { return props.modelValue }
defineExpose({ flushXml })
onMounted(() => emit('loaded', props.modelValue))
</script>
