"""Embed the Blender GLB into the review fragment; no runtime asset requests."""
from pathlib import Path
import base64
import gzip
import json

directory = Path(__file__).resolve().parent
project_root = next(path for path in directory.parents if (path / "ios/recoow.xcodeproj").is_dir())
source = (directory / "preview-template.html.in").read_text(encoding="utf-8")
manifest = json.loads((directory / "asset-manifest.json").read_text(encoding="utf-8"))
payload = {
    "encoding": "gzip",
    "data": base64.b64encode(gzip.compress((directory / "decision-scene.glb").read_bytes(), mtime=0)).decode("ascii"),
}
source = source.replace("__SCENE_DATA__", json.dumps(payload, separators=(",", ":")))
source = source.replace("__ASSET_ALIASES__", json.dumps(manifest["aliases"], ensure_ascii=False, separators=(",", ":")))
source = source.replace("__MODEL_KEYS__", json.dumps([asset["key"] for asset in manifest["assets"]]))
destination = project_root / "docs/decision-scene/decision-scene-preview.html"
encoded = source.encode("utf-8")
if len(encoded) >= 1_000_000:
    raise ValueError("Review fragment exceeds the inline size limit")
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_bytes(encoded)
print(f"Wrote {destination} ({len(encoded):,} bytes)")
