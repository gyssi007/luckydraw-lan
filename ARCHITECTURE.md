# 渔榜助手代码结构（页面拆分版）

现在 Android WebView 只负责：
- WebView 初始化
- 本地 HTML/CSS 资源拦截
- 安全配置存储
- 命中提示音
- 页面返回

业务页面已经拆成三个独立 HTML：

- `app/src/main/assets/pages/lucky.html`：幸运号码
- `app/src/main/assets/pages/detective.html`：钓场侦探
- `app/src/main/assets/pages/config.html`：配置

公共资源：

- `app/src/main/assets/assets/css/app.css`：公共样式
- `app/src/main/assets/assets/common.js`：公共存储、提示音、通用 API/header 工具

这样以后修改某个页面时，通常只需要打开对应 HTML 文件，不再需要在 `MainActivity.java` 的十万字字符串中定位。

## 同源说明

页面使用 `https://fishing.gysssi.com/app/` 作为 WebView 的页面地址。
`MainActivity` 会把 `/app/` 下的 HTML/CSS/JS 请求映射到 APK 内的 assets，同时 API 请求仍然走原来的 `fishing.gysssi.com` 同源地址，因此不会因为拆分本地页面而改变原来的 API/CORS 工作方式。

## 已保留

- 原有 Token / UUID 加密存储
- 原有命中 MP3 提示音
- 幸运号码单次/自动刷号
- 自动锁定
- 钓场侦探
- 钓场配置、添加、删除
- 底部三个页面导航

另外修复了拆分后一个容易出现的问题：配置页不再依赖幸运号码页的日志 DOM；钓场侦探也不再直接操作幸运号码页的锁定控件。
