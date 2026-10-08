"""Private CPU sentence embeddings. Never log request bodies or resident memories."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

MODEL = 'all-MiniLM-L6-v2:1110a243:mean-pool-v1'
options = ort.SessionOptions()
options.intra_op_num_threads = 2
options.inter_op_num_threads = 1
session = ort.InferenceSession('/model/model.onnx', sess_options=options, providers=['CPUExecutionProvider'])
tokenizer = Tokenizer.from_file('/model/tokenizer.json')
tokenizer.enable_truncation(max_length=256)
tokenizer.enable_padding(pad_id=0, pad_token='[PAD]')
lock = threading.Lock()


def embed(texts):
    # The tokenizer has mutable batch padding state; serialize bounded batches.
    with lock:
        encoded = tokenizer.encode_batch(texts)
        arrays = {
            'input_ids': np.array([e.ids for e in encoded], dtype=np.int64),
            'attention_mask': np.array([e.attention_mask for e in encoded], dtype=np.int64),
            'token_type_ids': np.array([e.type_ids for e in encoded], dtype=np.int64),
        }
        tokens = session.run(None, {i.name: arrays[i.name] for i in session.get_inputs()})[0]
        mask = arrays['attention_mask'][..., None]
        vectors = (tokens * mask).sum(axis=1) / np.maximum(mask.sum(axis=1), 1e-9)
        vectors /= np.maximum(np.linalg.norm(vectors, axis=1, keepdims=True), 1e-9)
        if vectors.shape != (len(texts), 384) or not np.isfinite(vectors).all():
            raise ValueError('Invalid model output')
        return vectors.tolist()


class Handler(BaseHTTPRequestHandler):
    def reply(self, status, body):
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        self.reply(200 if self.path == '/health' else 404, {'model': MODEL})

    def do_POST(self):
        if self.path != '/embed':
            return self.reply(404, {'error': 'Not found'})
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 < length <= 600000:
                return self.reply(413, {'error': 'Request too large'})
            texts = json.loads(self.rfile.read(length)).get('texts')
            if not isinstance(texts, list) or not 1 <= len(texts) <= 32 or any(
                    not isinstance(t, str) or not t.strip() or len(t) > 6000 for t in texts):
                return self.reply(400, {'error': 'Provide 1-32 nonempty texts of at most 6000 characters'})
            self.reply(200, {'model': MODEL, 'embeddings': embed(texts)})
        except (ValueError, TypeError, AttributeError):
            self.reply(400, {'error': 'Invalid request'})
        except Exception:
            self.reply(503, {'error': 'Embedding unavailable'})

    def log_message(self, *_):
        pass


if __name__ == '__main__':
    ThreadingHTTPServer(('0.0.0.0', 8090), Handler).serve_forever()
