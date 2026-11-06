# 最新验证补充：2026-11-06

本次语言与界面改动已完成Kotlin编译及12项单元测试，未生成APK，未做真机验证。详见validation/LOCALIZATION_VALIDATION.md。下方为此前版本的历史验证记录，APK签名/哈希不能用作本轮源码产物的校验值。

# 本轮验证记录（2026-10-26）

## 已通过

- 真实 Gradle 构建：`:app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest`，BUILD SUCCESSFUL。
- Kotlin / Compose / Java / Android 资源编译完成；两个 ABI Debug APK 和仪器测试 APK 均构建成功。
- 五个 JUnit 测试通过（四个回归相关测试及一个原有示例测试）：Pillow 独立黄金像素、部署包 raw/corrected 两个 float32 张量 SHA-256、15/20阈值、小时/天单位、重复/缺失 ROI 拒绝。
- ONNX Runtime 1.24.3 桌面 CPU 加载全部九图；前端/六工况 SHA-256 与部署配置一致，Pose/Seg 与原导出清单一致。
- 部署包两组合成输入 × 六工况共12次预测通过自检容差；不是新肉样准确率评估。
- 两个 APK 均通过 apksigner 验签（v2）、16 KiB 页面 ZIP 对齐检查；各只含对应 ABI 原生库，均含九个 ONNX，包内权重哈希与输入文件一致，无旧 Matrix 权重。

## 未执行

没有连接 Android 手机或模拟器，故没有实际运行仪器测试、相机/相册/裁剪全流程或真机“部署自检”。没有测量手机耗时、峰值内存和新批次肉样泛化。保留原上游几何流程，没有新的真实 ROI/增益/拼图配对样本来证明整条输入提取与 Python 逐图一致。

## 构建环境与排障

原源码未含 gradle 目录，已重建版本目录与官方 Gradle Wrapper，明确版本见 README。构建环境原 Java 17 缺少 javac/jmod/jlink 命令入口，但包含对应编译模块；为本次容器补齐命令入口后成功构建。用户本机应使用完整 JDK 17，无需这些容器补丁。

曾尝试系统包安装（权限失败）、Adoptium API（403）、Oracle 下载地址（返回195字节不可用HTML，非JDK），已放弃下载路线。构建初次依赖解析使用环境旧代理地址失败，改为当前进程代理后解析成功；未将代理配置写进源码。编译发现自检 UI 缺少生命周期状态收集 import，已修复；随后完整构建和测试通过。

## 签名与交付

交付 APK 使用用户提供的发布密钥签名，已与提供的 1.1.0 APK 证书核对一致；构建类型为 Debug，不声称已完成真机安装验证。
源码 ZIP 排除所有模型权重、构建缓存、SDK 路径、签名文件与密码。APK 各内置全部九个模型。
完整 APK 大于 GitHub 普通仓库单文件100 MiB限制，因此无法作为普通文件原样提交到 perry 根目录；完整文件另行保存，仓库交付说明列出下载与校验信息。

## 1.2.1 启动修复

用户日志确认首页动画以0微秒采样注册传感器导致 SecurityException。改为 SENSOR_DELAY_GAME 并捕获 SecurityException。详见 STARTUP_FIX_1.2.1.md；未更改模型。原始系统全量日志不纳入交付源码。
