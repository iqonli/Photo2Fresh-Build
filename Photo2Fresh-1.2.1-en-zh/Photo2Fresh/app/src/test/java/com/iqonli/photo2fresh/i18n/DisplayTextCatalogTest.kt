package com.iqonli.photo2fresh.i18n

import com.iqonli.photo2fresh.regression.*
import org.junit.Assert.*
import org.junit.Test

class DisplayTextCatalogTest {
    @Test fun localizedModelNamesHaveNoNicknames() {
        assertEquals("YOLO Pose Localization Model", DisplayTextCatalog.text("检测卡定位模型", true))
        assertEquals("YOLO Seg Localization Model", DisplayTextCatalog.text("传感区域分割模型", true))
        assertEquals("Color Extraction and Calibration Module", DisplayTextCatalog.text("颜色提取与校正模块", true))
        assertEquals("颜色提取与校正模块", DisplayTextCatalog.text("颜色提取与校正模块", false))
    }
    @Test fun conditionAndFreshnessLabelsStayCompatibleWithModelManifest() {
        assertEquals("猪肉", MeatType.PORK.label)
        assertEquals("pork_25c", RegressionCondition(MeatType.PORK, StorageTemperature.ROOM).key)
        assertEquals("新鲜", FreshnessPrediction(RegressionCondition(MeatType.PORK, StorageTemperature.ROOM), 10f, 1f, "v").freshness)
        assertEquals("Detection complete · Pork · 25℃", DisplayTextCatalog.text("检测完成 · 猪肉 · 25℃", true))
        assertEquals("Beef · 4℃ Regression Model", DisplayTextCatalog.text("牛肉 · 4℃回归模型", true))
    }
    @Test fun valuesAndHourDayUnitsAreLocalizedWithoutRescaling() {
        assertEquals("Storage duration: 48.00 hours", DisplayTextCatalog.text("已储存时长：48.00 小时", true))
        assertEquals("Storage duration: 2.00 days", DisplayTextCatalog.text("已储存时长：2.00 天", true))
        assertEquals("Storage duration: 1.00 hour", DisplayTextCatalog.text("已储存时长：1.00 小时", true))
        assertEquals("已储存时长：2.00 天", DisplayTextCatalog.text("已储存时长：2.00 天", false))
        assertEquals("Freshness: Less fresh", DisplayTextCatalog.text("新鲜度：次新鲜", true))
    }
    @Test fun dynamicFailuresAndDebugFieldsTranslateAtDisplayTime() {
        assertEquals("Detection failed: Unknown error", DisplayTextCatalog.text("检测失败：未知错误", true))
        assertEquals("Deployment self-check passed (12/12)", DisplayTextCatalog.text("部署自检通过（12/12）", true))
        assertEquals("C11 gain: 1.000000, 2.000000, 3.000000", DisplayTextCatalog.text("C11 增益：1.000000, 2.000000, 3.000000", true))
        assertEquals("Detection notes: Too few grid inliers, ROI outside image bounds", DisplayTextCatalog.text("检测提示：LOW_GRID_INLIERS, ROI_OUTSIDE", true))
        assertEquals("Gray reference: W0 + C-grid fallback", DisplayTextCatalog.text("灰度参考：W0_C_grid_fallback", true))
        assertEquals("模型版本：ONNX部署包 2026-10-26", DisplayTextCatalog.text("模型版本：ONNX部署包 2026-10-26", false))
    }
    @Test fun languageSelectionDoesNotChangeInternalKeysAndResetsToEnglish() {
        AppLanguage.reset()
        assertEquals(UiLanguage.ENGLISH, AppLanguage.current)
        assertEquals("Crop", AppLanguage.text("裁剪"))
        AppLanguage.select(UiLanguage.CHINESE)
        assertEquals("裁剪", AppLanguage.text("裁剪"))
        assertEquals("原始 ROI", AppLanguage.text("RAW ROI"))
        AppLanguage.reset()
        assertEquals("Crop", AppLanguage.text("裁剪"))
        assertEquals("mail: 3120143962@qq.com", AppLanguage.text("mail：3120143962@qq.com"))
    }
    @Test fun arbitraryPathsAndThirdPartyErrorsArePreserved() {
        assertEquals("models/file.onnx", DisplayTextCatalog.text("models/file.onnx", true))
        assertEquals("CUDA error 123", DisplayTextCatalog.text("CUDA error 123", true))
    }
}
