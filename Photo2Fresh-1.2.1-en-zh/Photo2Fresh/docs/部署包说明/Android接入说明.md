# Android接入说明

## 复制内容

1. 把包内`assets/肉类新鲜度`整个文件夹复制到APP的`app/src/main/assets/肉类新鲜度`。所有7个ONNX均为带权重的完整文件，没有.onnx.data等外置依赖。
2. 把`Android`中的3个.kt文件加入源码目录，修改package为APP实际包名。无需部署PyTorch、Python或Ultralytics库。
3. 将`依赖配置.gradle.kts`的dependency合并到APP模块配置，确保repositories中有mavenCentral()。采用已确认存在的`com.microsoft.onnxruntime:onnxruntime-android:1.24.3`；依赖由Gradle获取，本ZIP不重复打包其AAR及各ABI运行库。
4. CPU同步调用需放在APP已有后台线程/协程调度器中。引擎重复使用，页面或业务模块退出时调用close；不要每张图重新构造引擎。

```kotlin
val engine = MeatFreshnessEngine(context.assets)

// 将上游9个ROI转为RoiRgb(width, height, RGB字节数组)。
// map的key正好是C11、C12、C13、C21、C22、C23、C31、C32、C33。
val input: FloatArray = MeatImagePreprocessor.fromRawRois(rois, gains)
val result = engine.predict("猪肉", 25, input)
// result.tvbnMgPer100g：mg/100 g
// result.storageTimeHours：h；需要显示天则除24
// result.freshness：Fresh / Less Fresh / Spoiled

// 若上游已经按相同数值规则完成ROI像素矫正：
val input2 = MeatImagePreprocessor.fromCorrectedRois(correctedRois)
val result2 = engine.predict("牛肉", 4, input2)

// 持有引擎的业务结束后：
engine.close()
```

肉类只接受猪肉、牛肉、鸡肉，温度只接受25或4，不能根据文件名或输入值默默猜测工况。首次选择工况会加载对应回归模块；切换时只保留公共前端和当前模块，避免6个模块同时常驻。

## 如何取得RGB字节

如果上游返回Android Bitmap，先确保它是可读取的8位sRGB软件Bitmap、方向已按上游定位处理。按每个像素的R、G、B依次写入ByteArray，不包含alpha、行填充或BGR顺序。例如：

```kotlin
val pixels = IntArray(bitmap.width * bitmap.height)
bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
val rgb = ByteArray(pixels.size * 3)
for (i in pixels.indices) {
    rgb[3*i] = ((pixels[i] ushr 16) and 255).toByte()
    rgb[3*i+1] = ((pixels[i] ushr 8) and 255).toByte()
    rgb[3*i+2] = (pixels[i] and 255).toByte()
}
val roi = RoiRgb(bitmap.width, bitmap.height, rgb)
```

不要在这个过程中再次缩放ROI，或使用Android默认缩放代替包内预处理。原始训练读取的是ROI的RGB像素，透明图/广色域/HDR或从原图重新截图的结果不能自动当作同一输入。上游Pose/Seg、透视几何、灰度参考选点及增益计算仍沿用原流程；本包处理的是已提取ROI与已有增益，未包含新的ROI检测器。

## 首次集成自检

```kotlin
val results = MeatDeploymentSelfCheck.run(context.assets)
// 成功应返回12条OK：raw/corrected两个合成输入 × 六个工况。
```

自检不含实验照片，按配置生成确定性RGB图案，先比对预处理float32张量的SHA256，再比较各工况输出。若预处理SHA不一致，应先定位RGB、量化、缩放或数值实现差异；不要通过放宽预测误差来掩盖输入差异。

本次没有Android工程路径、JDK/Kotlin编译器或目标手机，因此尚未执行本段Kotlin代码的构建与真机自检。ONNX本身已在桌面CPU Runtime加载并验证；Kotlin API按ONNX Runtime 1.24.3官方Java源码核对，目标手机仍须运行上述自检。

## 运行库与输出

本包默认CPU、FP32。未启用NNAPI/GPU/INT8/FP16，也未验证这些加速方式。TVB-N和时间都已在图内恢复物理单位，不要再次乘标准差、加均值、Softplus或裁负值。新鲜度是TVB-N阈值派生，不能解释成独立分类概率。

运行时只需要assets中的文件和ONNX Runtime Android依赖。原始.pt、训练CSV、工作簿、冻结缓存、Python环境不需要放进APP。

## 官方参考

- [ONNX Runtime Java调用](https://onnxruntime.ai/docs/get-started/with-java.html)
- [ONNX Runtime Android构建说明](https://onnxruntime.ai/docs/build/android.html)
- [Android运行库1.24.3 Maven条目](https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.24.3/)
- [本包使用版本的OnnxTensor API源码](https://github.com/microsoft/onnxruntime/blob/v1.24.3/java/src/main/java/ai/onnxruntime/OnnxTensor.java)
