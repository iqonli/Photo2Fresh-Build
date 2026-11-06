# 2026-11-06 本轮验证

## 已完成

- Microsoft OpenJDK17.0.21、Gradle8.13、AGP8.13.2、Kotlin2.2.21、SDK36 / Build Tools35.0.0。
- :app:compileDebugKotlin :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin -Pkotlin.incremental=false --no-daemon，BUILD SUCCESSFUL。
- 12项JUnit测试通过，含原有预处理/阈值等5项、运行时语言/动态字符串等6项，以及234条已批准静态英文文案的完整对照测试。
- 英文去标识化模型名称、中文模型清单匹配、工况组合、小时/天及单复数、嵌套错误、增益列表、质量标志、语言切换与重置均纳入测试。
- tools/check_model_assets.py通过，九ONNX SHA-256与原备份一致；workflow_config.json、模型配置.json、RegressionDomain/RegressionBundle/YoloImageRegressor字节保持不变。
- 原传感器SENSOR_DELAY_GAME与SecurityException防护保留。未运行assemble任务，未生成APK。

## 尚未执行

未连接Android设备或模拟器；未运行仪器测试，未做MIUI真机点击/旋转/后台恢复、相机或实拍检测验收。所附仪器测试代码已编译，不等于已执行。

## 构建环境排障记录

干净环境补齐SDK和JDK。Adoptium API取JDK被403拒绝，改用Microsoft官方JDK下载成功。首次Gradle使用新JDK时插件解析失败，补用环境系统证书库后依赖解析与编译成功；代理、证书路径、local.properties、SDK/JDK及缓存均不打入源码包。无需用户修改项目依赖版本。

## 文案范围

依据292项审核CSV实现显示映射，模板插值在显示边界处理。运行时未知的系统或第三方异常保留原文。已绘入历史预览图/导出图片的文字随生成时语言保留。请参阅LOCALIZATION_20261106.md。
