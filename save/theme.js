/*
 * 白天/黑夜模式（custom.html / galgame.html / trpg.html / ptrpg.html / painter.html 共用）。
 *
 * 用法：在 <head> 里引入本脚本，主题类会在首次绘制前挂到 <html> 上，避免刷新时闪一下：
 *   <script src="theme.js"></script>
 *
 * 页面默认值（未保存过偏好时生效）：
 *   <script>window.AIWUXIA_THEME_DEFAULT = 'day';</script>   不写则为黑夜
 * 绘画页原本就是浅色，因此它声明默认白天；用户一旦切换，偏好对所有页面生效。
 *
 * 页面侧读取/切换：
 *   AIWuxiaTheme.isDay()      当前是否白天模式
 *   AIWuxiaTheme.setDay(bool) 切换并写入本地存储（各页面共用同一偏好）
 *   AIWuxiaTheme.toggle()     取反并返回新状态
 *
 * 样式：默认（不加类）为黑夜模式，白天模式的覆盖规则统一写在 chat-common.css 的
 * `html.day-mode ...` 选择器下；绘画页用自己的 `html:not(.day-mode)` 覆盖表示黑夜。
 */
(function (global) {
    'use strict';

    var KEY = 'aiwuxia_theme';
    var DAY = 'day-mode';
    // 页面级默认：只在用户从未选择过主题时生效
    var DEFAULT_DAY = global.AIWUXIA_THEME_DEFAULT === 'day';

    function isDay() {
        try {
            var saved = global.localStorage.getItem(KEY);
            if (saved === 'day') return true;
            if (saved === 'night') return false;
        } catch (e) {
            // 隐私模式/受限 iframe 下 localStorage 可能不可用，退化为页面默认值
        }
        return DEFAULT_DAY;
    }

    function apply(day) {
        var root = global.document && global.document.documentElement;
        if (!root) return;
        if (day) root.classList.add(DAY);
        else root.classList.remove(DAY);
        // 让浏览器原生控件（滚动条、输入框等）跟随主题
        root.style.colorScheme = day ? 'light' : 'dark';
    }

    function setDay(day) {
        day = !!day;
        try {
            global.localStorage.setItem(KEY, day ? 'day' : 'night');
        } catch (e) {
            // 存储不可用时仅本次生效
        }
        apply(day);
        return day;
    }

    // 立即应用一次，保证样式生效前主题已确定
    apply(isDay());

    global.AIWuxiaTheme = {
        isDay: isDay,
        setDay: setDay,
        toggle: function () {
            return setDay(!isDay());
        }
    };
})(window);
