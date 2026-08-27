def make_multipage_pdf(path, num_pages, lines_per_page=30):
    objects = []
    objects.append(b"<< /Type /Catalog /Pages 2 0 R >>")

    page_obj_start = 3
    content_obj_start = page_obj_start + num_pages
    kids = " ".join(f"{page_obj_start + i} 0 R" for i in range(num_pages))
    objects.append(f"<< /Type /Pages /Kids [{kids}] /Count {num_pages} >>".encode())

    for p in range(num_pages):
        content_obj_num = content_obj_start + p
        objects.append(
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            f"/Resources << /Font << /F1 {content_obj_start + num_pages} 0 R >> >> "
            f"/Contents {content_obj_num} 0 R >>".encode()
        )

    for p in range(num_pages):
        lines = [f"BT /F1 12 Tf"]
        y = 750
        lines.append(f"1 0 0 1 72 {y} Tm (Page {p+1} of {num_pages} - Test Document) Tj")
        y -= 30
        for i in range(lines_per_page):
            text = f"Line {i+1}: This is sample body text on page {p+1}, used to test multi-page PDF rendering performance."
            lines.append(f"1 0 0 1 72 {y} Tm ({text}) Tj")
            y -= 18
            if y < 40:
                break
        lines.append("ET")
        content = "\n".join(lines).encode("latin-1")
        stream_obj = b"<< /Length %d >>\nstream\n" % len(content) + content + b"\nendstream"
        objects.append(stream_obj)

    objects.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")

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

make_multipage_pdf("test-document-15page.pdf", 15)
print("wrote test-document-15page.pdf")
