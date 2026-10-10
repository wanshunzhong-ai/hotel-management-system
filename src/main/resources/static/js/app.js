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

    // 登录页演示账号快捷填充
    document.querySelectorAll('.demo-chip[data-username]').forEach((chip) => {
        chip.addEventListener('click', () => {
            const u = document.getElementById('username');
            const p = document.getElementById('password');
            if (u && p) {
                u.value = chip.getAttribute('data-username');
                p.value = chip.getAttribute('data-password');
                u.focus();
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
})();
