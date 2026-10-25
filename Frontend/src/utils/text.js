// 展示字段统一使用单一值，不再根据界面语言选择字段。
export const getDisplayField = (record, key) => record?.[key] ?? '';
