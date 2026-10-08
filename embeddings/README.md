# Local memory embeddings

Compose builds a private CPU service with the Apache-2.0 [sentence-transformers/all-MiniLM-L6-v2](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2) model. Weights and tokenizer are pinned to revision `1110a243fdf4706b3f48f1d95db1a4f5529b4d41`, downloaded during the build and baked into the image. Runtime inference does not contact Hugging Face or any paid embedding provider.

`POST /embed` accepts `{"texts":["a memory or query"]}` and returns `model` and normalized 384-dimensional `embeddings`. The backend checks the model identifier and dimensions before storing or searching vectors. Inputs are bounded to 32 texts per batch and 6,000 characters per text; tokenization follows the model's 256-token limit. This English sentence model is intended for concise memories. Full memory text is retained for lexical retrieval, including text beyond that token window.

The model identifier includes the weight revision and mean-pooling version. When changing the model or pooling, use a new identifier and explicitly migrate/backfill that space; vectors from different models must not be compared. Existing owner-supplied vectors are never overwritten.

`GET /health` is used by Docker after model loading. There is no published host port. The process runs as an unprivileged user and does not log memory content. API persistence and lexical/entity recall continue if this service becomes unavailable; PostgreSQL rows with missing vectors are the durable retry queue. The backend processes 32 pending rows every ten seconds after recovery.

For an API running outside Compose, provide `EMBEDDING_URL` pointing to a reachable instance of this service. The default is `http://localhost:8090/embed`; Compose uses its internal `http://embeddings:8090/embed` endpoint.
