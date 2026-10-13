# MiNotes-Analysis
软件项目管理4.11综合实践一：MiNotes源码精读、SonarQube质量度量与代码注释
1. 项目采用 MVC 架构，Activity 作为视图层，WorkingNote 作为模型层，ContentProvider 作为数据持久层，视图和数据解耦。
2. 使用 AsyncQueryHandler 异步数据库查询，避免主线程数据库 IO 引发 ANR，是早期 Android 性能优化经典方案。
3. IO 流使用 finally 关闭资源，利用 SharedPreferences 标记初始化状态，在 APP 首次启动自动创建引导笔记。
4. 编程风格：常量统一大写定义，复杂逻辑拆分为独立方法，onCreate 保持简洁；使用枚举管理页面状态，降低多状态维护复杂度。
5. 缺陷：代码使用传统 finally 关闭流，未使用 Java7 try-with-resources；Cursor 资源管理容易遗漏，存在内存泄漏风险；魔法常量较少，但缺少单元测试。
