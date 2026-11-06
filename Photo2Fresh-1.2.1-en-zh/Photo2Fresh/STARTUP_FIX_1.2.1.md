# Photo2Fresh 1.2.1 启动闪退修复

用户设备：MIUI 14 / Android 13。用户日志确认主线程 SecurityException：To use the sampling rate of 0 microseconds, app needs to declare the normal permission HIGH_SAMPLING_RATE_SENSORS。
调用位置：Photo2FreshApp.kt 的 BouncyEmptyPhotoPlaceholder，registerListener。

修复：首页摇晃动画的 SENSOR_DELAY_FASTEST 改为 SENSOR_DELAY_GAME（请求间隔20,000微秒）；捕获 SecurityException，传感器拒绝访问时不影响首页。未新增高采样率权限，模型和推理代码未更改。版本Code=13，Name=1.2.1。沿用用户提供的签名。

验证范围：Gradle 构建、已有单元测试、APK签名、ABI与模型哈希检查。未连接MIUI真机，覆盖安装与实际启动需要用户验证。
