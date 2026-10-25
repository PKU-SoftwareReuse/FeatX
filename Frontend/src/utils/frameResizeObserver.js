// Deliver resize callbacks outside the native ResizeObserver delivery cycle.
// UI libraries can then update layout without nesting another resize pass in it.
// This does not catch or suppress errors thrown by application callbacks.
export function createFrameResizeObserver(NativeObserver, requestFrame, cancelFrame) {
    class FrameResizeObserver {
        constructor(callback) {
            if (typeof callback !== 'function') throw new TypeError('ResizeObserver callback must be a function');
            this.targets = new Set();
            this.pending = new Map();
            this.frame = null;
            this.observer = new NativeObserver(entries => {
                for (const entry of entries) {
                    if (this.targets.has(entry.target)) this.pending.set(entry.target, entry);
                }
                if (!this.pending.size || this.frame !== null) return;
                this.frame = requestFrame(() => {
                    this.frame = null;
                    const batch = [...this.pending.values()];
                    this.pending.clear();
                    if (batch.length) callback.call(this, batch, this);
                });
            });
        }
        observe(target, options) {
            this.observer.observe(target, options);
            this.targets.add(target);
        }
        unobserve(target) {
            this.observer.unobserve(target);
            this.targets.delete(target);
            this.pending.delete(target);
            if (!this.pending.size && this.frame !== null) {
                cancelFrame(this.frame);
                this.frame = null;
            }
        }
        disconnect() {
            this.observer.disconnect();
            this.targets.clear();
            this.pending.clear();
            if (this.frame !== null) cancelFrame(this.frame);
            this.frame = null;
        }
    }
    FrameResizeObserver.featxFrameScheduled = true;
    return FrameResizeObserver;
}

// Must run before Ant Design/Monaco/React Flow capture the observer constructor.
// The marker prevents nested adapters during hot reload.
if (typeof window !== 'undefined' && window.ResizeObserver && !window.ResizeObserver.featxFrameScheduled) {
    window.ResizeObserver = createFrameResizeObserver(
        window.ResizeObserver,
        window.requestAnimationFrame.bind(window),
        window.cancelAnimationFrame.bind(window),
    );
}
