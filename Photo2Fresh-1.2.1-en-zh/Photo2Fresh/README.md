# Photo2Fresh 1.2.1

本包已应用2026-11-06英文默认语言及界面改动，详见LOCALIZATION_20261106.md。包含全部九个ONNX，不含APK和签名资料。

以 2026-10-26 三种肉六工况 ONNX 部署包为模型接口基准。照片 → Pose 定位 → 透视校正 → Seg 分割 → CatchTheRainbow 几何重建与逐区域增益 → 九个原始 ROI 像素校正 → 3×3 拼图 → YOLO26s 公共前端 → 当前工况双目标回归。

支持猪肉、牛肉、鸡肉及 25℃、4℃。结果为 TVB-N（mg/100 g）、由未四舍五入的 TVB-N 派生的新鲜度、已储存时长。25℃固定显示小时，4℃固定显示天；时间不是剩余保质期。TVB-N <15 为新鲜，15～20（含边界）为次新鲜，>20 为腐败。

## 模型放置

本备份已包含全部九个部署 ONNX，位于下列工程目录，无需再次提取或复制。不包含原始训练 PT 检查点。若日后替换模型，将部署包中的 `assets/肉类新鲜度/` 原样复制到本工程 `app/src/main/assets/肉类新鲜度/`，保留中文文件名，覆盖同名 JSON：

- 公共图像特征.onnx
- 猪肉_25摄氏度_双目标.onnx
- 猪肉_4摄氏度_双目标.onnx
- 牛肉_25摄氏度_双目标.onnx
- 牛肉_4摄氏度_双目标.onnx
- 鸡肉_25摄氏度_双目标.onnx
- 鸡肉_4摄氏度_双目标.onnx
- 模型配置.json
- 预处理自检.json

以下两个上游模型已从用户提供的 `photo2fresh_1.1.0.apk` 恢复并纳入本备份：

- `app/src/main/assets/models/indian_flying_pancake_1280.onnx`
- `app/src/main/assets/models/hydralisk_seg_1024_final.onnx`

APK 内对应路径没有 `app/src/main/` 前缀，即 `assets/models/…`。无需原始 `.pt` 或旧 Matrix 权重。不要使用上一版占位清单 `freshness_manifest.json` 和旧导出器；当前直接读取部署包的 `模型配置.json`。

## Windows 构建

1. 用 Android Studio 打开工程根目录；Gradle JDK 选择完整 JDK 17。SDK Manager 安装 Android SDK Platform 36 和 Build Tools 35.0.0（AGP 可能自动补齐所需版本）。
2. 同步 Gradle。版本目录和 Wrapper 已重建：AGP 8.13.2、Gradle 8.13、Kotlin 2.2.21、ONNX Runtime Android 1.24.3。它们是此次明确固定的构建配置，不代表恢复了原电脑上被删掉文件的内容。
3. 放好模型后执行 `build_photo2fresh_debug.bat`；或运行：

```bat
py -3 tools\check_model_assets.py
gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

产物位于 `app/build/outputs/apk/debug/`，分别为 arm64-v8a、x86_64 两个完整 APK，各自包含全部模型，无需安装模型包。Debug 使用本机构建的测试证书。与旧版证书不同可能无法覆盖安装；请保留旧应用数据后决定是否卸载。

正式签名：自行创建不入库的 `keystore.properties`，字段为 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`；`storeFile` 相对 app 模块解析，推荐填写本机绝对路径。然后执行 `build_photo2fresh_release.bat`。源码和本次交付不含签名私钥或密码。

## 检查输入与部署

测试界面保留原有入口，增加“部署自检（六工况）”。自检直接调用正式预处理和正式推理器，核对两组输入的 float32 SHA-256，再核对六工况各两组，共12条输出。自检只说明部署数值一致，不证明新肉样泛化性能。

测试检测可查看九个原始 ROI、192×192 拼图、224×224 实际输入、逐区域增益和质量标志，并分享含无损 PNG、增益、NCHW 张量和几何参数的 ZIP。它可用于实拍输入追溯。

原始 ROI 在 RGB 增益应用前提取，正式预处理只校正一次；像素为 float64 线性 sRGB 乘增益、取偶量化、Pillow 风格双线性缩放。RGB NCHW float32 除255，无 ImageNet 归一化。前端输入 images [1,3,224,224]、输出 features [1,512,7,7]；任务输出 prediction [1,2]，物理单位变换已包含在图内，Android 不再做 Softplus、均值或标准差恢复。

## 本轮范围

移除旧 Matrix 推理代码、清单、导出占位与旧工作流说明。保留现有 Pose、Seg、透视、CatchTheRainbow 几何/灰度参考选择及原参数：Pose conf 0.25，Seg candidate conf 0.001，minInliers 3、maxRecover 6，ROI比例1/3。几何重建与 Python 原始照片逐图一致性没有新增实拍数据验证；该限制不能通过模型合成自检替代。

详细执行结果见 `VALIDATION.md` 和 `validation/`。
