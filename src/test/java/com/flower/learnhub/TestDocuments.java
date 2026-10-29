package com.flower.learnhub;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

final class TestDocuments {
    private TestDocuments() {}
    static byte[] pdf(int pages, boolean hasText, boolean encrypted) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (hasText) try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700);
                    content.showText("Learning knowledge retrieval page " + (i + 1));
                    content.endText();
                }
            }
            if (encrypted) {
                StandardProtectionPolicy policy = new StandardProtectionPolicy("test-only-owner", "test-only-reader", new AccessPermission());
                policy.setEncryptionKeyLength(128);
                document.protect(policy);
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    static byte[] overCharacterLimitPdf() throws IOException {
        // Only 201 KB before compression; never construct a multi-GB decompression bomb in tests.
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 1);
                content.newLineAtOffset(1, 700);
                content.showText("a".repeat(TextProcessor.MAX_CHARS + 1));
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
