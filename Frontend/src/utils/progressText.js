const statusText = {pending:'等待中',queued:'等待中',running:'进行中',complete:'已完成',done:'已完成',failed:'失败'};
export const localizeSummaryStatus = status => statusText[status] || '未知';
export const localizeSummaryStageLabel = step => step?.name || '处理阶段';
export const localizeSummaryProgressMessage = progress => progress?.displayMessage || progress?.message
    || (progress?.status === 'failed' ? progress?.error || '总结失败' : localizeSummaryStatus(progress?.status));
export const localizeSummaryStepDetail = step => step?.message || step?.error || localizeSummaryStatus(step?.status);
export const formatLocalizedDuration = milliseconds => {
    if (milliseconds == null) return '—';
    const seconds = Math.max(0, Math.floor(Number(milliseconds) / 1000));
    if (!Number.isFinite(seconds)) return '—';
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    const rest = seconds % 60;
    if (hours) return `${hours} 小时 ${minutes} 分 ${rest} 秒`;
    if (minutes) return `${minutes} 分 ${rest} 秒`;
    return `${seconds} 秒`;
};
export const localizeOperationProgressMessage = progress => {
    if (progress?.message) return progress.message;
    if (progress?.failed || progress?.stage === 'failed') return progress?.error || '操作失败';
    const stage = progress?.stage || progress?.messageKey?.replace('progress.operation.', '');
    return {submit:'正在提交请求',start:'正在准备上下文',complete:'上下文已就绪',
        'generation-complete':'代码生成完成，请检查修改', 'agent-stream':'正在生成代码修改'}[stage] || '正在处理请求';
};
