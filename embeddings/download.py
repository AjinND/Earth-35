"""Bake pinned public model weights into the image; no network is needed at runtime."""
from huggingface_hub import hf_hub_download
from pathlib import Path
import shutil

REPOSITORY = "sentence-transformers/all-MiniLM-L6-v2"
REVISION = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41"
Path('/model').mkdir(exist_ok=True)
for remote, local in [('onnx/model.onnx', 'model.onnx'), ('tokenizer.json', 'tokenizer.json')]:
    shutil.copyfile(hf_hub_download(REPOSITORY, remote, revision=REVISION), '/model/' + local)
