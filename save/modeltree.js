/*
 * 模型选择组件（custom.html / ptrpg.html / galgame.html / painter.html 共用）。
 *
 * 后端下发的模型标识按 "/" 分层（例如 deepseek/pro/flash），本组件把它渲染成目录树：
 * 每个节点自身也是一个可选的模型（选中值即该节点的完整路径），
 * 默认全部展开，点击箭头折叠/展开，点击名称即选中。
 *
 * 用法（需先引入 vue.js）：
 *   <model-tree v-model="model" :nodes="modelTree" @change="onModelChange"></model-tree>
 *
 * 数据来源：会话状态帧中的 modelTree 字段，形如
 *   [{ "key": "deepseek", "name": "Deepseek", "children": [ ... ] }]
 */
(function (global) {
    'use strict';

    if (typeof global.Vue === 'undefined') {
        console.warn('modeltree.js: 未找到 Vue，模型选择组件未注册');
        return;
    }

    /**
     * 递归节点。折叠状态直接记录在节点对象上（collapsed 为 true 才折叠），
     * 因此初始数据没有任何折叠标记时即为“默认全部展开”。
     */
    var ModelTreeNode = {
        name: 'model-tree-node',
        props: {
            node: { type: Object, required: true },
            value: { type: String, default: '' }
        },
        computed: {
            children: function () {
                return (this.node && this.node.children) || [];
            },
            hasChildren: function () {
                return this.children.length > 0;
            },
            collapsed: function () {
                return this.node.collapsed === true;
            }
        },
        methods: {
            toggle: function () {
                this.$set(this.node, 'collapsed', !this.collapsed);
            },
            select: function () {
                this.$emit('select', this.node.key);
            },
            // 子节点选中后继续向上传递，最终由根组件抛出
            bubble: function (key) {
                this.$emit('select', key);
            }
        },
        template: [
            '<div class="model-tree-node">',
            '    <div class="model-tree-item" :class="{ \'is-selected\': value === node.key }" @click="select">',
            '        <span v-if="hasChildren" class="model-tree-caret" :class="{ \'is-collapsed\': collapsed }" @click.stop="toggle"></span>',
            '        <span v-else class="model-tree-caret is-leaf"></span>',
            '        <span class="model-tree-label">{{ node.name }}</span>',
            '    </div>',
            '    <div class="model-tree-children" v-if="hasChildren && !collapsed">',
            '        <model-tree-node v-for="child in children" :key="child.key" :node="child" :value="value" @select="bubble"></model-tree-node>',
            '    </div>',
            '</div>'
        ].join('\n')
    };

    global.Vue.component('model-tree-node', ModelTreeNode);

    global.Vue.component('model-tree', {
        name: 'model-tree',
        props: {
            /** 目录树数据，节点含 key/name/children */
            nodes: { type: Array, default: function () { return []; } },
            /** 当前选中的模型标识，空字符串表示默认模型 */
            value: { type: String, default: '' },
            /** “默认模型”一项的显示文案 */
            defaultLabel: { type: String, default: '默认模型' }
        },
        methods: {
            select: function (key) {
                var next = key == null ? '' : key;
                if (next === this.value) return;
                this.$emit('input', next);
                this.$emit('change', next);
            }
        },
        template: [
            '<div class="model-tree">',
            '    <div class="model-tree-item is-default" :class="{ \'is-selected\': !value }" @click="select(\'\')">',
            '        <span class="model-tree-caret is-leaf"></span>',
            '        <span class="model-tree-label">{{ defaultLabel }}</span>',
            '    </div>',
            '    <model-tree-node v-for="node in nodes" :key="node.key" :node="node" :value="value" @select="select"></model-tree-node>',
            '    <div v-if="!nodes || !nodes.length" class="model-tree-empty">暂无可用模型</div>',
            '</div>'
        ].join('\n')
    });
})(window);
