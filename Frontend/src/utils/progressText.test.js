import {formatLocalizedDuration, localizeSummaryStatus, localizeSummaryProgressMessage, localizeSummaryStageLabel} from './progressText';
test('状态和时间统一使用中文', () => {
    expect(localizeSummaryStatus('running')).toBe('进行中');
    expect(localizeSummaryStatus('complete')).toBe('已完成');
    expect(formatLocalizedDuration(65000)).toBe('1 分 5 秒');
    expect(formatLocalizedDuration(null)).toBe('—');
});
test('服务返回的阶段和说明直接显示', () => {
    expect(localizeSummaryStageLabel({name:'保存结果'})).toBe('保存结果');
    expect(localizeSummaryProgressMessage({displayMessage:'正在处理第 2 个主题'})).toBe('正在处理第 2 个主题');
});
