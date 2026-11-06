# Photo2Fresh 1.2.1 英文默认语言与界面更新

版本名仍为1.2.1，versionCode仍为13。本包包含完整Android工程、两个上游ONNX和七个回归ONNX，没有APK、签名密钥或密码。

## 本次改动

- 默认英文。测试页面的“裁剪 / Crop”改为左半行按钮，右半行为“切换语言 / Language”。点击右侧按钮弹窗选择English或中文，选中后立即应用。
- 语言只保存在当前进程内存中，不写入配置或Android保存状态。正常结束Activity后重置；新启动及进程重启默认英文。旋转屏幕、临时进入后台和拍摄过程保持本次运行的语言。
- 关于页删除QQ一行，改为mail：3120143962@qq.com；英文显示mail: 3120143962@qq.com。
- 工况卡片、两行选项铺满父容器可用宽度，保留页面正常边距。选中项使用2dp主题色描边及粗体文字，未选项1dp描边、正常字重。
- 英文模型名称统一为YOLO Pose Localization Model、YOLO Seg Localization Model及Color Extraction and Calibration Module，不出现旧绰号。中文界面保留原中文文案。
- 修正界面硬编码标题由v1.2.0改为v1.2.1，与工程现有版本一致。

## 翻译实现与数据约束

依据docs/translation-review-en-zh.csv中的292项文本池，在i18n/DisplayTextCatalog.kt整理显示词典和动态模板。AppLanguage.text仅在Text、Toast、无障碍描述、系统分享标题与预览绘字等显示边界使用；语言状态由Compose快照观察以触发界面更新。

推理层的肉类中文label继续用于模型清单匹配，新鲜度中文值继续用于部署自检比较；工况key、模型文件名、模型JSON和九个ONNX均未改动。错误信息和已保存结果保持原始内容，在显示时翻译，切换语言不更换模型或重新计算预测。未知的系统/第三方异常保留原文。天/小时按实际数值处理英文单复数，不改变小时转天计算。

生成的预览图中的文字按生成时的语言绘入像素；已经生成或导出的图片不会因切换语言而重新生成。图像内容、真实路径、C11等区域标识、模型版本时间戳和诊断数据键仍保持原值。已存在的语言识别选项English/中文固定显示自身名称。

## 构建

务必解压到全新目录，不覆盖旧工程，防止残留MatrixEnsemble.kt等旧文件。Android Studio打开含settings.gradle.kts的Photo2Fresh目录，配置完整JDK17和Android SDK36。Gradle8.13、AGP8.13.2、Kotlin2.2.21沿用原工程。

先执行：

    py -3 tools/check_model_assets.py
    gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest

若本机Kotlin增量缓存异常，可加-Pkotlin.incremental=false。需要自行生成Debug安装包时运行gradlew.bat :app:assembleDebug或build_photo2fresh_debug.bat；正式包另行配置原有签名文件和keystore.properties后使用发行脚本。本次不包含APK。

新增共享运行配置.run/Build Photo2Fresh 2 APKs.run.xml。若IDE仍显示旧的releasePhoto2Fresh或4 APKs配置，应打开本包的新工程，或改任务为:app:assembleDebug。

## 验证

验证记录见validation/LOCALIZATION_VALIDATION.md。本次未连接手机，不把编译或JVM测试通过写成MIUI真机验收通过。1.2.1既有传感器采样闪退修复继续保留。
