import {createFrameResizeObserver} from './frameResizeObserver';

function setup() {
    let receive, sequence = 0;
    const frames = new Map();
    class Native {
        constructor(callback) {receive = callback;}
        observe() {}
        unobserve() {}
        disconnect() {}
    }
    const Observer = createFrameResizeObserver(Native, callback => {
        const id = ++sequence; frames.set(id, callback); return id;
    }, id => frames.delete(id));
    return {Observer, notify: entries => receive(entries), flush: () => {
        const batch = [...frames.values()]; frames.clear(); batch.forEach(callback => callback());
    }};
}

test('batches callbacks and keeps the latest measurement for each target', () => {
    const {Observer, notify, flush} = setup();
    const callback = jest.fn(), a = {}, b = {};
    const observer = new Observer(callback); observer.observe(a); observer.observe(b);
    notify([{target:a,width:100}]); notify([{target:a,width:120},{target:b,width:80}]);
    expect(callback).not.toHaveBeenCalled();
    flush();
    expect(callback).toHaveBeenCalledTimes(1);
    expect(callback).toHaveBeenCalledWith([{target:a,width:120},{target:b,width:80}], observer);
});

test('unobserve and disconnect cancel pending notifications without preventing reuse', () => {
    const {Observer, notify, flush} = setup();
    const callback = jest.fn(), a = {}, b = {};
    const observer = new Observer(callback); observer.observe(a); observer.observe(b);
    notify([{target:a},{target:b}]); observer.unobserve(a); flush();
    expect(callback).toHaveBeenLastCalledWith([{target:b}], observer);
    callback.mockClear(); notify([{target:b}]); observer.disconnect(); flush();
    expect(callback).not.toHaveBeenCalled();
    observer.observe(a); notify([{target:a}]); flush();
    expect(callback).toHaveBeenCalledWith([{target:a}], observer);
});

test('real application errors still surface', () => {
    const {Observer, notify, flush} = setup();
    const target = {};
    const observer = new Observer(() => {throw new Error('application error');});
    observer.observe(target); notify([{target}]);
    expect(flush).toThrow('application error');
});
