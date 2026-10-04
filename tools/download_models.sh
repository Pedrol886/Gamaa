#!/usr/bin/env bash
set -euo pipefail

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Portuguese offline ASR model.
mkdir -p app/src/main/assets/model
curl -fL --retry 3 --connect-timeout 30 --max-time 300 \
  "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip" \
  -o "$work/vosk-pt.zip"
unzip -tq "$work/vosk-pt.zip"
unzip -q "$work/vosk-pt.zip" -d "$work/pt"
cp -a "$work/pt/vosk-model-small-pt-0.3/." app/src/main/assets/model/

# Language-independent Vosk speaker-identification model. It is not an Android
# unlock mechanism. Gama uses it only as a crowd-focus signal after local enrollment.
mkdir -p app/src/main/assets/spk-model
curl -fL --retry 3 --connect-timeout 30 --max-time 300 \
  "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip" \
  -o "$work/vosk-spk.zip"
unzip -tq "$work/vosk-spk.zip"
unzip -q "$work/vosk-spk.zip" -d "$work/spk"
SPK_DIR="$(find "$work/spk" -mindepth 1 -maxdepth 1 -type d -name 'vosk-model-spk-*' -print -quit)"
test -n "$SPK_DIR"
cp -a "$SPK_DIR/." app/src/main/assets/spk-model/

python3 - <<'CHECK'
from pathlib import Path
import hashlib
# Keep the strict hashes for the core files we actually know and validate the rest by presence.
root = Path('app/src/main/assets')
strict = {
    'model/Gr.fst': 'd81023936f5557c06930802b1db4880f56d6ac51b16ed4e5060ceba06895442c',
    'model/HCLr.fst': '8b45be1fa72913d61e37ac0b411177803848f4cda7728f74139b39acd1c0cf36',
    'model/final.mdl': '3e10e43ec01cf8d968bcc24c626a2eae2bfa77863bee82f36a554854afa1f147',
}
for name, digest in strict.items():
    file = root / name
    if not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest() != digest:
        raise SystemExit('Modelo de reconhecimento ausente ou alterado: ' + name)
spk = root / 'spk-model'
files = [p for p in spk.rglob('*') if p.is_file() and p.stat().st_size > 0]
if len(files) < 3:
    raise SystemExit('Modelo de identificação de locutor incompleto')
print(f'Modelos offline validados: ASR + speaker ({len(files)} arquivos do speaker model).')
CHECK
