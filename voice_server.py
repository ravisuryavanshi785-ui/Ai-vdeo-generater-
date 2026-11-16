import base64
import os
import subprocess
import sys
import tempfile
from typing import List

import modal
from pydantic import BaseModel

hf_cache = modal.Volume.from_name("rs-voice-hf-cache", create_if_missing=True)

image = (
    modal.Image.debian_slim(python_version="3.10")
    .apt_install("git", "ffmpeg", "libsndfile1")
    .run_commands(
        "git clone --depth 1 https://github.com/Plachtaa/seed-vc.git /opt/seed-vc",
        "pip install --no-cache-dir -r /opt/seed-vc/requirements.txt",
    )
    .pip_install("fastapi[standard]", "pydantic>=2.0", "huggingface_hub", "soundfile")
    .env({"HF_HOME": "/cache/hf"})
)

app = modal.App("rs-movie-spot-voice")


class ClipInput(BaseModel):
    id: int
    audio_b64: str
    pitch_shift: int = 0


class BatchCloneRequest(BaseModel):
    pin: str
    reference_audio_b64: str
    clips: List[ClipInput]
    diffusion_steps: int = 20
    auto_f0_adjust: bool = True
    inference_cfg_rate: float = 0.7


class ClipOutput(BaseModel):
    id: int
    wav_b64: str


class BatchCloneResponse(BaseModel):
    ok: bool
    error: str = ""
    clips: List[ClipOutput] = []


def _to_mono_wav(raw_bytes: bytes, out_wav: str, max_seconds: int = 0) -> None:
    with tempfile.NamedTemporaryFile(delete=False, suffix=".audio") as tmp:
        tmp.write(raw_bytes)
        in_path = tmp.name
    try:
        cmd = ["ffmpeg", "-y", "-i", in_path, "-ac", "1", "-ar", "22050"]
        if max_seconds > 0:
            cmd += ["-t", str(max_seconds)]
        cmd += ["-c:a", "pcm_s16le", out_wav]
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    finally:
        if os.path.exists(in_path):
            os.remove(in_path)


@app.cls(
    image=image,
    gpu="T4",
    volumes={"/cache": hf_cache},
    secrets=[modal.Secret.from_name("rs-voice-secret")],
    scaledown_window=45,
    timeout=300,
)
class VoiceService:
    @modal.enter()
    def load_model(self):
        sys.path.insert(0, "/opt/seed-vc")
        os.chdir("/opt/seed-vc")
        import torch
        from huggingface_hub import hf_hub_download
        from modules.commons import load_checkpoint
        from seed_vc_wrapper import SeedVCWrapper

        device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        self.vc = SeedVCWrapper(device=device)

        try:
            indic_ckpt = hf_hub_download(
                repo_id="DreamSyncCo/IndicVoiceChanger",
                filename="Indic-seed-uvit-whisper-small-wavenet.pth",
            )
            self.vc.model, _, _, _ = load_checkpoint(
                self.vc.model,
                None,
                indic_ckpt,
                load_only_params=True,
                ignore_modules=[],
                is_distributed=False,
            )
            for key in self.vc.model:
                self.vc.model[key].eval()
                self.vc.model[key].to(device)
            print("Loaded DreamSyncCo/IndicVoiceChanger Hindi checkpoint.")
        except Exception as exc:
            print(f"Using default Seed-VC checkpoint (Indic fallback skipped: {exc})")

        hf_cache.commit()

    @modal.fastapi_endpoint(method="POST")
    def clone_batch(self, req: BatchCloneRequest) -> BatchCloneResponse:
        expected_pin = os.environ.get("VOICE_API_SECRET", "").strip()
        if expected_pin and req.pin.strip() != expected_pin:
            return BatchCloneResponse(ok=False, error="Invalid PIN")

        if not req.clips:
            return BatchCloneResponse(ok=False, error="No audio clips provided")

        import soundfile as sf

        with tempfile.TemporaryDirectory() as workdir:
            ref_wav = os.path.join(workdir, "reference.wav")
            _to_mono_wav(base64.b64decode(req.reference_audio_b64), ref_wav, max_seconds=25)

            outputs: List[ClipOutput] = []
            for item in req.clips:
                src_wav = os.path.join(workdir, f"src_{item.id}.wav")
                out_wav = os.path.join(workdir, f"out_{item.id}.wav")
                _to_mono_wav(base64.b64decode(item.audio_b64), src_wav, max_seconds=60)

                waveform = None
                sr = 22050
                for _, full_audio in self.vc.convert_voice(
                    source=src_wav,
                    target=ref_wav,
                    diffusion_steps=max(10, min(35, req.diffusion_steps)),
                    length_adjust=1.0,
                    inference_cfg_rate=req.inference_cfg_rate,
                    f0_condition=False,
                    auto_f0_adjust=req.auto_f0_adjust,
                    pitch_shift=item.pitch_shift,
                    stream_output=True,
                ):
                    if full_audio is not None:
                        sr, waveform = full_audio
                        break

                if waveform is None:
                    return BatchCloneResponse(ok=False, error=f"Empty output for clip {item.id}")

                sf.write(out_wav, waveform, sr, subtype="PCM_16")
                with open(out_wav, "rb") as f:
                    outputs.append(ClipOutput(id=item.id, wav_b64=base64.b64encode(f.read()).decode("ascii")))

            return BatchCloneResponse(ok=True, clips=outputs)
