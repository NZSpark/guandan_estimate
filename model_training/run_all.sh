#!/usr/bin/env bash
# 按 doc/local_engine_tasks.md §6/§7 顺序执行全部自动化任务。
# 用法：model_training/run_all.sh [SEED]
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
PY="model_training/.venv/bin/python"
SEED="${1:-20261101}"
RUN="output/runs/$SEED"
LOG="$RUN/run-all.log"
mkdir -p "$RUN"
: > "$LOG"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"

step() {
  local name="$1"; shift
  echo "=== $name ===" | tee -a "$LOG"
  "$@" >>"$LOG" 2>&1
  echo "exit=$? :: $name" | tee -a "$LOG"
}

# M0 管线
step T0.1 "$PY" model_training/run_gradle_task.py --task T0.1 --tests '*ImagePreprocessorTest' --output-dir "$RUN/T0.1"
step T0.2 "$PY" model_training/run_gradle_task.py --task T0.2 --tests '*RecognitionWorkerTest' --tests '*ImageMemoryTest' --output-dir "$RUN/T0.2"
step T0.3 "$PY" model_training/run_gradle_task.py --task T0.3 --tests '*RecognitionCoordinatorTest' --output-dir "$RUN/T0.3"
step T0.4 "$PY" model_training/run_gradle_task.py --task T0.4 --tests '*VisualCardCodecTest' --output-dir "$RUN/T0.4"
step T0.5 "$PY" model_training/golden.py prepare --cases all --seed "$SEED" --output-dir "$RUN/T0.5"

# M1 数据
step T1.3a "$PY" model_training/generate_backgrounds.py --seed "$SEED" --count 16 --output-dir "$RUN/T1.3-backgrounds"
step T1.3 "$PY" model_training/generate_synthetic.py --seed "$SEED" --count 2000 --output-dir "$RUN/T1.3"
if [ -f "$RUN/T1.3/dataset_manifest.json" ]; then
  cp "$RUN/T1.3/dataset_manifest.json" model_training/datasets/dataset_manifest.json
fi
step T1.1 "$PY" model_training/fetch_public_data.py --seed "$SEED" --output-dir "$RUN/T1.1"
step T1.2 "$PY" model_training/convert_public_annotations.py --input "$RUN/T1.1/raw/hf-jackfurby-playing-cards" \
  --format hf-json --annotations splits/single/train.json,splits/single/val.json --classes splits/single/classes.txt \
  --label-map model_training/configs/public_conversion.yaml --seed "$SEED" --output-dir "$RUN/T1.2"
step T1.4 "$PY" model_training/validate_dataset.py --task T1.4 --manifest model_training/datasets/dataset_manifest.json \
  --check-files --check-hashes --check-labels --check-classes --check-scene-splits --check-leakage \
  --seed "$SEED" --output-dir "$RUN/T1.4"
step T1.5 "$PY" model_training/validate_dataset.py --task T1.5 --manifest model_training/datasets/dataset_manifest.json \
  --audit --check-golden-exclusion --check-classes --seed "$SEED" --output-dir "$RUN/T1.5"

# M2 检测器
step T2.1 "$PY" model_training/train_detector.py --mode baseline --seed "$SEED" --config model_training/configs/detector.yaml --output-dir "$RUN/T2.1"
step T2.2 "$PY" model_training/train_detector.py --mode train --seed "$SEED" --config model_training/configs/detector.yaml --output-dir "$RUN/T2.2"
# 960 续训：训练尺寸与 T2.4 推理尺寸对齐，修复 IoU 0.4–0.5 的牌角漏检
step T2.2v2 "$PY" model_training/train_detector.py --mode train --seed "$SEED" --config model_training/configs/detector.yaml \
  --model "$RUN/T2.2/card_corner_obb_train/weights/best.pt" --image-size 960 --epochs 20 --patience 6 --task T2.2 \
  --output-dir "$RUN/T2.2v2"
step T2.3 "$PY" model_training/verify_models.py --task T2.3 --assets app/src/main/assets/models --check-hash --check-manifest --output-dir "$RUN/T2.3"
step T2.4-predict "$PY" model_training/predict.py --stage detector --weights "$RUN/T2.2v2/card_corner_obb_train/weights/best.pt" \
  --manifest model_training/datasets/dataset_manifest.json --split test --min-confidence 0.6 --image-size 960 \
  --task T2.4 --seed "$SEED" --output-dir "$RUN/T2.4-predict"
step T2.4 "$PY" model_training/evaluate.py --task T2.4 --stage detector --manifest model_training/datasets/dataset_manifest.json \
  --split test --predictions "$RUN/T2.4-predict/predictions.jsonl" --seed "$SEED" --output-dir "$RUN/T2.4"
step T2.5-predict "$PY" model_training/predict.py --stage detector --weights "$RUN/T2.2v2/card_corner_obb_train/weights/best.pt" \
  --manifest model_training/datasets/dataset_manifest.json --split calibration --min-confidence 0.01 --image-size 960 \
  --task T2.5 --seed "$SEED" --output-dir "$RUN/T2.5-predict"
step T2.5 "$PY" model_training/calibrate.py --task T2.5 --target detector-postprocess --split calibration \
  --config model_training/configs/detector_grid.yaml --predictions "$RUN/T2.5-predict/predictions.jsonl" \
  --seed "$SEED" --output-dir "$RUN/T2.5"

# M3 分类器
step T3.1 "$PY" model_training/prepare_crops.py --manifest model_training/datasets/dataset_manifest.json \
  --size 96 128 --rotations 0 180 --max-crops 400 --seed "$SEED" --output-dir "$RUN/T3.1"
step T3.2 "$PY" model_training/train.py --mode train --crops "$RUN/T3.1/crops_manifest.json" \
  --seed "$SEED" --config model_training/configs/classifier.yaml --output-dir "$RUN/T3.2"
step T3.3-predict "$PY" model_training/predict.py --stage classifier --weights "$RUN/T3.2/best.keras" \
  --crops "$RUN/T3.1/crops_manifest.json" --split calibration --temperature 2.0 \
  --task T3.3 --seed "$SEED" --output-dir "$RUN/T3.3-predict"
step T3.3 "$PY" model_training/calibrate.py --task T3.3 --target classifier --split calibration \
  --predictions "$RUN/T3.3-predict/predictions.jsonl" --seed "$SEED" --output-dir "$RUN/T3.3"
step T3.4 "$PY" model_training/verify_models.py --task T3.4 --assets app/src/main/assets/models --check-hash --output-dir "$RUN/T3.4"
step T3.5-predict "$PY" model_training/predict.py --stage classifier --weights "$RUN/T3.2/best.keras" \
  --crops "$RUN/T3.1/crops_manifest.json" --split test --temperature 2.0 \
  --task T3.5 --seed "$SEED" --output-dir "$RUN/T3.5-predict"
step T3.5 "$PY" model_training/evaluate.py --stage classifier --manifest model_training/datasets/dataset_manifest.json \
  --split test --predictions "$RUN/T3.5-predict/predictions.jsonl" --seed "$SEED" --task T3.5 --output-dir "$RUN/T3.5"

# M4 Android 灰度
step T4.1 "$PY" model_training/verify_models.py --task T4.1 --assets app/src/main/assets/models --check-shape --check-labels --check-hash --output-dir "$RUN/T4.1"
step T4.2 "$PY" model_training/run_gradle_task.py --task T4.2 --tests '*CardGeometryTest' --tests '*CardCornerDetectorTest' --output-dir "$RUN/T4.2"
step T4.3 "$PY" model_training/run_gradle_task.py --task T4.3 --tests '*CardCornerClassifierTest' --tests '*CardGeometryTest' --output-dir "$RUN/T4.3"
step T4.4 "$PY" model_training/run_gradle_task.py --task T4.4 --tests '*RecognitionShadowTest' --output-dir "$RUN/T4.4"
step T4.5 "$PY" model_training/run_gradle_task.py --task T4.5 --tests '*RecognitionResultTest' --tests '*RecognitionStateTest' --output-dir "$RUN/T4.5"
step T4.6 "$PY" model_training/run_gradle_task.py --task T4.6 --tests '*RecognitionFallbackTest' --output-dir "$RUN/T4.6"
step T4.7 "$PY" model_training/device_benchmark.py --api 29 --cases case4 --repeat 10 --stress-sequence 20 --output-dir "$RUN/T4.7"

# M5 主动学习与发布
step T5.1 "$PY" model_training/active_learning.py --manifest model_training/datasets/dataset_manifest.json --input model_training/inbox \
  --crops "$RUN/T3.1/crops_manifest.json" --predictions "$RUN/T3.5-predict/predictions.jsonl" --output-dir "$RUN/T5.1"
step T5.2 "$PY" model_training/package_model.py --model app/src/main/assets/models/classifier.tflite --manifest app/src/main/assets/models/model_manifest.json --output-dir "$RUN/T5.2"
step T5.3 "$PY" model_training/evaluate.py --stage e2e --mode shadow --baseline heuristic --split test --task T5.3 --seed "$SEED" --output-dir "$RUN/T5.3"
step T5.4 "$PY" model_training/validate_docs.py --check-commands --check-task-coverage --check-links --seed "$SEED" --output-dir "$RUN/T5.4"

# 横切
step X1 "$PY" model_training/license_audit.py --seed "$SEED" --output-dir "$RUN/X1"
step X2 "$PY" model_training/offline_audit.py --source app/src/main --assets app/src/main/assets/models --seed "$SEED" --output-dir "$RUN/X2"
step X3 "$PY" model_training/gate.py --milestone all --reports "$RUN" --task-manifest model_training/task_manifest.json \
  --state harness/state.md --seed "$SEED" --output-dir "$RUN/X3"

echo "见 $RUN/X3/gate-summary.md" | tee -a "$LOG"
grep -E '^(exit=|=== )' "$LOG" | tail -40
