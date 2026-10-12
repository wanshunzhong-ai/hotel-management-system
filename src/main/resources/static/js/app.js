/* 云栖大酒店 · 管理系统通用脚本 */
(function () {
    'use strict';

    // 侧边栏（移动端）
    const toggle = document.getElementById('sidebarToggle');
    const sidebar = document.querySelector('.app-sidebar');
    if (toggle && sidebar) {
        toggle.addEventListener('click', () => sidebar.classList.toggle('open'));
        document.addEventListener('click', (e) => {
            if (window.innerWidth <= 860 && sidebar.classList.contains('open')
                && !sidebar.contains(e.target) && e.target !== toggle) {
                sidebar.classList.remove('open');
            }
        });
    }

    // 危险操作二次确认
    document.querySelectorAll('form[data-confirm]').forEach((form) => {
        form.addEventListener('submit', (e) => {
            if (!window.confirm(form.getAttribute('data-confirm'))) {
                e.preventDefault();
            }
        });
    });

    // 提示信息自动淡出
    document.querySelectorAll('.alert[data-auto-dismiss]').forEach((el) => {
        setTimeout(() => {
            el.style.transition = 'opacity .5s, transform .5s';
            el.style.opacity = '0';
            el.style.transform = 'translateY(-6px)';
            setTimeout(() => el.remove(), 500);
        }, 4200);
    });

    // 无 JS 时的兜底：提交按钮防重复点击
    document.querySelectorAll('form').forEach((form) => {
        form.addEventListener('submit', () => {
            const btn = form.querySelector('button[type="submit"]');
            if (btn && !form.hasAttribute('data-no-lock')) {
                setTimeout(() => { btn.disabled = true; }, 10);
                setTimeout(() => { btn.disabled = false; }, 6000);
            }
        });
    });

    // 金额格式化
    window.formatMoney = function (value) {
        const n = Number(value || 0);
        return '￥' + n.toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    };

    // Chart.js 全局风格
    if (window.Chart) {
        Chart.defaults.font.family = '"PingFang SC","Microsoft YaHei",Arial,sans-serif';
        Chart.defaults.font.size = 12;
        Chart.defaults.color = '#6b7280';
        Chart.defaults.plugins.legend.labels.usePointStyle = true;
        Chart.defaults.plugins.legend.labels.boxWidth = 8;
    }

    function escapeHtml(text) {
        return String(text === null || text === undefined ? '' : text)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    /**
     * 客户搜索选择器。
     *
     * 取代「把全量客户渲染进 <select>」的做法：输入姓名/手机号后按关键词远程取前 N 条，
     * 避免客户上千条时页面体积与渲染都失控。
     *
     * 选中的客户 id 写入隐藏域供表单提交；输入框被手工修改时自动清空隐藏域，
     * 防止出现「显示的是张三、提交的是李四」这种脏数据。
     *
     * @param config {rootId, inputId, hiddenId, listId, clearId, endpoint, limit, onSelect, onClear}
     * @returns {{select: Function, clear: Function, value: Function}|null}
     */
    window.initCustomerPicker = function (config) {
        const root = document.getElementById(config.rootId);
        const input = document.getElementById(config.inputId);
        const hidden = document.getElementById(config.hiddenId);
        const list = document.getElementById(config.listId);
        const clearBtn = config.clearId ? document.getElementById(config.clearId) : null;
        if (!root || !input || !hidden || !list) {
            return null;
        }

        const endpoint = config.endpoint || '/customers/picker';
        const limit = config.limit || 20;

        let items = [];
        let activeIndex = -1;
        let timer = null;
        let seq = 0;
        let selected = null;

        function displayOf(c) {
            return c.name + ' · ' + c.phone + (c.memberLevel ? ' · ' + c.memberLevel : '');
        }

        function open() {
            list.style.display = 'block';
        }

        function close() {
            list.style.display = 'none';
            activeIndex = -1;
        }

        /** 只切换高亮类，不重建 DOM —— 重建会再次触发 mouseenter 造成死循环 */
        function updateActive() {
            Array.prototype.forEach.call(list.children, function (el, i) {
                if (el.classList) {
                    el.classList.toggle('is-active', i === activeIndex);
                }
            });
            const current = list.children[activeIndex];
            if (current && current.scrollIntoView) {
                current.scrollIntoView({ block: 'nearest' });
            }
        }

        function render() {
            if (!items.length) {
                list.innerHTML = '<div class="picker-empty">没有匹配的客户，点右侧「新建客户」立即建档</div>';
                open();
                return;
            }
            list.innerHTML = '';
            items.forEach(function (c, i) {
                const row = document.createElement('div');
                row.className = 'picker-item';
                row.innerHTML = '<div class="pi-main"><b>' + escapeHtml(c.name) + '</b>'
                    + '<span class="pi-phone mono">' + escapeHtml(c.phone) + '</span></div>'
                    + '<span class="badge-soft secondary">' + escapeHtml(c.memberLevel || '普通') + '</span>';
                row.addEventListener('mousedown', function (e) {
                    e.preventDefault();
                    select(c);
                });
                row.addEventListener('mouseenter', function () {
                    activeIndex = i;
                    updateActive();
                });
                list.appendChild(row);
            });
            if (activeIndex < 0 || activeIndex >= items.length) {
                activeIndex = 0;
            }
            updateActive();
            open();
        }

        function select(c) {
            selected = c;
            hidden.value = c.id;
            input.value = displayOf(c);
            root.classList.add('is-selected');
            close();
            if (config.onSelect) {
                config.onSelect(c);
            }
        }

        function clearSelection(focusInput) {
            selected = null;
            hidden.value = '';
            root.classList.remove('is-selected');
            if (focusInput) {
                input.value = '';
                input.focus();
            }
            if (config.onClear) {
                config.onClear();
            }
        }

        function load(keyword) {
            const mySeq = ++seq;
            fetch(endpoint + '?kw=' + encodeURIComponent(keyword || ''), {
                headers: { 'Accept': 'application/json' }
            })
                .then(function (res) { return res.json(); })
                .then(function (data) {
                    if (mySeq !== seq) {
                        return; // 丢弃过期响应，避免快速输入时结果错位
                    }
                    items = Array.isArray(data) ? data.slice(0, limit) : [];
                    activeIndex = items.length ? 0 : -1;
                    render();
                })
                .catch(function () {
                    if (mySeq !== seq) {
                        return;
                    }
                    list.innerHTML = '<div class="picker-empty">客户列表加载失败，请重试</div>';
                    open();
                });
        }

        input.addEventListener('focus', function () {
            if (!selected) {
                load(input.value.trim());
            }
        });

        input.addEventListener('input', function () {
            if (selected) {
                clearSelection(false);
            }
            clearTimeout(timer);
            timer = setTimeout(function () { load(input.value.trim()); }, 220);
        });

        input.addEventListener('keydown', function (e) {
            if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
                if (!items.length) {
                    return;
                }
                e.preventDefault();
                activeIndex = e.key === 'ArrowDown'
                    ? (activeIndex + 1) % items.length
                    : (activeIndex - 1 + items.length) % items.length;
                updateActive();
            } else if (e.key === 'Enter') {
                if (list.style.display === 'block' && activeIndex >= 0 && items[activeIndex]) {
                    e.preventDefault();
                    select(items[activeIndex]);
                }
            } else if (e.key === 'Escape') {
                close();
            }
        });

        if (clearBtn) {
            clearBtn.addEventListener('click', function () { clearSelection(true); });
        }

        document.addEventListener('click', function (e) {
            if (!root.contains(e.target)) {
                close();
            }
        });

        return {
            select: select,
            clear: function () { clearSelection(false); },
            value: function () { return selected; }
        };
    };
})();
