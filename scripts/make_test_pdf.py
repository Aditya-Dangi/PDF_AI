import zlib

def make_pdf(path, lines):
    content_lines = []
    y = 750
    content_lines.append("BT /F1 12 Tf")
    for line in lines:
        esc = line.replace("\\", r"\\").replace("(", r"\(").replace(")", r"\)")
        content_lines.append(f"1 0 0 1 72 {y} Tm ({esc}) Tj")
        y -= 20
    content_lines.append("ET")
    content = "\n".join(content_lines).encode("latin-1")

    objects = []
    objects.append(b"<< /Type /Catalog /Pages 2 0 R >>")
    objects.append(b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
    objects.append(
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        b"/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>"
    )
    objects.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    stream_obj = b"<< /Length %d >>\nstream\n" % len(content) + content + b"\nendstream"
    objects.append(stream_obj)

    out = bytearray()
    out += b"%PDF-1.4\n"
    offsets = [0]
    for i, obj in enumerate(objects, start=1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n".encode("latin-1")
        out += obj
        out += b"\nendobj\n"

    xref_offset = len(out)
    n = len(objects) + 1
    out += f"xref\n0 {n}\n".encode("latin-1")
    out += b"0000000000 65535 f \n"
    for off in offsets[1:]:
        out += f"{off:010d} 00000 n \n".encode("latin-1")

    out += b"trailer\n"
    out += f"<< /Size {n} /Root 1 0 R >>\n".encode("latin-1")
    out += b"startxref\n"
    out += f"{xref_offset}\n".encode("latin-1")
    out += b"%%EOF"

    with open(path, "wb") as f:
        f.write(out)

lines = [
    "AI Document Fact Checker - Test Document",
    "",
    "Claim one: Drinking lemon water first thing in the morning detoxifies the liver.",
    "",
    "Claim two: The Eiffel Tower is located in Paris, France, and was completed in 1889.",
    "",
    "Claim three: Regular exercise is associated with a lower risk of cardiovascular disease.",
    "",
    "This document is used only to verify that the fact-checker application correctly",
    "extracts text, retrieves relevant passages, and highlights the source evidence.",
]

make_pdf("test-document.pdf", lines)
print("wrote test-document.pdf")
