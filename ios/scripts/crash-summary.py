"""Resume un informe de cierre de iOS (.ips, formato JSON de macOS 12+): excepción y pila del hilo que falló.
Uso: crash-summary.py <fichero.ips>  (lo llama .github/workflows/ios.yml tras los tests en simulador)."""
import json
import sys

text = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
header, _, body = text.partition("\n")
try:
    report = json.loads(body)
except Exception:
    print(body[:3000])
    sys.exit(0)

print("exception:", report.get("exception"))
print("termination:", report.get("termination"))
if report.get("asi"):
    print("asi:", report.get("asi"))
faulting = report.get("faultingThread", 0)
threads = report.get("threads", [])
images = report.get("usedImages", [])
if faulting < len(threads):
    for frame in threads[faulting].get("frames", [])[:45]:
        index = frame.get("imageIndex", 0)
        image = images[index].get("name", "?") if index < len(images) else "?"
        print(f"  {image}: {frame.get('symbol', '?')} +{frame.get('symbolLocation', 0)}")
