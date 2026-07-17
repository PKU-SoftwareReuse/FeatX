import os


def normalize_openai_base_url(url: str | None) -> str | None:
    if url is None or not url.strip():
        return None

    normalized = url.strip().rstrip("/")
    lower_url = normalized.lower()
    if lower_url.endswith("/chat/completion") or lower_url.endswith("/chat/completions"):
        raise ValueError("OPENAI_BASE_URL must not include /chat/completions")
    return normalized if lower_url.endswith("/v1") else f"{normalized}/v1"


def openai_base_url() -> str | None:
    return normalize_openai_base_url(os.getenv("OPENAI_BASE_URL"))
