package com.iqonli.photo2fresh.regression

import android.content.Context
import org.json.JSONObject

data class RegressionConfig(val manifestAssetPath: String)
data class RegressionGraph(val assetPath: String, val sha256: String, val inputName: String, val outputNames: List<String>)
data class RegressionBundle(val modelVersion: String, val frontend: RegressionGraph, val routes: Map<RegressionCondition, RegressionGraph>) {
    companion object {
        fun load(context: Context, config: RegressionConfig): RegressionBundle {
            val directory = config.manifestAssetPath.substringBeforeLast('/')
            val root = JSONObject(context.assets.open(config.manifestAssetPath).bufferedReader(Charsets.UTF_8).use { it.readText() })
            require(root.getInt("schema_version") == 1) { "不支持的模型配置版本" }
            val input = root.getJSONObject("input")
            require(input.getString("rgb_order") == "RGB" && input.getString("dtype") == "float32")
            require(!input.getBoolean("imagenet_normalization") && !input.getBoolean("preprocessing_inside_onnx"))
            require(root.getJSONObject("prediction").getBoolean("physical_unit_transform_inside_onnx"))
            fun graph(obj: JSONObject, input: String, output: String, shape: List<Int>): RegressionGraph {
                val file = obj.getString("file")
                require('/' !in file && '\\' !in file && ".." !in file && file.endsWith(".onnx"))
                require(obj.getString("input_name") == input && obj.getString("output_name") == output)
                val actual = obj.getJSONArray("output_shape")
                require(List(actual.length()) { actual.getInt(it) } == shape)
                require(!obj.getBoolean("external_data"))
                val hash = obj.getString("sha256")
                require(hash.matches(Regex("[0-9a-f]{64}")))
                return RegressionGraph("$directory/$file", hash, input, listOf(output))
            }
            val routes = linkedMapOf<RegressionCondition, RegressionGraph>()
            val array = root.getJSONArray("routes")
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val meat = MeatType.entries.single { it.label == obj.getString("meat") }
                val temperature = StorageTemperature.entries.single { it.celsius == obj.getInt("temperature_c") }
                val condition = RegressionCondition(meat, temperature)
                require(condition.key == obj.getString("route_id") && condition !in routes)
                val columns = obj.getJSONArray("output_columns")
                require(List(columns.length()) { columns.getString(it) } == listOf("tvbn_mg_per_100g", "storage_time_h"))
                routes[condition] = graph(obj, "features", "prediction", listOf(1, 2))
            }
            require(routes.size == 6) { "模型配置必须包含猪牛鸡 × 25℃/4℃六工况" }
            return RegressionBundle("ONNX部署包 " + root.getString("created"), graph(root.getJSONObject("frontend"), "images", "features", listOf(1, 512, 7, 7)), routes)
        }
    }
}
