/**
 * 浏览器控制台调试脚本
 * 流程：点击 dev-tester 分组的按钮 → 在弹窗输入框中填写文本 → 点击弹窗“确认”按钮
 *
 * 用法（F12 控制台粘贴运行一次后）：
 *   await devTestSubmit('要输入的内容');
 *   await devTestSubmit('要输入的内容', { enableTimeout: 8000 }); // 等按钮解禁最多 8s
 *   await devTestSubmit('要输入的内容', { force: true });          // 按钮始终禁用时强制点击
 *
 * 说明：
 *   1. 所有元素都带“出现等待”，元素暂未渲染到 DOM 时不会直接报空。
 *   2. 输入框通过原生 value setter + input/change 事件写入，能触发 Vue(v-model)/React 的
 *      数据绑定；直接 el.value = xxx 不会触发框架响应。
 *   3. 你给的确认按钮选择器是 `button.btn.primary.disabled`，其中 `.disabled` 正是“未就绪”
 *      状态；等它可用后这个选择器就不匹配了，所以这里用去掉 `.disabled` 的基类选择器，并
 *      轮询等待 disabled 状态解除再点击。
 */
(() => {
  'use strict';

  const SEL = {
    // 打开 dev-tester 分组的按钮
    open: '#app > div.app-shell > main > section > div.manage-layout > div > div > div > div > div:nth-child(4) > div.dev-tester-groups > div > div.dev-tester-block-head > button',
    // 弹窗输入框
    input: 'body > div.modal-root.app-dialog-root > section > div.editor-body.qbot-dialog-body > div > div > input[type=text]',
    // 弹窗底部与确认按钮（注意：不含 .disabled，禁用状态单独判断）
    foot: '.modal-foot.qbot-dialog-foot',
    confirm: 'button.btn.primary',
  };

  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

  /** 轮询等待 fn() 返回真值；超时抛错 */
  const waitFor = (fn, { timeout = 5000, interval = 50, label = '目标' } = {}) =>
    new Promise((resolve, reject) => {
      const started = performance.now();
      const tick = () => {
        let value = null;
        try {
          value = fn();
        } catch (_) {
          value = null;
        }
        if (value) return resolve(value);
        if (performance.now() - started >= timeout) {
          return reject(new Error(`[dev-tester] 等待超时：${label}（${timeout}ms）`));
        }
        setTimeout(tick, interval);
      };
      tick();
    });

  /** 按钮是否真正可点击（没有 disabled 属性 / class / aria-disabled） */
  const isEnabled = (btn) =>
    !!btn &&
    !btn.disabled &&
    !btn.classList.contains('disabled') &&
    btn.getAttribute('aria-disabled') !== 'true';

  /** 以框架能感知的方式写入输入框 */
  function typeInto(input, text) {
    input.focus();
    const valueSetter = Object.getOwnPropertyDescriptor(
      HTMLInputElement.prototype,
      'value'
    ).set;
    valueSetter.call(input, text); // 绕过框架对 value 的拦截，保证变更被追踪
    input.dispatchEvent(new Event('input', { bubbles: true }));  // v-model / React onChange
    input.dispatchEvent(new Event('change', { bubbles: true })); // 失焦校验类逻辑
  }

  let running = false;

  /**
   * @param {string} text 要输入到文本框的内容
   * @param {object} [options]
   * @param {number} [options.openTimeout=5000]   等待打开按钮出现的超时
   * @param {number} [options.inputTimeout=5000]  等待输入框出现的超时
   * @param {number} [options.enableTimeout=3000] 等待确认按钮解禁的超时
   * @param {number} [options.settle=100]         每步之后给框架的渲染时间
   * @param {boolean} [options.force=false]       按钮始终禁用时是否强制点击
   * @param {boolean} [options.verbose=true]      是否输出过程日志
   * @returns {Promise<HTMLButtonElement>} 被点击的确认按钮
   */
  async function devTestSubmit(text, options = {}) {
    const {
      openTimeout = 5000,
      inputTimeout = 5000,
      enableTimeout = 3000,
      settle = 100,
      force = false,
      verbose = true,
    } = options;

    const log = (...args) =>
      verbose && console.log('%c[dev-tester]', 'color:#3b82f6;font-weight:600', ...args);

    if (running) throw new Error('[dev-tester] 上一次执行尚未结束，请等待其完成');
    running = true;

    try {
      // 1. 点击打开按钮
      const openBtn = await waitFor(() => document.querySelector(SEL.open), {
        timeout: openTimeout,
        label: '打开按钮',
      });
      openBtn.click();
      log('已点击打开按钮');

      // 2. 等待弹窗输入框并输入内容
      const input = await waitFor(
        () => {
          const el = document.querySelector(SEL.input);
          return el && el.offsetParent !== null ? el : null; // 必须可见
        },
        { timeout: inputTimeout, label: '文本输入框' }
      );
      await sleep(settle);
      typeInto(input, String(text));
      log('已输入内容：', text);

      // 当前弹窗内查找页脚，避免误点页面上其它弹窗的按钮
      const dialog = input.closest('div.modal-root.app-dialog-root') ||
        document.querySelector('div.modal-root.app-dialog-root');
      if (!dialog) throw new Error('[dev-tester] 未找到弹窗容器');
      const scope = dialog.querySelector(SEL.foot) || dialog;

      // 3. 等待确认按钮解除禁用后点击
      const confirmBtn = await waitFor(() => {
        const buttons = Array.from(scope.querySelectorAll(SEL.confirm));
        return buttons.find(isEnabled) || null;
      }, { timeout: enableTimeout, label: '确认按钮变为可用' }).catch((err) => {
        const candidate = scope.querySelector(SEL.confirm);
        if (!candidate) throw err;
        if (!force) {
          throw new Error(
            `[dev-tester] 确认按钮仍处于禁用状态（class="${candidate.className}", ` +
            `disabled=${candidate.disabled}）；确认可以强制提交时请传 { force: true }`
          );
        }
        log('⚠ 确认按钮仍禁用，按 force 选项强制点击');
        return candidate;
      });

      await sleep(settle);
      confirmBtn.click();
      log('已点击确认按钮');
      return confirmBtn;
    } finally {
      running = false;
    }
  }

  window.devTestSubmit = devTestSubmit;
  console.log(
    '%c[dev-tester] 已就绪：devTestSubmit("文本内容")',
    'color:#16a34a;font-weight:600'
  );
})();
