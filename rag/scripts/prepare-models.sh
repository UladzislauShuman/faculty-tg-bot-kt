#!/usr/bin/env bash
# Готовит каталог models/ с моделями для запуска и тестов паритета.
#
#   models/bge-m3   — эмбеддинги: ONNX-файлы из кеша HuggingFace (уже скачаны Python-версией, ничего не качается)
#   models/reranker — реранкер: ONNX экспортируется из весов PyTorch (нужно окружение Python-проекта)
#
# Запуск из каталога faculty-tg-bot-kt/rag:  ./scripts/prepare-models.sh
set -euo pipefail

HF_HOME="${HF_HOME:-$HOME/.cache/huggingface}"
PYTHON_PROJECT="${PYTHON_PROJECT:-../../faculty-tg-bot}"
MODELS_DIR="${MODELS_DIR:-models}"
# MODELS_COPY=1 — копировать файлы вместо ссылок. Нужно для Docker: ссылка на кеш хоста внутри контейнера не откроется.
MODELS_COPY="${MODELS_COPY:-0}"

place() {
  if [ "$MODELS_COPY" = "1" ]; then rm -f "$2" && cp -L "$1" "$2"; else ln -sf "$1" "$2"; fi
}

mkdir -p "$MODELS_DIR/bge-m3"
BGE_ONNX=$(ls -d "$HF_HOME"/hub/models--BAAI--bge-m3/snapshots/*/onnx | head -1)
for f in model.onnx model.onnx_data tokenizer.json; do
  place "$BGE_ONNX/$f" "$MODELS_DIR/bge-m3/$f"
done
echo "bge-m3: файлы из $BGE_ONNX (копирование: $MODELS_COPY)"

if [ ! -f "$MODELS_DIR/reranker/model.onnx" ]; then
  (cd "$PYTHON_PROJECT" && PYTHONPATH=. .venv/bin/python "$OLDPWD/scripts/export_reranker_onnx.py" \
    DiTy/cross-encoder-russian-msmarco "$OLDPWD/$MODELS_DIR/reranker")
else
  echo "reranker: уже подготовлен"
fi
