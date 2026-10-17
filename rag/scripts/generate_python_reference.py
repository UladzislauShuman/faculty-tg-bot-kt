"""Генерирует эталонные данные Python-версии для проверки паритета Kotlin-порта.

Зачем: Kotlin-реализации эмбеддингов, sparse-кодировщика, реранкера и чанкера должны давать те же числа и тот же текст,
что Python-baseline. Скрипт запускается ОДИН раз в окружении Python-проекта, результат (JSON) лежит в
src/test/resources/parity и проверяется тестами Kotlin.

Запуск (из каталога faculty-tg-bot):
    .venv/bin/python ../faculty-tg-bot-kt/rag/scripts/generate_python_reference.py ../faculty-tg-bot-kt/rag/src/test/resources/parity
"""
import json
import sys
import urllib.request
from pathlib import Path

OUT = Path(sys.argv[1])
OUT.mkdir(parents=True, exist_ok=True)

# Примеры в духе вопросов и текстов сайта ФПМИ: кириллица, латиница, цифры, ссылки, знаки препинания.
TEXTS = [
    "Как подать документы на отработку?",
    "Преддипломная практика проводится на 4 курсе. Договор о проведении практики нужно подписать до 1 марта.",
    "Сотрудники ФПМИ публикуются в журналах из перечня ВАК, а также в базах Scopus и WoS.",
    "Контакты деканата: телефон +375 17 209-50-00, электронная почта fpmi@bsu.by",
    "Python и Kotlin — языки программирования; Java 25 вышла в 2025 году.",
]
QUERIES = ["куда нести документы на практику", "проходной балл на ФПМИ", "Деканат ФПМИ телефон"]
PAIRS = [
    ("Как подать документы на отработку?", "Для отработки необходимо подать заявление в деканат в течение трёх дней после пропуска."),
    ("Как подать документы на отработку?", "Сотрудники факультета публикуются в рецензируемых научных журналах."),
    ("Кто декан ФПМИ?", "Деканом факультета прикладной математики и информатики является доктор физико-математических наук."),
]

# --- 1. Dense: BAAI/bge-m3 через sentence-transformers, как в HuggingFaceEmbeddings проекта ---
from sentence_transformers import SentenceTransformer  # noqa: E402

dense_model = SentenceTransformer("BAAI/bge-m3", device="cpu", local_files_only=True)
dense_vectors = dense_model.encode(TEXTS + QUERIES, normalize_embeddings=True)
json.dump(
    {"model": "BAAI/bge-m3", "texts": TEXTS + QUERIES, "vectors": [[float(x) for x in v] for v in dense_vectors]},
    open(OUT / "dense_bge_m3.json", "w", encoding="utf-8"),
    ensure_ascii=False,
)

# --- 2. Sparse: FastEmbedSparse("Qdrant/bm25"), ровно как в qdrant_retriever.py и pipeline.py ---
from langchain_qdrant import FastEmbedSparse  # noqa: E402

sparse = FastEmbedSparse(model_name="Qdrant/bm25")
doc_vectors = sparse.embed_documents(TEXTS)
query_vectors = [sparse.embed_query(q) for q in QUERIES]
json.dump(
    {
        "model": "Qdrant/bm25",
        "documents": [
            {"text": t, "indices": [int(i) for i in v.indices], "values": [float(x) for x in v.values]}
            for t, v in zip(TEXTS, doc_vectors)
        ],
        "queries": [
            {"text": q, "indices": [int(i) for i in v.indices], "values": [float(x) for x in v.values]}
            for q, v in zip(QUERIES, query_vectors)
        ],
    },
    open(OUT / "sparse_bm25.json", "w", encoding="utf-8"),
    ensure_ascii=False,
)

# --- 3. Реранкер: HuggingFaceCrossEncoder из конфига ---
from langchain_community.cross_encoders import HuggingFaceCrossEncoder  # noqa: E402

cross = HuggingFaceCrossEncoder(model_name="DiTy/cross-encoder-russian-msmarco")
scores = cross.score([list(p) for p in PAIRS])
json.dump(
    {
        "model": "DiTy/cross-encoder-russian-msmarco",
        "pairs": [{"query": q, "passage": p, "score": float(s)} for (q, p), s in zip(PAIRS, scores)],
    },
    open(OUT / "reranker_scores.json", "w", encoding="utf-8"),
    ensure_ascii=False,
)

# --- 4. Чанкер: настоящая страница сайта → родители и дети ParentChildHTMLChunker ---
from langchain_core.documents import Document  # noqa: E402
from src.parsing_and_chunking.chunkers.parent_child_chunker import ParentChildHTMLChunker  # noqa: E402

PAGE_URL = "https://fpmi.bsu.by/graduate"
# Сервер fpmi.bsu.by отдаёт неполную цепочку сертификатов (нет промежуточного): Python не находит издателя,
# поэтому в Python-версии стояло verify=False. Здесь, в одноразовом скрипте, проверку отключаем осознанно;
# в Kotlin-приложении вместо этого подключается недостающий сертификат (см. crawler.tls в application.yaml).
import ssl  # noqa: E402

unverified = ssl._create_unverified_context()
request = urllib.request.Request(PAGE_URL, headers={"User-Agent": "Mozilla/5.0"})
html = urllib.request.urlopen(request, timeout=30, context=unverified).read().decode("utf-8")
(OUT / "graduate_page.html").write_text(html, encoding="utf-8")

chunker = ParentChildHTMLChunker(child_chunk_size=300, parent_chunk_size=1200)
children, parents = chunker.chunk_with_parents(Document(page_content=html, metadata={"source": PAGE_URL}))
json.dump(
    {
        "source": PAGE_URL,
        "parents": [{"id": p.metadata["doc_id"], "text": p.page_content, "metadata": p.metadata} for p in parents],
        "children": [{"parent_id": c.metadata["parent_id"], "text": c.page_content, "metadata": c.metadata} for c in children],
    },
    open(OUT / "chunker_graduate.json", "w", encoding="utf-8"),
    ensure_ascii=False,
)
print(f"parents={len(parents)} children={len(children)} written to {OUT}")
