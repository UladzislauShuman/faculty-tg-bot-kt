"""Экспорт cross-encoder реранкера из HuggingFace в формат ONNX для Kotlin-приложения.

Зачем: в репозитории модели DiTy/cross-encoder-russian-msmarco только веса PyTorch, а Kotlin-версия считает реранкер
внутри процесса через ONNX Runtime. Экспорт делается один раз; результат лежит в models/reranker (не в git).

Запуск (из каталога faculty-tg-bot, где есть окружение с torch и transformers):
    .venv/bin/python ../faculty-tg-bot-kt/rag/scripts/export_reranker_onnx.py \
        DiTy/cross-encoder-russian-msmarco ../faculty-tg-bot-kt/rag/models/reranker
"""
import sys
from pathlib import Path

import torch
from transformers import AutoModelForSequenceClassification, AutoTokenizer

model_name, out_dir = sys.argv[1], Path(sys.argv[2])
out_dir.mkdir(parents=True, exist_ok=True)

tokenizer = AutoTokenizer.from_pretrained(model_name, local_files_only=True)
model = AutoModelForSequenceClassification.from_pretrained(model_name, local_files_only=True).eval()

sample = tokenizer("пример вопроса", "пример фрагмента", return_tensors="pt")
inputs = (sample["input_ids"], sample["attention_mask"], sample["token_type_ids"])

torch.onnx.export(
    model,
    inputs,
    str(out_dir / "model.onnx"),
    input_names=["input_ids", "attention_mask", "token_type_ids"],
    output_names=["logits"],
    # Размер пакета и длина последовательности меняются от вызова к вызову.
    dynamic_axes={
        "input_ids": {0: "batch", 1: "sequence"},
        "attention_mask": {0: "batch", 1: "sequence"},
        "token_type_ids": {0: "batch", 1: "sequence"},
        "logits": {0: "batch"},
    },
    opset_version=17,
    dynamo=False,
)
tokenizer.save_pretrained(out_dir)
print(f"Готово: {out_dir / 'model.onnx'}, токенизатор: {out_dir / 'tokenizer.json'}")
