"""Read-only asset integrity check; no inference required."""
import hashlib, json
from pathlib import Path
assets = Path(__file__).resolve().parents[1]/"app/src/main/assets"
config = json.loads((assets/"肉类新鲜度/模型配置.json").read_text(encoding="utf-8"))
if len(config["routes"]) != 6: raise SystemExit("Expected six routes")
for graph in [config["frontend"], *config["routes"]]:
    path=assets/"肉类新鲜度"/graph["file"]
    if not path.is_file(): raise SystemExit(f"Missing {path}")
    if path.stat().st_size != graph["bytes"] or hashlib.sha256(path.read_bytes()).hexdigest() != graph["sha256"]:
        raise SystemExit(f"Model integrity mismatch: {path}")
for name in ["indian_flying_pancake_1280.onnx", "hydralisk_seg_1024_final.onnx"]:
    if not (assets/"models"/name).is_file(): raise SystemExit(f"Missing {name}")
manifest=json.loads((assets/"models/yolo_export_manifest.json").read_text(encoding="utf-8"))
for model in manifest["models"]:
    name=model["onnx"].replace("\\", "/").split("/")[-1]
    path=assets/"models"/name
    if hashlib.sha256(path.read_bytes()).hexdigest()!=model["sha256"]: raise SystemExit(f"Upstream model integrity mismatch: {name}")
print("PASS: two upstream models and seven regression models")
